package com.robothy.s3.core.service;

import com.robothy.s3.core.assertions.BucketAssertions;
import com.robothy.s3.core.exception.LocalS3RequestException;
import com.robothy.s3.core.exception.S3ErrorCode;
import com.robothy.s3.core.model.BucketObjectLockConfiguration;
import com.robothy.s3.core.model.internal.BucketMetadata;
import java.util.Optional;

/**
 * See {@code docs/semantics.md#object-lock}.
 */
public interface BucketObjectLockService extends LocalS3MetadataApplicable {

  default BucketObjectLockConfiguration putObjectLockConfiguration(String bucketName,
                                                                   BucketObjectLockConfiguration configuration) {
    return changeBucket(bucketName, () -> {
      BucketAssertions.assertBucketNameIsValid(bucketName);
      BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucketName);
      if (bucketMetadata.getObjectLock().isEmpty() && !Boolean.TRUE.equals(bucketMetadata.getVersioningEnabled())) {
        throw new LocalS3RequestException(S3ErrorCode.InvalidBucketState,
            "Versioning must be 'Enabled' on the bucket to apply a Object Lock configuration");
      }
      bucketMetadata.setObjectLock(configuration);
      return configuration;
    });
  }

  default Optional<BucketObjectLockConfiguration> getObjectLockConfiguration(String bucketName) {
    return withBucketReadLock(bucketName, () -> {
      BucketAssertions.assertBucketNameIsValid(bucketName);
      return BucketAssertions.assertBucketExists(localS3Metadata(), bucketName).getObjectLock();
    });
  }

}
