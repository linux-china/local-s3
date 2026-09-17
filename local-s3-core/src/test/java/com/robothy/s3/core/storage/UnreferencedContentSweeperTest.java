package com.robothy.s3.core.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.robothy.s3.core.model.internal.UploadPartMetadata;
import com.robothy.s3.core.model.request.CompleteMultipartUploadPartOption;
import com.robothy.s3.core.model.request.CreateMultipartUploadOptions;
import com.robothy.s3.core.model.request.GetObjectOptions;
import com.robothy.s3.core.model.request.PutObjectOptions;
import com.robothy.s3.core.model.request.UploadPartOptions;
import com.robothy.s3.core.service.ObjectService;
import com.robothy.s3.core.service.manager.LocalS3Manager;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.h2.mvstore.MVMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A persistent store that is opened by its first holder deletes the content files that its metadata doesn't reference
 * in the background, and keeps everything it can't be sure of.
 */
class UnreferencedContentSweeperTest {

  private static final String BUCKET = "my-bucket";

  private static final long ORPHAN_ID = 123_456_789L;

  private static final Instant LONG_AGO = Instant.now().minus(Duration.ofHours(1));

  @Test
  void deletesTheContentFilesThatNoMetadataReferences(@TempDir Path dataPath) throws Exception {
    withManager(dataPath, manager -> {
      ObjectService objectService = manager.objectService();
      manager.bucketService().createBucket(BUCKET);
      objectService.putObject(BUCKET, "put.txt", content("Hello"));
      objectService.completeMultipartUpload(BUCKET, "completed.txt", upload(objectService, "completed.txt", "Hel", "lo"),
          List.of(part(1), part(2)));
      upload(objectService, "in-progress.txt", "Hello");
    });
    Path storage = dataPath.resolve(LocalS3Manager.STORAGE_DIRECTORY);
    long referenced = countContentFiles(storage);
    assertEquals(4, referenced, "The object, the two parts of the completed upload and the part in progress.");
    ageContentFiles(storage);

    Path orphan = contentFile(storage, ORPHAN_ID, LONG_AGO);
    Path recent = contentFile(storage, ORPHAN_ID + 1, Instant.now().minus(Duration.ofSeconds(10)));
    Path flat = Files.writeString(storage.resolve(String.valueOf(ORPHAN_ID + 2)), "flat layout");
    Path requestBody = Files.writeString(Files.createDirectories(storage.resolve(".request-bodies"))
        .resolve("locals3-body-1.tmp"), "request body");

    try (LocalS3Store store = LocalS3Store.persistent(dataPath)) {
      UnreferencedContentSweeper.Result result = store.sweeper().await();
      assertEquals(1, result.files());
      assertEquals("orphan".length(), result.bytes());
    }
    assertFalse(Files.exists(orphan));
    assertTrue(Files.exists(recent), "A file modified less than a minute before the store was opened is kept.");
    assertTrue(Files.exists(flat), "A file of the flat layout is kept.");
    assertTrue(Files.exists(requestBody));
    assertEquals(referenced + 2, countContentFiles(storage), "The referenced files, the recent one and the flat one.");
    withManager(dataPath, manager -> {
      assertEquals("Hello", read(manager.objectService(), "put.txt"));
      assertEquals("Hello", read(manager.objectService(), "completed.txt"));
    });
  }

