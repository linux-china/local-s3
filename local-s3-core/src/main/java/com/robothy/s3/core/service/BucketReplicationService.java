package com.robothy.s3.core.service;

import com.robothy.s3.core.assertions.BucketAssertions;
import com.robothy.s3.core.model.internal.BucketMetadata;

/**
 * See {@code docs/semantics.md#stored-not-applied}.
 */
public interface BucketReplicationService extends LocalS3MetadataApplicable {

  default void putBucketReplication(String bucketName, String replicationConfig) {
    changeBucket(bucketName, () -> {
      BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucketName);
      bucketMetadata.setReplication(replicationConfig);
    });
  }

  default String getBucketReplication(String bucketName) {
    return withBucketReadLock(bucketName, () -> {
      return BucketAssertions.assertBucketReplicationExist(localS3Metadata(), bucketName);
    });
  }

  default void deleteBucketReplication(String bucketName) {
    changeBucket(bucketName, () -> {
      BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucketName);
      bucketMetadata.setReplication(null);
    });
  }

}
