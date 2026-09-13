package com.robothy.s3.core.service;

import com.robothy.s3.core.assertions.BucketAssertions;
import com.robothy.s3.core.model.internal.BucketMetadata;
import com.robothy.s3.datatypes.PublicAccessBlockConfiguration;
import java.util.Optional;

/**
 * Bucket public access block service.
 * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_PutPublicAccessBlock.html">PutPublicAccessBlock</a>
 * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_GetPublicAccessBlock.html">GetPublicAccessBlock</a>
 * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_DeletePublicAccessBlock.html">DeletePublicAccessBlock</a>
 */
public interface BucketPublicAccessBlockService extends LocalS3MetadataApplicable {

  /**
   * Put public access block configuration to the specified bucket.
   *
   * @param bucketName bucket that associates with the public access block configuration.
   * @param configuration public access block configuration.
   */
  default void putPublicAccessBlock(String bucketName, PublicAccessBlockConfiguration configuration) {
    changeBucket(bucketName, () -> {
      BucketAssertions.assertBucketNameIsValid(bucketName);
      BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucketName);
      bucketMetadata.setPublicAccessBlock(configuration);
    });
  }

  /**
   * Get public access block configuration.
   *
   * @param bucketName the bucket that associates with the public access block configuration.
   * @return the public access block configuration.
   */
  default Optional<PublicAccessBlockConfiguration> getPublicAccessBlock(String bucketName) {
    return withBucketReadLock(bucketName, () -> {
      BucketAssertions.assertBucketNameIsValid(bucketName);
      BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucketName);
      return bucketMetadata.getPublicAccessBlock();
    });
  }

  /**
   * Delete public access block configuration.
   *
   * @param bucketName the bucket name.
   */
  default void deletePublicAccessBlock(String bucketName) {
    changeBucket(bucketName, () -> {
      BucketAssertions.assertBucketNameIsValid(bucketName);
      BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucketName);
      bucketMetadata.setPublicAccessBlock(null);
    });
  }
}