  /**
   * The sweep reads the metadata as it was when the store was opened, so the content that a change moves from one map
   * to another meanwhile, e.g. the parts of an upload that is completed, is still found referenced.
   */
  @Test
  void readsTheMetadataAsItWasWhenTheStoreWasOpened(@TempDir Path dataPath) {
    withManager(dataPath, manager -> {
      ObjectService objectService = manager.objectService();
      manager.bucketService().createBucket(BUCKET);
      objectService.putObject(BUCKET, "put.txt", content("Hello"));
      String uploadId = upload(objectService, "completed.txt", "Hel", "lo");
      long[] partIds = manager.objectService().localS3Metadata().getBucketMetadata(BUCKET).orElseThrow()
          .getUploads().get("completed.txt").get(uploadId).getParts().values().stream()
          .mapToLong(UploadPartMetadata::getFileId).toArray();
      long objectId = manager.objectService().localS3Metadata().getBucketMetadata(BUCKET).orElseThrow()
          .getObjectMetadata("put.txt").orElseThrow().getLatest().getFileId();

      try (LocalS3Store store = LocalS3Store.persistent(dataPath)) {
        var usage = store.store().registerVersionUsage();
        try {
          List<MVMap<String, String>> snapshot = MVStoreBucketMetadataStore.contentReferencingMaps(store.store());
          objectService.completeMultipartUpload(BUCKET, "completed.txt", uploadId, List.of(part(1), part(2)));
          objectService.deleteObject(BUCKET, "put.txt");

          long[] expected = LongStream.concat(LongStream.of(objectId), LongStream.of(partIds)).sorted().toArray();
          assertArrayEquals(expected, MVStoreBucketMetadataStore.referencedContentIds(snapshot, () -> false));
        } finally {
          store.store().deregisterVersionUsage(usage);
        }
      }
    });
  }

  @Test
  void keepsTheContentOfADirectoryOfALocalS3Before25(@TempDir Path dataPath) throws Exception {
    createObject(dataPath);
    Path orphan = contentFile(dataPath.resolve(LocalS3Manager.STORAGE_DIRECTORY), ORPHAN_ID, LONG_AGO);
    Files.writeString(dataPath.resolve(BUCKET + ".bucket.meta"), "{}");

    assertSweptNothing(dataPath);
    assertTrue(Files.exists(orphan));
  }

  @Test
  void keepsTheContentOfADirectoryWhoseMetadataReferencesNothing(@TempDir Path dataPath) throws Exception {
    withManager(dataPath, manager -> manager.bucketService().createBucket(BUCKET));
    Path orphan = contentFile(dataPath.resolve(LocalS3Manager.STORAGE_DIRECTORY), ORPHAN_ID, LONG_AGO);

    assertSweptNothing(dataPath);
    assertTrue(Files.exists(orphan), "A store that references nothing may have lost its metadata.");
  }

  /**
   * The temporary files of the writes of the storage and of the request bodies are never referenced, so the ones that
   * were last modified long enough before are deleted, even from a store whose metadata references nothing.
   */
  @Test
  void deletesTheTemporaryFilesThatAProcessLeftBehind(@TempDir Path dataPath) throws Exception {
    withManager(dataPath, manager -> manager.bucketService().createBucket(BUCKET));
    Path storage = dataPath.resolve(LocalS3Manager.STORAGE_DIRECTORY);
    Path requestBodies = Files.createDirectories(storage.resolve(LocalS3Manager.REQUEST_BODY_DIRECTORY));
    Path oldWrite = file(storage.resolve(".42.0000.tmp"), LONG_AGO);
    Path recentWrite = file(storage.resolve(".43.0000.tmp"), Instant.now().minus(Duration.ofSeconds(10)));
    Path oldBody = file(requestBodies.resolve("locals3-body-1.tmp"), LONG_AGO);
    Path recentBody = file(requestBodies.resolve("locals3-body-2.tmp"), Instant.now().minus(Duration.ofSeconds(10)));
    Path unrelated = file(requestBodies.resolve("notes.txt"), LONG_AGO);
    Path orphan = contentFile(storage, ORPHAN_ID, LONG_AGO);

    try (LocalS3Store store = LocalS3Store.persistent(dataPath)) {
      assertEquals(new UnreferencedContentSweeper.Result(0, 0, 2), store.sweeper().await());
    }
    assertFalse(Files.exists(oldWrite));
    assertFalse(Files.exists(oldBody));
    assertTrue(Files.exists(recentWrite), "A write that is less than a minute old may still be in progress.");
    assertTrue(Files.exists(recentBody));
    assertTrue(Files.exists(unrelated), "Only the temporary files are deleted.");
    assertTrue(Files.exists(orphan), "A store that references nothing keeps its content files.");
  }

