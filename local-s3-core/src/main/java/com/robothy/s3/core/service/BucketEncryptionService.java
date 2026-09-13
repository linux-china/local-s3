package com.robothy.s3.core.service;

import com.robothy.s3.core.assertions.BucketAssertions;
import com.robothy.s3.core.model.internal.BucketMetadata;

/**
 * Bucket encryption put/get/delete service.
 */
public interface BucketEncryptionService extends LocalS3MetadataApplicable {

  /**
   * Put encryption configuration to the specified bucket.
   *
   * @param bucketName bucket name.
   * @param encryption encryption configuration.
   */
  default void putBucketEncryption(String bucketName, String encryption) {
    changeBucket(bucketName, () -> {
      BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucketName);
      bucketMetadata.setEncryption(encryption);
    });
  }

  /**
   * Get the encryption configuration of the specified bucket.
   *
   * @param bucketName the bucket name.
   * @return encryption configuration the specified bucket.
   */
  default String getBucketEncryption(String bucketName) {
    return withBucketReadLock(bucketName, () -> {
      return BucketAssertions.assertBucketEncryptionExist(localS3Metadata(), bucketName);
    });
  }

  /**
   * Remove bucket encryption configuration from the specified bucket.
   *
   * @param bucketName the bucket name.
   */
  default void deleteBucketEncryption(String bucketName) {
    changeBucket(bucketName, () -> {
      BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucketName);
      bucketMetadata.setEncryption(null);
    });
  }

}
