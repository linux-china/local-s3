package com.robothy.s3.core.service;

import com.robothy.s3.core.assertions.BucketAssertions;
import com.robothy.s3.core.model.internal.BucketMetadata;
import com.robothy.s3.datatypes.PublicAccessBlockConfiguration;
import java.util.Optional;

public interface BucketPublicAccessBlockService extends LocalS3MetadataApplicable {

  default void putPublicAccessBlock(String bucketName, PublicAccessBlockConfiguration configuration) {
    changeBucket(bucketName, () -> {
      BucketAssertions.assertBucketNameIsValid(bucketName);
      BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucketName);
      bucketMetadata.setPublicAccessBlock(configuration);
    });
  }

  default Optional<PublicAccessBlockConfiguration> getPublicAccessBlock(String bucketName) {
    return withBucketReadLock(bucketName, () -> {
      BucketAssertions.assertBucketNameIsValid(bucketName);
      BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucketName);
      return bucketMetadata.getPublicAccessBlock();
    });
  }

  default void deletePublicAccessBlock(String bucketName) {
    changeBucket(bucketName, () -> {
      BucketAssertions.assertBucketNameIsValid(bucketName);
      BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucketName);
      bucketMetadata.setPublicAccessBlock(null);
    });
  }
}
