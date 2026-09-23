package com.robothy.s3.rest.service;

import com.robothy.s3.core.assertions.BucketAssertions;

/**
 * Validates the names of buckets to create. A name must follow the
 * <a href="https://docs.aws.amazon.com/AmazonS3/latest/userguide/bucketnamingrules.html">naming rules</a>
 * of Amazon S3 general purpose buckets, or of S3 Express One Zone directory buckets.
 */
public final class BucketNameValidator {

  /**
   * Validate the name of a bucket to create.
   *
   * @param bucketName the bucket name.
   * @throws com.robothy.s3.core.exception.InvalidBucketNameException if the name is invalid.
   */
  public void validate(String bucketName) {
    BucketAssertions.assertBucketNameFollowsNamingRules(bucketName);
  }

}