  /**
   * A holder that shares the store of a directory that another holder has open starts no sweep: the other holder may
   * be storing content that its metadata doesn't reference yet. Neither does a store that was just created.
   */
  @Test
  void sweepsOnlyWhenTheFirstHolderOpensAnExistingStore(@TempDir Path dataPath) throws Exception {
    try (LocalS3Store created = LocalS3Store.persistent(dataPath)) {
      assertNull(created.sweeper());
    }
    createObject(dataPath);
    Path storage = dataPath.resolve(LocalS3Manager.STORAGE_DIRECTORY);
    contentFile(storage, ORPHAN_ID, LONG_AGO);
    try (LocalS3Store first = LocalS3Store.persistent(dataPath)) {
      assertNotNull(first.sweeper());
      assertEquals(1, first.sweeper().await().files());

      Path inFlight = contentFile(storage, ORPHAN_ID + 1, LONG_AGO);
      Path bodyInFlight = file(Files.createDirectories(storage.resolve(LocalS3Manager.REQUEST_BODY_DIRECTORY))
          .resolve("locals3-body-1.tmp"), LONG_AGO);
      try (LocalS3Store second = LocalS3Store.persistent(dataPath)) {
        assertSame(first, second);
        assertEquals(1, second.sweeper().await().files(), "Sharing the store starts no other sweep.");
      }
      assertTrue(Files.exists(inFlight));
      assertTrue(Files.exists(bodyInFlight));
    }
  }

  private static void assertSweptNothing(Path dataPath) throws InterruptedException {
    try (LocalS3Store store = LocalS3Store.persistent(dataPath)) {
      assertEquals(UnreferencedContentSweeper.Result.NONE, store.sweeper().await());
    }
  }

  private static void withManager(Path dataPath, Consumer<LocalS3Manager> action) {
    LocalS3Manager manager = LocalS3Manager.createFileSystemS3Manager(dataPath);
    try {
      action.accept(manager);
    } finally {
      manager.close();
    }
  }

  private static void createObject(Path dataPath) throws IOException {
    withManager(dataPath, manager -> {
      manager.bucketService().createBucket(BUCKET);
      manager.objectService().putObject(BUCKET, "put.txt", content("Hello"));
    });
    ageContentFiles(dataPath.resolve(LocalS3Manager.STORAGE_DIRECTORY));
  }

  private static Path file(Path file, Instant lastModified) throws IOException {
    Files.writeString(file, "partial");
    Files.setLastModifiedTime(file, FileTime.from(lastModified));
    return file;
  }

  private static Path contentFile(Path storage, long id, Instant lastModified) throws IOException {
    Path file = ShardedFileLayout.shardedPath(storage, id);
    Files.createDirectories(file.getParent());
    Files.writeString(file, "orphan");
    Files.setLastModifiedTime(file, FileTime.from(lastModified));
    return file;
  }

  /**
   * Make the content files look written long ago, so that the sweep considers them.
   */
  private static void ageContentFiles(Path storage) throws IOException {
    try (Stream<Path> files = Files.walk(storage)) {
      for (Path file : files.filter(ShardedFileLayout::isIdFile).toList()) {
        Files.setLastModifiedTime(file, FileTime.from(LONG_AGO));
      }
    }
  }

  private static long countContentFiles(Path storage) throws IOException {
    try (Stream<Path> files = Files.walk(storage)) {
      return files.filter(ShardedFileLayout::isIdFile).count();
    }
  }

  private static PutObjectOptions content(String content) {
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    return PutObjectOptions.builder().content(new ByteArrayInputStream(bytes)).size(bytes.length).build();
  }

  private static String upload(ObjectService objectService, String key, String... parts) {
    String uploadId = objectService.createMultipartUpload(BUCKET, key, CreateMultipartUploadOptions.builder().build());
    for (int i = 0; i < parts.length; i++) {
      byte[] bytes = parts[i].getBytes(StandardCharsets.UTF_8);
      objectService.uploadPart(BUCKET, key, uploadId, i + 1, UploadPartOptions.builder()
          .contentLength(bytes.length)
          .data(new ByteArrayInputStream(bytes))
          .build());
    }
    return uploadId;
  }

  private static CompleteMultipartUploadPartOption part(int partNumber) {
    return CompleteMultipartUploadPartOption.builder().partNumber(partNumber).build();
  }

  private static String read(ObjectService objectService, String key) {
    try (InputStream in = objectService.getObject(BUCKET, key, GetObjectOptions.builder().build()).getContent()) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

}
