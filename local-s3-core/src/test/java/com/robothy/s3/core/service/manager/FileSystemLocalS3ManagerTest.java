package com.robothy.s3.core.service.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
  void failedPersistenceKeepsMemoryStorageAndDiskConsistent() throws IOException {
    LocalS3Manager manager = LocalS3Manager.createFileSystemS3Manager(dataPath);
    ObjectService objectService = manager.objectService();
    manager.bucketService().createBucket(BUCKET);
    putObject(objectService, "v1");
    Set<Path> storedObjects = storedObjects();

    // Overwriting the object in the non-versioned bucket deletes v1, but the metadata can't be persisted.
    dataPath.toFile().setWritable(false);
    assumeFalse(Files.isWritable(dataPath), "The data directory can't be made read-only, e.g. when running as root.");
    assertThrows(Exception.class, () -> putObject(objectService, "v2"));
    dataPath.toFile().setWritable(true);

    assertEquals(storedObjects, storedObjects(), "v1 is kept and v2 is removed.");
    assertEquals("v1", getObject(objectService));
    assertEquals("v1", getObject(LocalS3Manager.createFileSystemS3Manager(dataPath).objectService()));

    putObject(objectService, "v3");
    assertEquals(1, storedObjects().size(), "v1 is deleted once v3 is persisted.");
    assertEquals("v3", getObject(LocalS3Manager.createFileSystemS3Manager(dataPath).objectService()));
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
    try (Stream<Path> files = Files.list(dataPath.resolve(LocalS3Manager.STORAGE_DIRECTORY))) {
      return files.collect(Collectors.toSet());
    }
  }

}
