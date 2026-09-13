package com.robothy.s3.core.service.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import com.robothy.s3.core.assertions.UploadAssertions;
import com.robothy.s3.core.model.internal.LocalS3Metadata;
import com.robothy.s3.core.model.internal.ObjectPartMetadata;
import com.robothy.s3.core.model.internal.UploadPartMetadata;
import com.robothy.s3.core.model.internal.VersionedObjectMetadata;
import com.robothy.s3.core.model.request.CompleteMultipartUploadPartOption;
import com.robothy.s3.core.model.request.CopyObjectOptions;
import com.robothy.s3.core.model.request.CreateMultipartUploadOptions;
import com.robothy.s3.core.model.request.GetObjectOptions;
import com.robothy.s3.core.model.request.PutObjectOptions;
import com.robothy.s3.core.model.request.Range;
import com.robothy.s3.core.model.request.UploadPartOptions;
import com.robothy.s3.core.service.ObjectService;
import com.robothy.s3.core.storage.CompositeInputStream;
import com.robothy.s3.core.storage.Storage;
import com.robothy.s3.core.util.S3ObjectUtils;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The object of a completed multipart upload references the content of its parts instead of a copy of it.
 */
class MultipartObjectContentTest {

  private static final String BUCKET = "my-bucket";

  private static final String KEY = "a.txt";

  @Test
  void completesAnUploadWithoutStoringOrReadingContent() throws IOException {
    CountingStorage storage = new CountingStorage();
    InMemoryLocalS3Manager manager = new InMemoryLocalS3Manager(new LocalS3Metadata(), storage);
    ObjectService objectService = manager.objectService();
    manager.bucketService().createBucket(BUCKET);
    String uploadId = upload(objectService, "Hello", "Local", "S3!");
    assertEquals(3, storage.puts.get());

    String etag = objectService.completeMultipartUpload(BUCKET, KEY, uploadId, completeParts(1, 2, 3)).getEtag();

    assertEquals(3, storage.puts.get(), "No content is stored.");
    assertEquals(0, storage.reads.get(), "No content is read.");
    assertEquals(S3ObjectUtils.compositeEtag(List.of(DigestUtils.md5("Hello"), DigestUtils.md5("Local"),
        DigestUtils.md5("S3!"))), etag);
    assertEquals("HelloLocalS3!", read(objectService, null));
    assertEquals(13, objectService.headObject(BUCKET, KEY, GetObjectOptions.builder().build()).getSize());
  }

  @Test
  void keepsNoCopyOfThePartsOnTheDisk(@TempDir Path dataPath) throws IOException {
    LocalS3Manager manager = LocalS3Manager.createFileSystemS3Manager(dataPath);
    ObjectService objectService = manager.objectService();
    manager.bucketService().createBucket(BUCKET);
    String uploadId = upload(objectService, "Hello", "Local", "S3!");
    Path storageDirectory = dataPath.resolve(LocalS3Manager.STORAGE_DIRECTORY);
    assertEquals(3, countFiles(storageDirectory));

    objectService.completeMultipartUpload(BUCKET, KEY, uploadId, completeParts(1, 2, 3));

    assertEquals(3, countFiles(storageDirectory), "The object is its parts.");
    VersionedObjectMetadata version = latestVersion(objectService);
    assertNull(version.getFileId());
    assertEquals(List.of(5L, 5L, 3L), version.getParts().orElseThrow().stream().map(ObjectPartMetadata::getSize).toList());

    // The object is loaded from the disk like any other one.
    LocalS3Manager reloaded = LocalS3Manager.createFileSystemS3Manager(dataPath);
    assertEquals("HelloLocalS3!", read(reloaded.objectService(), null));
    assertEquals("loLocalS3", read(reloaded.objectService(), Range.of(3, 11)));
  }

  @Test
  void readsRangesAcrossParts() throws IOException {
    for (LocalS3Manager manager : managers()) {
      ObjectService objectService = manager.objectService();
      manager.bucketService().createBucket(BUCKET);
      objectService.completeMultipartUpload(BUCKET, KEY, upload(objectService, "Hello", "Local", "S3!"),
          completeParts(1, 2, 3));

      assertEquals("loLocalS3", read(objectService, Range.of(3, 11)));
      assertEquals("Local", read(objectService, Range.of(5, 9)), "A range that is exactly a part.");
      assertEquals("oca", read(objectService, Range.of(6, 8)), "A range within a part.");
      assertEquals("oL", read(objectService, Range.of(4, 5)), "A range across a part boundary.");
      assertEquals("S3!", read(objectService, Range.last(3)));
      assertEquals("3!", read(objectService, Range.from(11)));
      assertEquals("H", read(objectService, Range.of(0, 0)));
    }
  }

  @Test
  void skipsEmptyParts() throws IOException {
    for (LocalS3Manager manager : managers()) {
      ObjectService objectService = manager.objectService();
      manager.bucketService().createBucket(BUCKET);
      objectService.completeMultipartUpload(BUCKET, KEY, upload(objectService, "Hello", "", "S3"),
          completeParts(1, 2, 3));

      assertEquals("HelloS3", read(objectService, null));
      assertEquals("oS", read(objectService, Range.of(4, 5)));
    }
  }

