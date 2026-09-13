package com.robothy.s3.core.service;

import com.robothy.s3.core.assertions.BucketAssertions;
import com.robothy.s3.core.model.internal.BucketMetadata;

/**
 * Bucket replication configuration service. Put/Get/Delete bucket configuration.
 */
public interface BucketReplicationService extends LocalS3MetadataApplicable {

  /**
   * Put bucket replication configuration to the specified bucket.
   *
   * @param bucketName bucket that the replication configuration applies to.
   * @param replicationConfig replication configuration. LocalS3 only stores it, won't parse it.
   */
  default void putBucketReplication(String bucketName, String replicationConfig) {
    changeBucket(bucketName, () -> {
      BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucketName);
      bucketMetadata.setReplication(replicationConfig);
    });
  }

  /**
   * Get bucket configuration of the specified bucket.
   *
   * @param bucketName the bucket where the replication configuration is fetched.
   * @return bucket configuration.
   */
  default String getBucketReplication(String bucketName) {
    return withBucketReadLock(bucketName, () -> {
      return BucketAssertions.assertBucketReplicationExist(localS3Metadata(), bucketName);
    });
  }

  /**
   * Delete the replication configuration of the specified bucket.
   *
   * @param bucketName the bucket name.
   */
  default void deleteBucketReplication(String bucketName) {
    changeBucket(bucketName, () -> {
      BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucketName);
      bucketMetadata.setReplication(null);
    });
  }

}
