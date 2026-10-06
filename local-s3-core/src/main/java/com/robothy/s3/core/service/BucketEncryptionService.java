package com.robothy.s3.core.service;

import com.robothy.s3.core.assertions.BucketAssertions;
import com.robothy.s3.core.model.internal.BucketMetadata;

/**
 * See {@code docs/semantics.md#server-side-encryption-with-s3-managed-and-kms-keys-sse-s3-sse-kms}.
 */
public interface BucketEncryptionService extends LocalS3MetadataApplicable {

  default void putBucketEncryption(String bucketName, String encryption) {
    changeBucket(bucketName, () -> {
      BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucketName);
      bucketMetadata.setEncryption(encryption);
    });
  }

  default String getBucketEncryption(String bucketName) {
    return withBucketReadLock(bucketName, () -> {
      return BucketAssertions.assertBucketEncryptionExist(localS3Metadata(), bucketName);
    });
  }

  default void deleteBucketEncryption(String bucketName) {
    changeBucket(bucketName, () -> {
      BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucketName);
      bucketMetadata.setEncryption(null);
    });
  }

}