  @Test
  void deletesTheContentOfThePartsThatDontCompleteTheUpload() {
    for (LocalS3Manager manager : managers()) {
      ObjectService objectService = manager.objectService();
      manager.bucketService().createBucket(BUCKET);
      String uploadId = upload(objectService, "Hello", "Local", "S3!");
      List<Long> partFileIds = partFileIds(objectService, uploadId);

      objectService.completeMultipartUpload(BUCKET, KEY, uploadId, completeParts(1, 3));

      assertTrue(objectService.storage().isExist(partFileIds.get(0)));
      assertFalse(objectService.storage().isExist(partFileIds.get(1)));
      assertTrue(objectService.storage().isExist(partFileIds.get(2)));
    }
  }

  @Test
  void deletesThePartsWithTheObject() {
    for (LocalS3Manager manager : managers()) {
      ObjectService objectService = manager.objectService();
      manager.bucketService().createBucket(BUCKET);

      // Overwritten in a bucket without versioning.
      String uploadId = upload(objectService, "Hello", "World");
      List<Long> overwritten = partFileIds(objectService, uploadId);
      objectService.completeMultipartUpload(BUCKET, KEY, uploadId, completeParts(1, 2));
      objectService.putObject(BUCKET, KEY, content("Replaced"));
      overwritten.forEach(id -> assertFalse(objectService.storage().isExist(id)));

      // Deleted.
      uploadId = upload(objectService, "Hello", "World");
      List<Long> deleted = partFileIds(objectService, uploadId);
      objectService.completeMultipartUpload(BUCKET, KEY, uploadId, completeParts(1, 2));
      deleted.forEach(id -> assertTrue(objectService.storage().isExist(id)));
      objectService.deleteObject(BUCKET, KEY);
      deleted.forEach(id -> assertFalse(objectService.storage().isExist(id)));

      // A version deleted from a bucket with versioning.
      manager.bucketService().setVersioningEnabled(BUCKET, true);
      uploadId = upload(objectService, "Hello", "World");
      List<Long> versioned = partFileIds(objectService, uploadId);
      String versionId = objectService.completeMultipartUpload(BUCKET, KEY, uploadId, completeParts(1, 2))
          .getVersionId();
      objectService.deleteObject(BUCKET, KEY);
      // A delete marker keeps the version.
      versioned.forEach(id -> assertTrue(objectService.storage().isExist(id)));
      objectService.deleteObject(BUCKET, KEY, versionId);
      versioned.forEach(id -> assertFalse(objectService.storage().isExist(id)));
    }
  }

  @Test
  void copiesTheContentOfAnObjectStoredInParts() throws IOException {
    for (LocalS3Manager manager : managers()) {
      ObjectService objectService = manager.objectService();
      manager.bucketService().createBucket(BUCKET);
      objectService.completeMultipartUpload(BUCKET, KEY, upload(objectService, "Hello", "World"), completeParts(1, 2));

      String etag = objectService.copyObject(BUCKET, "copied", CopyObjectOptions.builder()
          .sourceBucket(BUCKET).sourceKey(KEY).build()).getEtag();

      assertEquals(DigestUtils.md5Hex("HelloWorld"), etag);
      try (InputStream in = objectService.getObject(BUCKET, "copied", GetObjectOptions.builder().build()).getContent()) {
        assertEquals("HelloWorld", new String(in.readAllBytes(), StandardCharsets.UTF_8));
      }
    }
  }

  /**
   * A part that a LocalS3 before 2.5 stored holds no digest of its content, which is read to compute the entity tag.
   */
  @Test
  void digestsThePartsThatHoldNoDigest() {
    CountingStorage storage = new CountingStorage();
    InMemoryLocalS3Manager manager = new InMemoryLocalS3Manager(new LocalS3Metadata(), storage);
    ObjectService objectService = manager.objectService();
    manager.bucketService().createBucket(BUCKET);
    String uploadId = upload(objectService, "Hello", "World");
    UploadAssertions.assertUploadExists(objectService.localS3Metadata().getBucketMetadata(BUCKET).orElseThrow(),
        KEY, uploadId).getParts().get(1).setContentMd5(null);

    String etag = objectService.completeMultipartUpload(BUCKET, KEY, uploadId, completeParts(1, 2)).getEtag();

    assertEquals(S3ObjectUtils.compositeEtag(List.of(DigestUtils.md5("Hello"), DigestUtils.md5("World"))), etag);
    assertEquals(1, storage.reads.get(), "Only the part without a digest is read.");
  }

