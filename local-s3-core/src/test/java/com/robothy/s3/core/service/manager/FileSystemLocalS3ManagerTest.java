package com.robothy.s3.core.service.manager;

import com.robothy.s3.core.exception.BucketTaggingNotExistException;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import com.robothy.s3.core.exception.BucketNotExistException;
import com.robothy.s3.core.exception.UploadNotExistException;
import com.robothy.s3.core.model.request.CreateMultipartUploadOptions;
import com.robothy.s3.core.model.request.GetObjectOptions;
import com.robothy.s3.core.model.request.PutObjectOptions;
import com.robothy.s3.core.model.request.UploadPartOptions;
import com.robothy.s3.core.service.BucketService;
import com.robothy.s3.core.service.ObjectService;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FileSystemLocalS3ManagerTest {

  private static final String BUCKET = "my-bucket";

  private static final String KEY = "a.txt";

  private Path dataPath;

  @BeforeEach
  void setUp() throws IOException {
    dataPath = Files.createTempDirectory("local-s3");
  }

  @AfterEach
  void tearDown() throws IOException {
    dataPath.toFile().setWritable(true);
    FileUtils.deleteDirectory(dataPath.toFile());
  }

  @Test
  void cachesServices() {
    LocalS3Manager manager = LocalS3Manager.createFileSystemS3Manager(dataPath);

    assertSame(manager.bucketService(), manager.bucketService());
    assertSame(manager.objectService(), manager.objectService());
  }

  /**
   * The tagging of a bucket is part of the metadata of the bucket, which a restarted service loads.
   */
  @Test
  void persistsTheTaggingOfABucket() {
    LocalS3Manager manager = LocalS3Manager.createFileSystemS3Manager(dataPath);
    manager.bucketService().createBucket("tagged");
    manager.bucketService().createBucket("untagged");
    manager.bucketService().putTagging("tagged", List.of(Map.of("team", "s3")));
    manager.bucketService().putTagging("untagged", List.of(Map.of("team", "s3")));
    manager.bucketService().deleteTagging("untagged");

    LocalS3Manager restarted = LocalS3Manager.createFileSystemS3Manager(dataPath);
    assertEquals(List.of(Map.of("team", "s3")),
        List.copyOf(restarted.bucketService().getTagging("tagged")));
    assertThrows(BucketTaggingNotExistException.class,
        () -> restarted.bucketService().getTagging("untagged"));
  }

  /**
   * Overwriting an object of a non-versioned bucket deletes the content of the version it replaces, and only once the
   * metadata that no longer references it is persisted, so that the metadata on the disk and the object files it
   * references stay consistent. A change whose persistence fails is rolled back instead, which
   * {@code DefaultBucketGuardTest.rollsBackAChangeWhosePersistenceFails} covers.
   */
  @Test
  void overwritingAnObjectKeepsMemoryStorageAndDiskConsistent() throws IOException {
    LocalS3Manager manager = LocalS3Manager.createFileSystemS3Manager(dataPath);
    ObjectService objectService = manager.objectService();
    manager.bucketService().createBucket(BUCKET);
    putObject(objectService, "v1");
    assertEquals(1, storedObjects().size());

    putObject(objectService, "v2");
    assertEquals(1, storedObjects().size(), "v1 is deleted once v2 is persisted.");
    assertEquals("v2", getObject(objectService));
    assertEquals("v2", getObject(LocalS3Manager.createFileSystemS3Manager(dataPath).objectService()));
  }

  @Test
  void discardsStoredContentWhenBucketIsDeletedWhileStoring() throws IOException {
    LocalS3Manager manager = LocalS3Manager.createFileSystemS3Manager(dataPath);
    BucketService bucketService = manager.bucketService();
    bucketService.createBucket(BUCKET);

    // The bucket isn't locked while the content is stored, so it can be deleted meanwhile.
    InputStream content = onFirstRead("v1", () -> bucketService.deleteBucket(BUCKET));
    assertThrows(BucketNotExistException.class, () -> manager.objectService().putObject(BUCKET, KEY,
        PutObjectOptions.builder().content(content).contentType("text/plain").size(2L).build()));

    assertEquals(Set.of(), storedObjects());
  }

  @Test
  void discardsStoredPartWhenUploadIsAbortedWhileStoring() throws IOException {
    LocalS3Manager manager = LocalS3Manager.createFileSystemS3Manager(dataPath);
    ObjectService objectService = manager.objectService();
    manager.bucketService().createBucket(BUCKET);
    String uploadId = objectService.createMultipartUpload(BUCKET, KEY,
        CreateMultipartUploadOptions.builder().contentType("text/plain").build());

    InputStream data = onFirstRead("part", () -> objectService.abortMultipartUpload(BUCKET, KEY, uploadId));
    assertThrows(UploadNotExistException.class, () -> objectService.uploadPart(BUCKET, KEY, uploadId, 1,
        UploadPartOptions.builder().contentLength(4).data(data).build()));

    assertEquals(Set.of(), storedObjects());
  }

  /**
   * A stream that runs {@code action} when it is first read.
   */
  private static InputStream onFirstRead(String content, Runnable action) {
    return new FilterInputStream(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))) {

      private boolean started;

      @Override
      public int read() throws IOException {
        start();
        return super.read();
      }

      @Override
      public int read(byte[] b, int off, int len) throws IOException {
        start();
        return super.read(b, off, len);
      }

      private void start() {
        if (!started) {
          started = true;
          action.run();
        }
      }
    };
  }

  /**
   * The store writes the objects that a change touched, which a service reaches through the metadata of its bucket and
   * then changes in place, e.g. the tagging of a version. A restarted service has to see every such change, so this
   * walks the changes that don't replace the object of a key.
   */
  @Test
  void persistsTheChangesMadeInsideAnObject() throws IOException {
    LocalS3Manager manager = LocalS3Manager.createFileSystemS3Manager(dataPath);
    ObjectService objectService = manager.objectService();
    manager.bucketService().createBucket(BUCKET);
    manager.bucketService().setVersioningEnabled(BUCKET, true);

    String firstVersion = objectService.putObject(BUCKET, KEY, PutObjectOptions.builder()
        .content(new ByteArrayInputStream("v1".getBytes(StandardCharsets.UTF_8)))
        .contentType("text/plain")
        .size(2L)
        .build()).getVersionId();
    // A second version is added to the object that the key already holds, rather than replacing it.
    putObject(objectService, "v2");
    // Both change a version in place.
    objectService.putObjectTagging(BUCKET, KEY, firstVersion, new String[][] {{"team", "s3"}});

    String uploadId = objectService.createMultipartUpload(BUCKET, KEY + ".part",
        CreateMultipartUploadOptions.builder().contentType("text/plain").build());

    LocalS3Manager restarted = LocalS3Manager.createFileSystemS3Manager(dataPath);
    ObjectService restartedObjects = restarted.objectService();
    assertEquals("v2", getObject(restartedObjects));
    assertEquals(2, restartedObjects.listObjectVersions(BUCKET, null, null, 10, null, null).getVersions().size());
    assertEquals("team", restartedObjects.getObjectTagging(BUCKET, KEY, firstVersion).getTagging()[0][0],
        "The tagging set on a version of a loaded object is persisted.");
    assertEquals(List.of(uploadId), restartedObjects.listMultipartUploads(BUCKET, null, null, null, 10, null, null)
        .getUploads().stream().map(upload -> upload.getUploadId()).toList());

    // Aborting the upload, and deleting a version, are persisted too.
    restartedObjects.abortMultipartUpload(BUCKET, KEY + ".part", uploadId);
    restartedObjects.deleteObject(BUCKET, KEY, firstVersion);

    LocalS3Manager restartedAgain = LocalS3Manager.createFileSystemS3Manager(dataPath);
    assertEquals(1, restartedAgain.objectService()
        .listObjectVersions(BUCKET, null, null, 10, null, null).getVersions().size());
    assertTrue(restartedAgain.objectService().listMultipartUploads(BUCKET, null, null, null, 10, null, null)
        .getUploads().isEmpty());
  }

  private static void putObject(ObjectService objectService, String content) {
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    objectService.putObject(BUCKET, KEY, PutObjectOptions.builder()
        .content(new ByteArrayInputStream(bytes))
        .contentType("text/plain")
        .size((long) bytes.length)
        .build());
  }

  private static String getObject(ObjectService objectService) throws IOException {
    return new String(objectService.getObject(BUCKET, KEY, GetObjectOptions.builder().build())
        .getContent().readAllBytes(), StandardCharsets.UTF_8);
  }

  private Set<Path> storedObjects() throws IOException {
    // The object files are spread over subdirectories.
    try (Stream<Path> files = Files.walk(dataPath.resolve(LocalS3Manager.STORAGE_DIRECTORY))) {
      return files.filter(Files::isRegularFile).collect(Collectors.toSet());
    }
  }

}
