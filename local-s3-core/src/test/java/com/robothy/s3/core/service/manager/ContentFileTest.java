package com.robothy.s3.core.service.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.robothy.s3.core.exception.LocalS3BadDigestException;
import com.robothy.s3.core.model.request.CompleteMultipartUploadPartOption;
import com.robothy.s3.core.model.request.CreateMultipartUploadOptions;
import com.robothy.s3.core.model.request.GetObjectOptions;
import com.robothy.s3.core.model.request.PutObjectOptions;
import com.robothy.s3.core.model.request.UploadPartOptions;
import com.robothy.s3.core.service.ObjectService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The content of an upload that is buffered in a file is handed over to the storage, which renames the file into
 * place when it can, rather than writing the content a second time.
 */
class ContentFileTest {

  private static final String BUCKET = "my-bucket";

  @Test
  void aPersistentStorageRenamesTheFileOfAnObject(@TempDir Path dataPath) throws IOException {
    LocalS3Manager manager = LocalS3Manager.createFileSystemS3Manager(dataPath);
    manager.bucketService().createBucket(BUCKET);
    ObjectService objectService = manager.objectService();
    Path storageDirectory = dataPath.resolve(LocalS3Manager.STORAGE_DIRECTORY);
    Path body = Files.writeString(Files.createDirectories(storageDirectory.resolve(".bodies")).resolve("body"),
        "Hello");

    String etag = objectService.putObject(BUCKET, "a.txt", PutObjectOptions.builder()
        .content(Files.newInputStream(body))
        .contentFile(body)
        .size(5)
        .build()).getEtag();

    assertFalse(Files.exists(body), "The file is renamed into the storage.");
    assertEquals(DigestUtils.md5Hex("Hello"), etag);
    assertEquals("Hello", read(objectService, "a.txt"));
    assertEquals(1, countObjects(storageDirectory));
  }

  @Test
  void aPersistentStorageRenamesTheFileOfAPart(@TempDir Path dataPath) throws IOException {
    LocalS3Manager manager = LocalS3Manager.createFileSystemS3Manager(dataPath);
    manager.bucketService().createBucket(BUCKET);
    ObjectService objectService = manager.objectService();
    Path body = Files.writeString(dataPath.resolve("body"), "Hello");
    String uploadId = objectService.createMultipartUpload(BUCKET, "a.txt",
        CreateMultipartUploadOptions.builder().build());

    String etag = objectService.uploadPart(BUCKET, "a.txt", uploadId, 1, UploadPartOptions.builder()
        .contentLength(5)
        .dataFile(body)
        .build()).getEtag();
    objectService.completeMultipartUpload(BUCKET, "a.txt", uploadId,
        List.of(CompleteMultipartUploadPartOption.builder().partNumber(1).build()));

    assertFalse(Files.exists(body));
    assertEquals(DigestUtils.md5Hex("Hello"), etag);
    assertEquals("Hello", read(objectService, "a.txt"));
  }

  @Test
  void anInMemoryStorageCopiesTheFile(@TempDir Path directory) throws IOException {
    LocalS3Manager manager = LocalS3Manager.createInMemoryS3Manager();
    manager.bucketService().createBucket(BUCKET);
    ObjectService objectService = manager.objectService();
    Path body = Files.writeString(directory.resolve("body"), "Hello");

    objectService.putObject(BUCKET, "a.txt", PutObjectOptions.builder()
        .content(Files.newInputStream(body))
        .contentFile(body)
        .size(5)
        .build());

    assertTrue(Files.exists(body), "The file is left to its owner.");
    assertEquals("Hello", read(objectService, "a.txt"));
  }

  @Test
  void theContentOfARejectedObjectIsDeleted(@TempDir Path dataPath) throws IOException {
    LocalS3Manager manager = LocalS3Manager.createFileSystemS3Manager(dataPath);
    manager.bucketService().createBucket(BUCKET);
    ObjectService objectService = manager.objectService();
    Path body = Files.writeString(dataPath.resolve("body"), "Hello");

    assertThrows(LocalS3BadDigestException.class, () -> objectService.putObject(BUCKET, "a.txt",
        PutObjectOptions.builder()
            .content(Files.newInputStream(body))
            .contentFile(body)
            .contentMd5("AAAAAAAAAAAAAAAAAAAAAA==")
            .size(5)
            .build()));

    assertEquals(0, countObjects(dataPath.resolve(LocalS3Manager.STORAGE_DIRECTORY)));
  }

  private static String read(ObjectService objectService, String key) throws IOException {
    try (InputStream in = objectService.getObject(BUCKET, key, GetObjectOptions.builder().build()).getContent()) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static long countObjects(Path storageDirectory) throws IOException {
    try (Stream<Path> files = Files.list(storageDirectory)) {
      return files.filter(Files::isRegularFile).count();
    }
  }

}
