package com.robothy.s3.core.service;

import com.robothy.s3.core.assertions.BucketAssertions;
import com.robothy.s3.core.exception.LocalS3RequestException;
import com.robothy.s3.core.exception.S3ErrorCode;
import com.robothy.s3.core.model.BucketObjectLockConfiguration;
import com.robothy.s3.core.model.internal.BucketMetadata;
import java.util.Optional;

/**
 * The object lock configuration of a bucket.
 *
 * <p>A bucket gets Object Lock when it is created with {@code x-amz-bucket-object-lock-enabled: true}, which enables its
 * versioning too, or when an object lock configuration is put to a bucket whose versioning is enabled. Object Lock
 * can't be disabled afterwards, and the versioning of such a bucket can't be suspended.
 *
 * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_PutObjectLockConfiguration.html">PutObjectLockConfiguration</a>
 * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_GetObjectLockConfiguration.html">GetObjectLockConfiguration</a>
 */
public interface BucketObjectLockService extends LocalS3MetadataApplicable {

  /**
   * Put the object lock configuration of a bucket, replacing its default retention, and enabling Object Lock if the
   * bucket doesn't have it yet.
   *
   * @param bucketName the bucket name.
   * @param configuration the configuration.
   * @return the stored configuration.
   * @throws LocalS3RequestException {@code InvalidBucketState} if the bucket doesn't have Object Lock yet and its
   *     versioning isn't enabled.
   */
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

  /**
   * Get the object lock configuration of a bucket.
   *
   * @param bucketName the bucket name.
   * @return the configuration; empty if the bucket doesn't have Object Lock enabled.
   */
  default Optional<BucketObjectLockConfiguration> getObjectLockConfiguration(String bucketName) {
    return withBucketReadLock(bucketName, () -> {
      BucketAssertions.assertBucketNameIsValid(bucketName);
      return BucketAssertions.assertBucketExists(localS3Metadata(), bucketName).getObjectLock();
    });
  }

}
