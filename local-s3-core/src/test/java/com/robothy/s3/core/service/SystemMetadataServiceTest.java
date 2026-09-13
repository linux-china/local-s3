package com.robothy.s3.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import com.robothy.s3.core.model.internal.SystemMetadata;
import com.robothy.s3.core.model.request.CompleteMultipartUploadPartOption;
import com.robothy.s3.core.model.request.CopyObjectOptions;
import com.robothy.s3.core.model.request.CreateMultipartUploadOptions;
import com.robothy.s3.core.model.request.GetObjectOptions;
import com.robothy.s3.core.model.request.PutObjectOptions;
import com.robothy.s3.core.model.request.UploadPartOptions;
import com.robothy.s3.core.service.manager.LocalS3Manager;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The system-defined metadata that an object is stored with, e.g. its {@code Cache-Control}, is kept with the
 * object and answered by the reads of it.
 */
class SystemMetadataServiceTest extends LocalS3ServiceTestBase {

  private static final String BUCKET = "my-bucket";

  private static final SystemMetadata SYSTEM_METADATA = SystemMetadata.builder()
      .cacheControl("max-age=3600")
      .contentDisposition("attachment; filename=\"report.csv\"")
      .contentEncoding("gzip")
      .contentLanguage("en-US")
      .expires("Thu, 01 Dec 2033 16:00:00 GMT")
      .build();

  private static void put(ObjectService objectService, String key, String contentType, SystemMetadata systemMetadata) {
    byte[] content = "Hello".getBytes(StandardCharsets.UTF_8);
    objectService.putObject(BUCKET, key, PutObjectOptions.builder()
        .contentType(contentType)
        .systemMetadata(systemMetadata)
        .size(content.length)
        .content(new ByteArrayInputStream(content))
        .build());
  }

  @ParameterizedTest
  @MethodSource("localS3Services")
  void putObjectKeepsTheSystemMetadata(BucketService bucketService, ObjectService objectService) {
    bucketService.createBucket(BUCKET);
    put(objectService, "a.txt", "text/plain", SYSTEM_METADATA);
    put(objectService, "b.txt", "text/plain", null);

    assertEquals(SYSTEM_METADATA, objectService.getObject(BUCKET, "a.txt", GetObjectOptions.builder().build())
        .getSystemMetadata());
    assertEquals(SYSTEM_METADATA, objectService.headObject(BUCKET, "a.txt", GetObjectOptions.builder().build())
        .getSystemMetadata());
    assertNull(objectService.headObject(BUCKET, "b.txt", GetObjectOptions.builder().build()).getSystemMetadata());
  }

  @ParameterizedTest
  @MethodSource("localS3Services")
  void completedMultipartUploadKeepsTheSystemMetadataOfTheUpload(BucketService bucketService,
                                                                  ObjectService objectService) {
    bucketService.createBucket(BUCKET);
    String uploadId = objectService.createMultipartUpload(BUCKET, "a.txt", CreateMultipartUploadOptions.builder()
        .contentType("text/plain")
        .systemMetadata(SYSTEM_METADATA)
        .build());
    objectService.uploadPart(BUCKET, "a.txt", uploadId, 1, UploadPartOptions.builder()
        .data(new ByteArrayInputStream("Hello".getBytes(StandardCharsets.UTF_8)))
        .contentLength(5)
        .build());
    objectService.completeMultipartUpload(BUCKET, "a.txt", uploadId,
        List.of(CompleteMultipartUploadPartOption.builder().partNumber(1).build()));

    assertEquals(SYSTEM_METADATA, objectService.headObject(BUCKET, "a.txt", GetObjectOptions.builder().build())
        .getSystemMetadata());
  }

  @ParameterizedTest
  @MethodSource("localS3Services")
  void copyObjectCopiesTheSystemMetadataUnlessTheDirectiveReplacesIt(BucketService bucketService,
                                                                    ObjectService objectService) {
    bucketService.createBucket(BUCKET);
    put(objectService, "source", "text/csv", SYSTEM_METADATA);

    objectService.copyObject(BUCKET, "copied", CopyObjectOptions.builder()
        .sourceBucket(BUCKET).sourceKey("source").build());
    var copied = objectService.headObject(BUCKET, "copied", GetObjectOptions.builder().build());
    assertEquals("text/csv", copied.getContentType());
    assertEquals(SYSTEM_METADATA, copied.getSystemMetadata());

    SystemMetadata replacement = SystemMetadata.builder().cacheControl("no-cache").build();
    objectService.copyObject(BUCKET, "replaced", CopyObjectOptions.builder()
        .sourceBucket(BUCKET).sourceKey("source")
        .metadataDirective(CopyObjectOptions.MetadataDirective.REPLACE)
        .contentType("application/json")
        .systemMetadata(replacement)
        .build());
    var replaced = objectService.headObject(BUCKET, "replaced", GetObjectOptions.builder().build());
    assertEquals("application/json", replaced.getContentType());
    assertEquals(replacement, replaced.getSystemMetadata());
  }

  @Test
  void systemMetadataIsPersisted(@TempDir Path dataPath) {
    LocalS3Manager manager = LocalS3Manager.createFileSystemS3Manager(dataPath);
    manager.bucketService().createBucket(BUCKET);
    put(manager.objectService(), "a.txt", "text/plain", SYSTEM_METADATA);

    LocalS3Manager reloaded = LocalS3Manager.createFileSystemS3Manager(dataPath);
    assertEquals(SYSTEM_METADATA, reloaded.objectService().headObject(BUCKET, "a.txt", GetObjectOptions.builder().build())
        .getSystemMetadata());
  }

}
