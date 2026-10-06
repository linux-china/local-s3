package com.robothy.s3.core.service;

import com.robothy.s3.core.assertions.BucketAssertions;
import com.robothy.s3.core.model.internal.BucketMetadata;

/**
 * See {@code docs/semantics.md#stored-not-applied}.
 */
public interface BucketPolicyService extends LocalS3MetadataApplicable {

  default String getBucketPolicy(String bucketName) {
    return withBucketReadLock(bucketName, () -> {
      return BucketAssertions.assertBucketPolicyExist(localS3Metadata(), bucketName);
    });
  }

  default void putBucketPolicy(String bucketName, String policyJson) {
    changeBucket(bucketName, () -> {
      BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucketName);
      bucketMetadata.setPolicy(policyJson);
    });
  }

  default void deleteBucketPolicy(String bucketName) {
    changeBucket(bucketName, () -> {
      BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucketName);
      bucketMetadata.setPolicy(null);
    });
  }

}
