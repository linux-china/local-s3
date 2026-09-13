package com.robothy.s3.core.service;

import com.robothy.s3.core.assertions.BucketAssertions;
import com.robothy.s3.core.model.internal.BucketMetadata;
import java.util.Collection;
import java.util.Map;

/**
 * Bucket tagging related operations.
 */
public interface BucketTaggingService extends LocalS3MetadataApplicable {

  /**
   * Put tagging to the specified bucket.
   *
   * @param bucketName The name of the bucket for which to set the tagging.
   * @param tagging tagging to the bucket.
   */
  default void putTagging(String bucketName, Collection<Map<String, String>> tagging) {
    changeBucket(bucketName, () -> {
      BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucketName);
      bucketMetadata.setTagging(tagging);
    });
  }

  /**
   * Gets the tagging configuration for the specified bucket.
   *
   * @param bucketName The request object for retrieving the bucket tagging
   * @return tagging of the specified bucket.
   */
  default Collection<Map<String, String>> getTagging(String bucketName) {
    return withBucketReadLock(bucketName, () -> {
      BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucketName);
      return BucketAssertions.assertBucketTaggingExist(bucketMetadata);
    });
  }

  /**
   * Delete the bucket tagging.
   *
   * @param bucketName the name of the bucket for which to remove the tagging
   */
  default void deleteTagging(String bucketName) {
    changeBucket(bucketName, () -> {
      BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucketName);
      bucketMetadata.setTagging(null);
    });
  }

}
