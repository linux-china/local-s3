package com.robothy.s3.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.robothy.s3.core.exception.BucketAlreadyExistsException;
import com.robothy.s3.core.exception.BucketNotExistException;
import com.robothy.s3.core.model.Bucket;
import com.robothy.s3.core.model.internal.UploadPartMetadata;
import com.robothy.s3.core.model.request.CreateMultipartUploadOptions;
import com.robothy.s3.core.model.request.UploadPartOptions;
import java.io.ByteArrayInputStream;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class BucketServiceTest extends LocalS3ServiceTestBase {


  @MethodSource("bucketServices")
  @ParameterizedTest
  void deleteBucket(BucketService bucketService) {
    assertThrows(BucketNotExistException.class, () -> bucketService.deleteBucket("bucket"));
    Bucket bucket = bucketService.createBucket("bucket");
    Bucket deletedBucket = bucketService.deleteBucket("bucket");
    assertEquals(bucket, deletedBucket);
  }

  @MethodSource("localS3Services")
  @ParameterizedTest
  void deleteBucketDeletesThePartsOfItsUploads(BucketService bucketService, ObjectService objectService) {
    bucketService.createBucket("uploads");
    String uploadId = objectService.createMultipartUpload("uploads", "a.txt",
        CreateMultipartUploadOptions.builder().build());
    objectService.uploadPart("uploads", "a.txt", uploadId, 1, UploadPartOptions.builder()
        .contentLength(5)
        .data(new ByteArrayInputStream("Hello".getBytes()))
        .build());
    List<Long> fileIds = objectService.localS3Metadata().getBucketMetadata("uploads").orElseThrow()
        .getUploads().get("a.txt").get(uploadId).getParts().values().stream()
        .map(UploadPartMetadata::getFileId).toList();
    assertEquals(1, fileIds.size());
    assertTrue(objectService.storage().isExist(fileIds.get(0)));

    bucketService.deleteBucket("uploads");
    assertFalse(objectService.storage().isExist(fileIds.get(0)));
  }

  @MethodSource("bucketServices")
  @ParameterizedTest
  void getBucket(BucketService bucketService) {
    assertThrows(BucketNotExistException.class, () -> bucketService.getBucket("bucket"));
    Bucket bucket = bucketService.createBucket("bucket", "region");
    Bucket getBucket = bucketService.getBucket("bucket");
    assertEquals(bucket, getBucket);
  }

  @MethodSource("bucketServices")
  @ParameterizedTest
  void setVersioningEnabled(BucketService bucketService) {
    String bucketName = "bucket";
    Bucket bucket = bucketService.createBucket(bucketName);
    assertNull(bucket.getVersioningEnabled());

    bucketService.setVersioningEnabled(bucketName, true);
    bucket = bucketService.getBucket(bucketName);
    assertTrue(bucket.getVersioningEnabled());

    bucketService.setVersioningEnabled(bucketName, false);
    bucket = bucketService.getBucket(bucketName);
    assertFalse(bucket.getVersioningEnabled());
  }

  @MethodSource("bucketServices")
  @ParameterizedTest
  void listBuckets(BucketService bucketService) throws InterruptedException {
    String bucketName1 = "bucket1";
    String bucketName2 = "bucket2";
    bucketService.createBucket(bucketName1);
    Thread.sleep(1L); // Makes sure bucket1 and bucket2 are created in different time.
    bucketService.createBucket(bucketName2);
    List<Bucket> buckets = bucketService.listBuckets();
    assertEquals(2, buckets.size());
    assertEquals(bucketName1, buckets.get(0).getName());
    assertTrue(System.currentTimeMillis() - buckets.get(0).getCreationDate() < 2000);
    assertEquals(bucketName2, buckets.get(1).getName());
    assertTrue(System.currentTimeMillis() - buckets.get(1).getCreationDate() < 2000);

    bucketService.deleteBucket(bucketName1);
    buckets = bucketService.listBuckets();
    assertEquals(1, buckets.size());
    assertEquals(bucketName2, buckets.get(0).getName());
    assertTrue(System.currentTimeMillis() - buckets.get(0).getCreationDate() < 2000);
  }

}