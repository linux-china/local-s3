package com.robothy.s3.rest.service;

import com.robothy.s3.core.assertions.UploadAssertions;

/**
 * What a multipart upload of this service must satisfy, besides the rules that always apply.
 *
 * @param minimumPartSize the smallest size of a part that isn't the last one of an upload; {@code 0} accepts
 *     parts of any size, which is the default so that tests that upload small parts keep working.
 */
public record MultipartUploadPolicy(long minimumPartSize) {

  /**
   * Create the policy of a service.
   *
   * @param strictPartSizes whether a part that isn't the last one must have the size that Amazon S3 requires.
   * @return the policy to apply.
   */
  public static MultipartUploadPolicy of(boolean strictPartSizes) {
    return new MultipartUploadPolicy(strictPartSizes ? UploadAssertions.MIN_PART_SIZE : 0);
  }

}
