package com.robothy.s3.test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.rest.LocalS3;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.EtagSource;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FSDataOutputStreamBuilder;
import org.apache.hadoop.fs.FileAlreadyExistsException;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocatedFileStatus;
import org.apache.hadoop.fs.Options;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.PathIOException;
import org.apache.hadoop.fs.RemoteIterator;
import org.apache.hadoop.fs.s3a.RemoteFileChangedException;
import org.apache.hadoop.fs.s3a.S3AFileSystem;
import org.apache.hadoop.fs.s3a.commit.CommitConstants;
import org.apache.hadoop.fs.s3a.commit.magic.MagicS3GuardCommitter;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.mapreduce.JobID;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.TaskAttemptID;
import org.apache.hadoop.mapreduce.TaskID;
import org.apache.hadoop.mapreduce.TaskType;
import org.apache.hadoop.mapreduce.task.JobContextImpl;
import org.apache.hadoop.mapreduce.task.TaskAttemptContextImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * Hadoop's S3A connector on LocalS3, the file system that Spark, Delta Lake, Hudi and Paimon read and write
 * {@code s3a://} paths with:
 *
 * <pre>{@code
 * fs.s3a.endpoint=http://localhost:29090
 * fs.s3a.path.style.access=true
 * fs.s3a.access.key=admin
 * fs.s3a.secret.key=admin
 * fs.s3a.endpoint.region=us-east-1
 * }</pre>
 *
 * <p>S3A emulates a directory tree on the flat keys of a bucket: {@code mkdirs} puts a marker object {@code dir/},
 * {@code listStatus} lists with a delimiter, {@code rename} copies every object and deletes the sources, a large file is
 * a multipart upload, and a large rename a multipart copy. The magic committer of Spark writes the files of a task as
 * multipart uploads it doesn't complete, and completes them when the job commits, so the output appears at once or not
 * at all. A conditional create, which the commit protocols of Delta Lake and Paimon are built on, is a {@code PutObject}
 * with {@code If-None-Match: *}, or with {@code If-Match} for an overwrite of a known version.
 */
@Tag("data-tools")
@Timeout(value = 3, unit = TimeUnit.MINUTES)
class HadoopS3AIntegrationTest {

  private static final String BUCKET = "lake";

  private static final int MIB = 1024 * 1024;

  private LocalS3 localS3;

  private S3Client s3;

  private final List<FileSystem> fileSystems = new ArrayList<>();

  /**
   * Where S3A buffers the blocks of a file before it uploads them.
   */
  @TempDir
  java.nio.file.Path bufferDir;

  @BeforeEach
  void setUp() {
    localS3 = LocalS3.builder().port(-1).buckets(BUCKET).build();
    localS3.start();
    s3 = S3Client.builder()
        .endpointOverride(URI.create(localS3.endpoint()))
        .region(Region.US_EAST_1)
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("admin", "admin")))
        .forcePathStyle(true)
        .build();
  }

  @AfterEach
  void tearDown() throws IOException {
    for (FileSystem fileSystem : fileSystems) {
      fileSystem.close();
    }
    if (s3 != null) {
      s3.close();
    }
    if (localS3 != null) {
      localS3.shutdown();
    }
  }

  /**
   * The directory tree of S3A: directories made, files written and listed, a directory renamed with everything in it,
   * and deleted.
   */
  @Test
  void makesListsRenamesAndDeletesDirectories() throws Exception {
    FileSystem fs = fileSystem(configuration());
    assertTrue(fs instanceof S3AFileSystem, fs.getClass().getName());

    Path table = new Path("s3a://" + BUCKET + "/warehouse/events");
    assertTrue(fs.mkdirs(new Path(table, "dt=2026-01-01")));
    assertTrue(fs.mkdirs(new Path(table, "dt=2026-01-02")));
    assertTrue(fs.getFileStatus(new Path(table, "dt=2026-01-01")).isDirectory(), "An empty directory is a marker.");
    assertTrue(keys().contains("warehouse/events/dt=2026-01-01/"), "The marker of an empty directory: " + keys());

    write(fs, new Path(table, "dt=2026-01-01/part-0.csv"), "id,name\n1,a\n");
    write(fs, new Path(table, "dt=2026-01-01/part-1.csv"), "id,name\n2,b\n");
    write(fs, new Path(table, "dt=2026-01-02/part-0.csv"), "id,name\n3,c\n");
    assertEquals("id,name\n2,b\n", read(fs, new Path(table, "dt=2026-01-01/part-1.csv")));
    // S3A 3.4 and later keep the marker of a directory that holds files, which a listing must fold into the directory.
    assertTrue(keys().contains("warehouse/events/dt=2026-01-01/"), "The marker is kept: " + keys());

    FileStatus[] partitions = fs.listStatus(table);
    assertEquals(List.of("dt=2026-01-01", "dt=2026-01-02"), names(partitions));
    assertTrue(Arrays.stream(partitions).allMatch(FileStatus::isDirectory));
    FileStatus[] files = fs.listStatus(new Path(table, "dt=2026-01-01"));
    assertEquals(List.of("part-0.csv", "part-1.csv"), names(files));
    assertEquals(12, files[0].getLen());

    List<String> recursive = new ArrayList<>();
    RemoteIterator<LocatedFileStatus> iterator = fs.listFiles(table, true);
    while (iterator.hasNext()) {
      recursive.add(iterator.next().getPath().toUri().getPath());
    }
    recursive.sort(null);
    assertEquals(List.of("/warehouse/events/dt=2026-01-01/part-0.csv", "/warehouse/events/dt=2026-01-01/part-1.csv",
        "/warehouse/events/dt=2026-01-02/part-0.csv"), recursive);
    assertEquals(3, fs.getContentSummary(table).getFileCount());

    // A file renamed within its directory, and then the whole table.
    assertTrue(fs.rename(new Path(table, "dt=2026-01-02/part-0.csv"), new Path(table, "dt=2026-01-02/part-9.csv")));
    assertFalse(fs.exists(new Path(table, "dt=2026-01-02/part-0.csv")));
    Path renamed = new Path("s3a://" + BUCKET + "/warehouse/events_v2");
    assertTrue(fs.rename(table, renamed));
    assertFalse(fs.exists(table));
    assertEquals("id,name\n3,c\n", read(fs, new Path(renamed, "dt=2026-01-02/part-9.csv")));
    assertEquals(3, fs.getContentSummary(renamed).getFileCount());
    assertFalse(keys().stream().anyMatch(key -> key.startsWith("warehouse/events/")), "Sources are gone: " + keys());

    // A file is never renamed over another one.
    write(fs, new Path(renamed, "other.csv"), "x");
    assertThrows(FileAlreadyExistsException.class,
        () -> fs.rename(new Path(renamed, "other.csv"), new Path(renamed, "dt=2026-01-01/part-0.csv")));

    assertThrows(PathIOException.class, () -> fs.delete(renamed, false), "A directory that isn't empty.");
    assertTrue(fs.delete(new Path(renamed, "dt=2026-01-01/part-0.csv"), false));
    assertTrue(fs.delete(renamed, true));
    assertFalse(fs.exists(renamed));
    assertThrows(FileNotFoundException.class, () -> fs.listStatus(renamed));
    // The parent directory is kept, as a marker, so that it survives its last child.
    assertTrue(fs.getFileStatus(new Path("s3a://" + BUCKET + "/warehouse")).isDirectory());
  }

  /**
   * A file larger than a part is a multipart upload, read back with ranged reads and a seek, and renamed with a
   * multipart copy.
   */
  @Test
  void writesSeeksAndRenamesALargeFile() throws Exception {
    Configuration conf = configuration();
    conf.set("fs.s3a.multipart.size", "5M");
    conf.set("fs.s3a.multipart.threshold", "5M");
    FileSystem fs = fileSystem(conf);

    byte[] content = new byte[12 * MIB + 17];
    new Random(42).nextBytes(content);
    Path file = new Path("s3a://" + BUCKET + "/big/data.bin");
    try (FSDataOutputStream out = fs.create(file)) {
      out.write(content);
    }
    assertEquals(content.length, fs.getFileStatus(file).getLen());
    assertTrue(((EtagSource) fs.getFileStatus(file)).getEtag().endsWith("-3\""),
        "Written in three parts: " + ((EtagSource) fs.getFileStatus(file)).getEtag());

    try (FSDataInputStream in = fs.open(file)) {
      byte[] tail = new byte[1024];
      in.readFully(content.length - tail.length, tail);
      assertArrayEquals(Arrays.copyOfRange(content, content.length - tail.length, content.length), tail);
      in.seek(7 * MIB);
      byte[] middle = new byte[MIB];
      in.readFully(middle);
      assertArrayEquals(Arrays.copyOfRange(content, 7 * MIB, 8 * MIB), middle);
    }

    Path moved = new Path("s3a://" + BUCKET + "/big/moved.bin");
    assertTrue(fs.rename(file, moved));
    assertFalse(fs.exists(file));
    try (FSDataInputStream in = fs.open(moved)) {
      assertArrayEquals(content, in.readAllBytes());
    }
  }

  /**
   * The magic committer: the files of a task are multipart uploads that no reader sees until the job commits, a task
   * that is aborted leaves nothing behind, and the job writes {@code _SUCCESS}.
   */
  @Test
  void commitsTheOutputOfAJobWithTheMagicCommitter() throws Exception {
    Configuration conf = configuration();
    conf.set(CommitConstants.FS_S3A_COMMITTER_NAME, CommitConstants.COMMITTER_NAME_MAGIC);
    conf.setBoolean(CommitConstants.MAGIC_COMMITTER_ENABLED, true);
    FileSystem fs = fileSystem(conf);
    Path output = new Path("s3a://" + BUCKET + "/output/job");
    assertTrue(fs.hasPathCapability(output, CommitConstants.STORE_CAPABILITY_MAGIC_COMMITTER));

    JobID jobId = new JobID("20260924", 1);
    JobContext job = new JobContextImpl(conf, jobId);
    TaskAttemptContext committed = task(conf, jobId, 0);
    TaskAttemptContext aborted = task(conf, jobId, 1);

    MagicS3GuardCommitter jobCommitter = new MagicS3GuardCommitter(output, committed);
    jobCommitter.setupJob(job);

    MagicS3GuardCommitter first = new MagicS3GuardCommitter(output, committed);
    first.setupTask(committed);
    Path firstWork = first.getWorkPath();
    assertTrue(firstWork.toString().contains(CommitConstants.MAGIC_PATH_PREFIX), firstWork.toString());
    write(fs, new Path(firstWork, "part-00000.csv"), "id\n1\n");

    MagicS3GuardCommitter second = new MagicS3GuardCommitter(output, aborted);
    second.setupTask(aborted);
    write(fs, new Path(second.getWorkPath(), "part-00001.csv"), "id\n2\n");

    // Written, but not visible: each file is a multipart upload that isn't complete yet.
    assertFalse(fs.exists(new Path(output, "part-00000.csv")));
    assertEquals(2, pendingUploads(), "One pending upload per file.");

    assertTrue(first.needsTaskCommit(committed));
    first.commitTask(committed);
    second.abortTask(aborted);
    assertEquals(1, pendingUploads(), "The aborted task leaves no upload behind.");
    assertFalse(fs.exists(new Path(output, "part-00000.csv")), "Nothing is visible before the job commits.");

    jobCommitter.commitJob(job);
    assertEquals(0, pendingUploads());
    assertEquals("id\n1\n", read(fs, new Path(output, "part-00000.csv")));
    assertFalse(fs.exists(new Path(output, "part-00001.csv")), "The output of the aborted task was never written.");
    String success = read(fs, new Path(output, "_SUCCESS"));
    assertTrue(success.contains("\"committer\" : \"magic\"") || success.contains("\"committer\":\"magic\""), success);
    assertTrue(success.contains("part-00000.csv"), success);
    assertFalse(keys().stream().anyMatch(key -> key.contains(CommitConstants.MAGIC_PATH_PREFIX)),
        "The job cleans up its magic directory: " + keys());
    assertEquals(List.of("_SUCCESS", "part-00000.csv"), names(fs.listStatus(output)));
  }

  /**
   * A conditional create: a file created only if it doesn't exist, which the second of two writers fails to do, and
   * an overwrite only of the version that was read, which fails once another writer replaced it.
   */
  @Test
  void createsAFileOnlyIfItDoesNotExistOrIsTheVersionThatWasRead() throws Exception {
    FileSystem fs = fileSystem(configuration());
    Path commit = new Path("s3a://" + BUCKET + "/delta/_delta_log/00000000000000000001.json");

    createIfAbsent(fs, commit, "{\"commitInfo\":{\"writer\":1}}");
    assertEquals("{\"commitInfo\":{\"writer\":1}}", read(fs, commit));

    IOException conflict = assertThrows(IOException.class,
        () -> createIfAbsent(fs, commit, "{\"commitInfo\":{\"writer\":2}}"));
    assertTrue(conflict instanceof FileAlreadyExistsException || conflict instanceof RemoteFileChangedException,
        "The second writer loses: " + conflict);
    assertEquals("{\"commitInfo\":{\"writer\":1}}", read(fs, commit), "The first commit is kept.");

    // An overwrite of the version that was read, and then of that version again, which is stale by then.
    String etag = ((EtagSource) fs.getFileStatus(commit)).getEtag();
    assertNotNull(etag);
    overwriteIfMatch(fs, commit, etag, "{\"v\":2}");
    assertEquals("{\"v\":2}", read(fs, commit));

    IOException stale = assertThrows(IOException.class, () -> overwriteIfMatch(fs, commit, etag, "{\"v\":3}"));
    assertTrue(stale instanceof RemoteFileChangedException || stale instanceof FileAlreadyExistsException,
        "The version that was read was replaced: " + stale);
    assertEquals("{\"v\":2}", read(fs, commit));
  }

  /**
   * Create a file with {@code If-None-Match: *}, the way Delta Lake and Paimon commit a version.
   */
  private static void createIfAbsent(FileSystem fs, Path path, String content) throws IOException {
    FSDataOutputStreamBuilder<?, ?> builder = fs.createFile(path).overwrite(true);
    builder.must(Options.CreateFileOptionKeys.FS_OPTION_CREATE_CONDITIONAL_OVERWRITE, true);
    write(builder, content);
  }

  /**
   * Overwrite a file with {@code If-Match: <etag>}, which only replaces the version that was read.
   */
  private static void overwriteIfMatch(FileSystem fs, Path path, String etag, String content) throws IOException {
    FSDataOutputStreamBuilder<?, ?> builder = fs.createFile(path).overwrite(true);
    builder.must(Options.CreateFileOptionKeys.FS_OPTION_CREATE_CONDITIONAL_OVERWRITE_ETAG, etag);
    write(builder, content);
  }

  private static void write(FSDataOutputStreamBuilder<?, ?> builder, String content) throws IOException {
    try (FSDataOutputStream out = builder.build()) {
      out.write(content.getBytes(StandardCharsets.UTF_8));
    }
  }

  /**
   * The configuration of S3A for this LocalS3, with a file system of its own per test rather than the cached one.
   */
  private Configuration configuration() {
    Configuration conf = new Configuration();
    conf.set("hadoop.tmp.dir", bufferDir.toString());
    conf.set("fs.s3a.impl", S3AFileSystem.class.getName());
    conf.set("fs.s3a.endpoint", localS3.endpoint());
    conf.set("fs.s3a.endpoint.region", "us-east-1");
    conf.setBoolean("fs.s3a.path.style.access", true);
    conf.setBoolean("fs.s3a.connection.ssl.enabled", false);
    conf.set("fs.s3a.aws.credentials.provider", "org.apache.hadoop.fs.s3a.SimpleAWSCredentialsProvider");
    conf.set("fs.s3a.access.key", "admin");
    conf.set("fs.s3a.secret.key", "admin");
    conf.setBoolean("fs.s3a.impl.disable.cache", true);
    return conf;
  }

  private FileSystem fileSystem(Configuration conf) throws IOException {
    FileSystem fs = FileSystem.get(URI.create("s3a://" + BUCKET + "/"), conf);
    fileSystems.add(fs);
    return fs;
  }

  private static TaskAttemptContext task(Configuration conf, JobID jobId, int task) {
    return new TaskAttemptContextImpl(new Configuration(conf),
        new TaskAttemptID(new TaskID(jobId, TaskType.MAP, task), 0));
  }

  private static void write(FileSystem fs, Path path, String content) throws IOException {
    try (FSDataOutputStream out = fs.create(path, true)) {
      out.write(content.getBytes(StandardCharsets.UTF_8));
    }
  }

  private static String read(FileSystem fs, Path path) throws IOException {
    try (FSDataInputStream in = fs.open(path)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static List<String> names(FileStatus[] statuses) {
    return Arrays.stream(statuses).map(status -> status.getPath().getName()).sorted().toList();
  }

  private List<String> keys() {
    return s3.listObjectsV2Paginator(request -> request.bucket(BUCKET)).contents().stream()
        .map(S3Object::key)
        .toList();
  }

  private int pendingUploads() {
    return s3.listMultipartUploads(request -> request.bucket(BUCKET)).uploads().size();
  }

}
