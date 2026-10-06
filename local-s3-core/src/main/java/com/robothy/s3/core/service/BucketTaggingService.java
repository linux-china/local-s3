package com.robothy.s3.core.service;

import com.robothy.s3.core.assertions.BucketAssertions;
import com.robothy.s3.core.model.internal.BucketMetadata;
import java.util.Collection;
import java.util.Map;

public interface BucketTaggingService extends LocalS3MetadataApplicable {

  default void putTagging(String bucketName, Collection<Map<String, String>> tagging) {
    changeBucket(bucketName, () -> {
      BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucketName);
      bucketMetadata.setTagging(tagging);
    });
  }

  default Collection<Map<String, String>> getTagging(String bucketName) {
    return withBucketReadLock(bucketName, () -> {
      BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucketName);
      return BucketAssertions.assertBucketTaggingExist(bucketMetadata);
    });
  }

  default void deleteTagging(String bucketName) {
    changeBucket(bucketName, () -> {
      BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucketName);
      bucketMetadata.setTagging(null);
    });
  }

}