  @Test
  void opensThePartsOfARangeAtOnce() throws IOException {
    for (LocalS3Manager manager : managers()) {
      ObjectService objectService = manager.objectService();
      manager.bucketService().createBucket(BUCKET);
      objectService.completeMultipartUpload(BUCKET, KEY, upload(objectService, "Hello", "Local", "S3!"),
          completeParts(1, 2, 3));

      try (InputStream all = objectService.getObject(BUCKET, KEY, GetObjectOptions.builder().build()).getContent()) {
        assertEquals(3, assertInstanceOf(CompositeInputStream.class, all).getStreams().size());
      }
      try (InputStream within = objectService.getObject(BUCKET, KEY, GetObjectOptions.builder()
          .range(Range.of(6, 8)).build()).getContent()) {
        assertFalse(within instanceof CompositeInputStream, "A range within a part is the stream of the part.");
      }
    }
  }

  /**
   * The parts of an object are opened while the object is read locked, so that a read that is still in progress
   * isn't cut short when the object is overwritten, which deletes its parts. Windows refuses to delete an open file.
   */
  @Test
  void readsAnObjectThatIsOverwrittenWhileItIsRead(@TempDir Path dataPath) throws IOException {
    assumeFalse(System.getProperty("os.name").startsWith("Windows"));
    LocalS3Manager manager = LocalS3Manager.createFileSystemS3Manager(dataPath);
    ObjectService objectService = manager.objectService();
    manager.bucketService().createBucket(BUCKET);
    objectService.completeMultipartUpload(BUCKET, KEY, upload(objectService, "Hello", "Local", "S3!"),
        completeParts(1, 2, 3));

    try (InputStream in = objectService.getObject(BUCKET, KEY, GetObjectOptions.builder().build()).getContent()) {
      objectService.putObject(BUCKET, KEY, content("Replaced"));
      assertEquals("HelloLocalS3!", new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }
    assertEquals("Replaced", read(objectService, null));
  }

  private static List<LocalS3Manager> managers() {
    try {
      Path dataPath = Files.createTempDirectory("local-s3");
      dataPath.toFile().deleteOnExit();
      return List.of(LocalS3Manager.createInMemoryS3Manager(), LocalS3Manager.createFileSystemS3Manager(dataPath));
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String upload(ObjectService objectService, String... parts) {
    String uploadId = objectService.createMultipartUpload(BUCKET, KEY,
        CreateMultipartUploadOptions.builder().contentType("plain/text").build());
    for (int i = 0; i < parts.length; i++) {
      byte[] bytes = parts[i].getBytes(StandardCharsets.UTF_8);
      objectService.uploadPart(BUCKET, KEY, uploadId, i + 1, UploadPartOptions.builder()
          .contentLength(bytes.length)
          .data(new ByteArrayInputStream(bytes))
          .build());
    }
    return uploadId;
  }

  private static List<Long> partFileIds(ObjectService objectService, String uploadId) {
    return UploadAssertions.assertUploadExists(objectService.localS3Metadata().getBucketMetadata(BUCKET).orElseThrow(),
            KEY, uploadId).getParts().values().stream()
        .map(UploadPartMetadata::getFileId)
        .toList();
  }

  private static List<CompleteMultipartUploadPartOption> completeParts(int... partNumbers) {
    return IntStream.of(partNumbers)
        .mapToObj(partNumber -> CompleteMultipartUploadPartOption.builder().partNumber(partNumber).build())
        .toList();
  }

  private static VersionedObjectMetadata latestVersion(ObjectService objectService) {
    return objectService.localS3Metadata().getBucketMetadata(BUCKET).orElseThrow()
        .getObjectMetadata(KEY).orElseThrow().getLatest();
  }

  private static String read(ObjectService objectService, Range range) throws IOException {
    try (InputStream in = objectService.getObject(BUCKET, KEY, GetObjectOptions.builder().range(range).build())
        .getContent()) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static PutObjectOptions content(String text) {
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    return PutObjectOptions.builder().content(new ByteArrayInputStream(bytes)).size(bytes.length).build();
  }

  private static long countFiles(Path directory) throws IOException {
    // The object files are spread over subdirectories.
    try (Stream<Path> files = Files.walk(directory)) {
      return files.filter(Files::isRegularFile).count();
    }
  }

  /**
   * An in-memory storage that counts the content that is stored and read.
   */
  private static final class CountingStorage implements Storage {

    private final Storage delegate = Storage.createInMemory();

    private final AtomicInteger puts = new AtomicInteger();

    private final AtomicInteger reads = new AtomicInteger();

    @Override
    public Long put(Long id, byte[] data) {
      puts.incrementAndGet();
      return delegate.put(id, data);
    }

    @Override
    public Long put(Long id, InputStream data) {
      puts.incrementAndGet();
      return delegate.put(id, data);
    }

    @Override
    public byte[] getBytes(Long id) {
      reads.incrementAndGet();
      return delegate.getBytes(id);
    }

    @Override
    public InputStream getInputStream(Long id) {
      reads.incrementAndGet();
      return delegate.getInputStream(id);
    }

    @Override
    public InputStream getInputStream(Long id, long position, long length) {
      reads.incrementAndGet();
      return delegate.getInputStream(id, position, length);
    }

    @Override
    public long size(Long id) {
      return delegate.size(id);
    }

    @Override
    public Long delete(Long id) {
      return delegate.delete(id);
    }

    @Override
    public boolean isExist(Long id) {
      return delegate.isExist(id);
    }
  }

}
