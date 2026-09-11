package com.robothy.s3.rest.service;

import com.robothy.s3.core.assertions.BucketAssertions;

/**
 * Validates the names of buckets to create. In strict mode, a name must follow the naming rules of
 * Amazon S3 general purpose buckets; otherwise, any non-blank name is accepted.
 */
public final class BucketNameValidator {

  private final boolean strict;

  /**
   * Create a validator.
   *
   * @param strict whether bucket names must follow the naming rules of Amazon S3.
   */
  public BucketNameValidator(boolean strict) {
    this.strict = strict;
  }

  /**
   * Validate the name of a bucket to create.
   *
   * @param bucketName the bucket name.
   * @throws com.robothy.s3.core.exception.InvalidBucketNameException if the name is invalid.
   */
  public void validate(String bucketName) {
    if (strict) {
      BucketAssertions.assertBucketNameFollowsNamingRules(bucketName);
    } else {
      BucketAssertions.assertBucketNameIsValid(bucketName);
    }
  }

}
