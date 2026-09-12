package com.robothy.s3.core.service;

import static org.junit.jupiter.api.Assertions.*;
import com.robothy.s3.core.assertions.UploadAssertions;
import com.robothy.s3.core.exception.UploadNotExistException;
import com.robothy.s3.core.model.answers.CompleteMultipartUploadAns;
import com.robothy.s3.core.model.answers.UploadPartAns;
import com.robothy.s3.core.model.internal.BucketMetadata;
import com.robothy.s3.core.model.internal.LocalS3Metadata;
import com.robothy.s3.core.model.internal.UploadMetadata;
import com.robothy.s3.core.model.internal.UploadPartMetadata;
import com.robothy.s3.core.model.request.CompleteMultipartUploadPartOption;
import com.robothy.s3.core.model.request.CreateMultipartUploadOptions;
import com.robothy.s3.core.model.request.GetObjectOptions;
import com.robothy.s3.core.model.request.UploadPartOptions;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class UploadPartServiceTest extends LocalS3ServiceTestBase {

  @ParameterizedTest
  @MethodSource("localS3Services")
  void doesNotLockBucketWhileStoringPart(BucketService bucketService, ObjectService objectService) throws Exception {
    String bucket = "my-bucket";
    String key = "a.txt";
    bucketService.createBucket(bucket);
    String uploadId = objectService.createMultipartUpload(bucket, key,
        CreateMultipartUploadOptions.builder().contentType("plain/text").build());

    BlockingInputStream data = new BlockingInputStream("Robothy".getBytes(StandardCharsets.UTF_8));
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<UploadPartAns> uploading = executor.submit(() -> objectService.uploadPart(bucket, key, uploadId, 1,
          UploadPartOptions.builder().contentLength(7).data(data).build()));
      data.awaitReading();

      // Other parts are uploaded without waiting for the data of the first one.
      assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
          objectService.uploadPart(bucket, key, uploadId, 2, part("!")));

      data.release();
      assertEquals(DigestUtils.md5Hex("Robothy"), uploading.get(5, TimeUnit.SECONDS).getEtag());
      assertEquals(2, uploadMetadata(objectService, bucket, key, uploadId).getParts().size());
    } finally {
      data.release();
      executor.shutdownNow();
    }
  }

  @ParameterizedTest
  @MethodSource("localS3Services")
  void replacingPartDeletesReplacedData(BucketService bucketService, ObjectService objectService) throws Exception {
    String bucket = "my-bucket";
    String key = "a.txt";
    bucketService.createBucket(bucket);
    String uploadId = objectService.createMultipartUpload(bucket, key,
        CreateMultipartUploadOptions.builder().contentType("plain/text").build());

    objectService.uploadPart(bucket, key, uploadId, 1, part("first"));
    Long replacedFileId = uploadMetadata(objectService, bucket, key, uploadId).getParts().get(1).getFileId();
    objectService.uploadPart(bucket, key, uploadId, 1, part("second"));

    assertFalse(objectService.storage().isExist(replacedFileId));
    Long fileId = uploadMetadata(objectService, bucket, key, uploadId).getParts().get(1).getFileId();
    try (InputStream stored = objectService.storage().getInputStream(fileId)) {
      assertEquals("second", new String(stored.readAllBytes(), StandardCharsets.UTF_8));
    }
  }

  /**
   * The declared length of a part is not trusted either: it is summed into the length of the completed object.
   */
  @ParameterizedTest
  @MethodSource("localS3Services")
  void storesTheLengthOfThePartInsteadOfTheDeclaredOne(BucketService bucketService, ObjectService objectService) {
    String bucket = "my-bucket";
    String key = "a.txt";
    bucketService.createBucket(bucket);
    String uploadId = objectService.createMultipartUpload(bucket, key,
        CreateMultipartUploadOptions.builder().contentType("plain/text").build());

    objectService.uploadPart(bucket, key, uploadId, 1, UploadPartOptions.builder()
        .contentLength(700) // Doesn't match the data.
        .data(new ByteArrayInputStream("Robo".getBytes(StandardCharsets.UTF_8)))
        .build());
    objectService.uploadPart(bucket, key, uploadId, 2, UploadPartOptions.builder()
        .contentLength(1) // Doesn't match the data.
        .data(new ByteArrayInputStream("thy".getBytes(StandardCharsets.UTF_8)))
        .build());

    UploadMetadata uploadMetadata = uploadMetadata(objectService, bucket, key, uploadId);
    assertEquals(4, uploadMetadata.getParts().get(1).getSize());
    assertEquals(3, uploadMetadata.getParts().get(2).getSize());

    CompleteMultipartUploadAns completed = objectService.completeMultipartUpload(bucket, key, uploadId, List.of(
        CompleteMultipartUploadPartOption.builder().partNumber(1).build(),
        CompleteMultipartUploadPartOption.builder().partNumber(2).build()));
    assertEquals(7, completed.getSize());
    assertEquals(7, objectService.getObject(bucket, key, GetObjectOptions.builder().build()).getSize());
  }

  private static UploadPartOptions part(String text) {
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    return UploadPartOptions.builder().contentLength(bytes.length).data(new ByteArrayInputStream(bytes)).build();
  }

  private static UploadMetadata uploadMetadata(ObjectService objectService, String bucket, String key, String uploadId) {
    return UploadAssertions.assertUploadExists(objectService.localS3Metadata().getBucketMetadata(bucket).get(), key, uploadId);
  }

  @ParameterizedTest
  @MethodSource("localS3Services")
  void uploadPart(BucketService bucketService, ObjectService objectService) {
    String bucket = "my-bucket";
    String key = "a.txt";
    bucketService.createBucket(bucket);

    assertThrows(UploadNotExistException.class, () -> objectService.uploadPart(bucket, key, "123", 1, null));
    String uploadId = objectService.createMultipartUpload(bucket, key,
        CreateMultipartUploadOptions.builder().contentType("plain/text").build());
    assertThrows(UploadNotExistException.class, () -> objectService.uploadPart(bucket, key, "123", 1, null));

    UploadPartOptions uploadPartOptions = UploadPartOptions.builder()
        .contentLength(7)
        .data(new ByteArrayInputStream("Robothy".getBytes()))
        .build();
    objectService.uploadPart(bucket, key, uploadId, 1, uploadPartOptions);
    LocalS3Metadata localS3Metadata = objectService.localS3Metadata();
    Optional<BucketMetadata> bucketMetadataOpt = localS3Metadata.getBucketMetadata(bucket);
    assertTrue(bucketMetadataOpt.isPresent());
    UploadMetadata uploadMetadata = UploadAssertions.assertUploadExists(bucketMetadataOpt.get(), key, uploadId);
    UploadPartMetadata uploadPartMetadata1 = uploadMetadata.getParts().get(1);
    assertNotNull(uploadPartMetadata1);
    assertNotEquals(0, uploadPartMetadata1.getFileId());
    assertEquals(7, uploadPartMetadata1.getSize());
    assertTrue(System.currentTimeMillis() - uploadPartMetadata1.getLastModified() < 5000);
  }
}