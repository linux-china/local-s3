package com.robothy.s3.test.delta;

import io.delta.kernel.defaults.engine.fileio.FileIO;
import io.delta.kernel.defaults.engine.fileio.InputFile;
import io.delta.kernel.defaults.engine.fileio.OutputFile;
import io.delta.kernel.defaults.engine.fileio.PositionOutputStream;
import io.delta.kernel.defaults.engine.fileio.SeekableInputStream;
import io.delta.kernel.utils.CloseableIterator;
import io.delta.kernel.utils.FileStatus;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * The storage that Delta Lake reads and writes a table through, backed by LocalS3.
 *
 * <p>Delta's {@code delta-kernel-defaults} builds its whole engine — the JSON handler that writes a commit, the
 * Parquet handler that writes the data files, and the file system client that lists the log — on this one interface,
 * so plugging LocalS3 in needs nothing but the AWS SDK that these tests already use. No Hadoop {@code S3A}, and none
 * of the AWS SDK bundle that it drags in.
 *
 * <p><b>{@linkplain S3OutputFile#writeAtomically} is the interesting method.</b> It is how Delta commits: a version of
 * a table becomes visible by creating {@code _delta_log/<version>.json}, and the commit is only correct if creating
 * that object fails when another writer already created it. On S3 that is a {@code PUT} carrying
 * {@code If-None-Match: *}, and the {@code 412 PreconditionFailed} that LocalS3 answers the loser with is translated
 * here into the {@linkplain FileAlreadyExistsException} that Delta reads as a lost commit. So the conflict detection
 * of Delta on LocalS3 rests exactly on the conditional write of LocalS3 — which is what
 * {@code DeltaLakeIntegrationTest} is there to prove.
 *
 * <p>This is a test double, and it is written for clarity rather than for scale: an object being read is fetched
 * whole, and an object being written is buffered in memory until it is closed. The tables of the tests are a few
 * kilobytes.
 */
public final class LocalS3DeltaFileIO implements FileIO {

  private static final String SCHEME = "s3://";

  private final S3Client s3;

  private final String bucket;

  /**
   * Create the storage.
   *
   * @param s3 the client of the LocalS3 service.
   * @param bucket the bucket that the tables live in.
   */
  public LocalS3DeltaFileIO(S3Client s3, String bucket) {
    this.s3 = Objects.requireNonNull(s3, "s3");
    this.bucket = Objects.requireNonNull(bucket, "bucket");
  }

  /**
   * The objects of the same directory whose paths sort at or after {@code path}, in order.
   *
   * <p>This is how Delta reads a log: it asks for everything in {@code _delta_log/} from the version it already knows,
   * and the answer must be sorted, so that {@code 00000000000000000003.json} follows
   * {@code 00000000000000000002.json}.
   *
   * @return the listing; empty if the directory holds nothing at or after {@code path}, which is also how a table that
   *     doesn't exist yet looks.
   */
  @Override
  public CloseableIterator<FileStatus> listFrom(String path) {
    String from = key(path);
    String directory = directoryOf(from);
    List<FileStatus> found = new ArrayList<>();
    String continuationToken = null;
    do {
      ListObjectsV2Response page = s3.listObjectsV2(ListObjectsV2Request.builder()
          .bucket(bucket)
          .prefix(directory)
          // Only the direct children: a listing of a directory must not descend into the ones below it.
          .delimiter("/")
          .continuationToken(continuationToken)
          .build());
      for (S3Object object : page.contents()) {
        if (object.key().compareTo(from) >= 0) {
          found.add(FileStatus.of(uri(object.key()), object.size(), object.lastModified().toEpochMilli()));
        }
      }
      continuationToken = Boolean.TRUE.equals(page.isTruncated()) ? page.nextContinuationToken() : null;
    } while (continuationToken != null);

    found.sort(Comparator.comparing(FileStatus::getPath));
    return closeable(found.iterator());
  }

  @Override
  public FileStatus getFileStatus(String path) throws IOException {
    String key = key(path);
    try {
      HeadObjectResponse head = s3.headObject(request -> request.bucket(bucket).key(key));
      return FileStatus.of(uri(key), head.contentLength(), head.lastModified().toEpochMilli());
    } catch (NoSuchKeyException e) {
      throw new FileNotFoundException(path);
    }
  }

  /**
   * The path as it is: the paths of these tests are already absolute {@code s3://} URIs.
   */
  @Override
  public String resolvePath(String path) {
    return uri(key(path));
  }

  /**
   * S3 has no directories — a key that ends in {@code /} is just a name — so there is nothing to create.
   */
  @Override
  public boolean mkdirs(String path) {
    return true;
  }

  @Override
  public InputFile newInputFile(String path, long size) {
    return new S3InputFile(key(path), size);
  }

  @Override
  public OutputFile newOutputFile(String path) {
    return new S3OutputFile(key(path));
  }

  @Override
  public boolean delete(String path) {
    String key = key(path);
    try {
      s3.headObject(request -> request.bucket(bucket).key(key));
    } catch (NoSuchKeyException e) {
      return false;
    }
    s3.deleteObject(request -> request.bucket(bucket).key(key));
    return true;
  }

  /**
   * No engine configuration: the defaults of Delta apply.
   */
  @Override
  public Optional<String> getConf(String key) {
    return Optional.empty();
  }

  /**
   * Copy an object, refusing to replace one that exists unless {@code overwrite}. Read and write rather than
   * {@code CopyObject}, so that the destination goes through the same conditional write as a commit does.
   */
  @Override
  public void copyFileAtomically(String source, String destination, boolean overwrite) throws IOException {
    byte[] content = read(key(source));
    put(key(destination), content, overwrite);
  }

  /*
   * The objects themselves.
   */

  private final class S3InputFile implements InputFile {

    private final String key;

    private final long size;

    private S3InputFile(String key, long size) {
      this.key = key;
      this.size = size;
    }

    @Override
    public long length() throws IOException {
      if (size > 0) {
        return size;
      }
      return getFileStatus(uri(key)).getSize();
    }

    @Override
    public String path() {
      return uri(key);
    }

    @Override
    public SeekableInputStream newStream() throws IOException {
      return new ByteArraySeekableInputStream(read(key));
    }
  }

  private final class S3OutputFile implements OutputFile {

    private final String key;

    private S3OutputFile(String key) {
      this.key = key;
    }

    @Override
    public String path() {
      return uri(key);
    }

    /**
     * A stream that stores the object when it is closed, which the Parquet writer of Delta writes a data file through.
     */
    @Override
    public PositionOutputStream create(boolean overwrite) {
      return new BufferedPositionOutputStream(key, overwrite);
    }

    /**
     * Store the object in one request, and fail rather than replace an object that exists unless {@code overwrite}.
     *
     * <p>This is the commit of Delta. Every action of the commit is a line of the file, and the file is
     * {@code _delta_log/<version>.json}: the version becomes visible when this object is created, and two writers that
     * both build version {@code n} must not both create it. {@code If-None-Match: *} is what decides between them, and
     * the writer that LocalS3 answers {@code 412} gets the {@linkplain FileAlreadyExistsException} that Delta turns
     * into a retryable conflict.
     */
    @Override
    public void writeAtomically(CloseableIterator<String> lines, boolean overwrite) throws IOException {
      StringBuilder content = new StringBuilder();
      try (CloseableIterator<String> toWrite = lines) {
        while (toWrite.hasNext()) {
          content.append(toWrite.next()).append('\n');
        }
      }
      put(key, content.toString().getBytes(StandardCharsets.UTF_8), overwrite);
    }
  }

  private final class BufferedPositionOutputStream extends PositionOutputStream {

    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

    private final String key;

    private final boolean overwrite;

    private boolean closed;

    private BufferedPositionOutputStream(String key, boolean overwrite) {
      this.key = key;
      this.overwrite = overwrite;
    }

    @Override
    public long getPos() {
      return buffer.size();
    }

    @Override
    public void write(int b) {
      buffer.write(b);
    }

    @Override
    public void write(byte[] bytes, int offset, int length) {
      buffer.write(bytes, offset, length);
    }

    @Override
    public void close() throws IOException {
      if (closed) {
        return;
      }
      closed = true;
      put(key, buffer.toByteArray(), overwrite);
    }
  }

  /**
   * A stream over the content of an object, which the Parquet reader seeks around in to find the footer and then the
   * row groups it needs.
   */
  private static final class ByteArraySeekableInputStream extends SeekableInputStream {

    private final byte[] content;

    private int position;

    private ByteArraySeekableInputStream(byte[] content) {
      this.content = content;
    }

    @Override
    public long getPos() {
      return position;
    }

    @Override
    public void seek(long newPosition) throws IOException {
      if (newPosition < 0 || newPosition > content.length) {
        throw new IOException("Cannot seek to " + newPosition + " of " + content.length + " bytes.");
      }
      position = (int) newPosition;
    }

    @Override
    public void readFully(byte[] destination, int offset, int length) throws IOException {
      if (position + length > content.length) {
        throw new IOException("Cannot read " + length + " bytes at " + position + " of " + content.length + ".");
      }
      System.arraycopy(content, position, destination, offset, length);
      position += length;
    }

    @Override
    public int read() {
      return position >= content.length ? -1 : content[position++] & 0xFF;
    }

    @Override
    public int read(byte[] destination, int offset, int length) {
      if (position >= content.length) {
        return -1;
      }
      int read = Math.min(length, content.length - position);
      System.arraycopy(content, position, destination, offset, read);
      position += read;
      return read;
    }

    @Override
    public int available() {
      return content.length - position;
    }
  }

  /*
   * The requests.
   */

  private byte[] read(String key) throws IOException {
    try {
      ResponseBytes<?> object = s3.getObjectAsBytes(GetObjectRequest.builder()
          .bucket(bucket).key(key).build());
      return object.asByteArray();
    } catch (NoSuchKeyException e) {
      throw new FileNotFoundException(uri(key));
    }
  }

  /**
   * Store an object, conditionally when it must not replace one.
   *
   * @param overwrite whether an object that the key already holds may be replaced. {@code false} sends
   *     {@code If-None-Match: *}, which LocalS3 answers with {@code 412 PreconditionFailed} if the key is taken.
   * @throws FileAlreadyExistsException if the key is taken and {@code overwrite} is {@code false}.
   */
  private void put(String key, byte[] content, boolean overwrite) throws IOException {
    PutObjectRequest.Builder request = PutObjectRequest.builder().bucket(bucket).key(key);
    if (!overwrite) {
      request.ifNoneMatch("*");
    }
    try {
      s3.putObject(request.build(), RequestBody.fromBytes(content));
    } catch (S3Exception e) {
      if (e.statusCode() == 412) {
        throw new FileAlreadyExistsException(uri(key));
      }
      throw e;
    }
  }

  /*
   * Paths. Delta addresses everything by the s3://bucket/key URI that resolvePath() answered.
   */

  private String key(String path) {
    if (!path.startsWith(SCHEME)) {
      throw new IllegalArgumentException("Not an s3:// path: " + path);
    }
    String rest = path.substring(SCHEME.length());
    int slash = rest.indexOf('/');
    if (slash < 0) {
      return "";
    }
    String name = rest.substring(0, slash);
    if (!bucket.equals(name)) {
      throw new IllegalArgumentException("Expected a path of the bucket " + bucket + ", got: " + path);
    }
    return rest.substring(slash + 1);
  }

  private String uri(String key) {
    return SCHEME + bucket + "/" + key;
  }

  private static String directoryOf(String key) {
    int slash = key.lastIndexOf('/');
    return slash < 0 ? "" : key.substring(0, slash + 1);
  }

  private static <T> CloseableIterator<T> closeable(Iterator<T> iterator) {
    return new CloseableIterator<>() {

      @Override
      public boolean hasNext() {
        return iterator.hasNext();
      }

      @Override
      public T next() {
        if (!hasNext()) {
          throw new NoSuchElementException();
        }
        return iterator.next();
      }

      @Override
      public void close() {
      }
    };
  }

}
