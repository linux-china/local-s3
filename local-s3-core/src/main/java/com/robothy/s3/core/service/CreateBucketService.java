package com.robothy.s3.core.service;

import com.robothy.s3.core.assertions.BucketAssertions;
import com.robothy.s3.core.event.S3Change;
import com.robothy.s3.core.model.Bucket;
import com.robothy.s3.core.model.internal.BucketMetadata;

/**
 * Create bucket service.
 * <p>
 * <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_CreateBucket.html">CreateBucket</a>
 */
public interface CreateBucketService extends LocalS3MetadataApplicable {

  /**
   * Create a bucket.
   */
  default Bucket createBucket(String bucketName) {
    return changeBucket(bucketName, BucketGuard.Change.CREATE, () -> {
      return createBucket(bucketName, null);
    });
  }

  /**
   * Create a bucket.
   */
  default Bucket createBucket(String bucketName, String region) {
    return changeBucket(bucketName, BucketGuard.Change.CREATE, () -> {
      BucketAssertions.assertBucketNameIsValid(bucketName);
      BucketAssertions.assertBucketNotExists(localS3Metadata(), bucketName);
      BucketMetadata bucketMetadata = new BucketMetadata();
      bucketMetadata.setBucketName(bucketName);
      bucketMetadata.setCreationDate(System.currentTimeMillis());
      bucketMetadata.setRegion(region);
      localS3Metadata().addBucketMetadata(bucketMetadata);
      publishChange(S3Change.bucketCreated("CreateBucket", bucketName, region));
      return Bucket.fromBucketMetadata(bucketMetadata);
    });
  }

}
