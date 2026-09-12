package com.robothy.s3.rest.service;

import com.robothy.s3.core.assertions.UploadAssertions;

/**
 * What a multipart upload of this service must satisfy, besides the rules that always apply, and how the
 * object of a completed upload is identified.
 *
 * @param minimumPartSize the smallest size of a part that isn't the last one of an upload; {@code 0} accepts
 *     parts of any size, which is the default so that tests that upload small parts keep working.
 * @param compositeEtags whether the object of a completed upload gets the entity tag that Amazon S3 gives an
 *     object uploaded in parts, i.e. the digest of the digests of its parts with a {@code -<parts>} suffix;
 *     {@code false} gives it the MD5 digest of its whole content, which is what LocalS3 did before 2.5.
 */
public record MultipartUploadPolicy(long minimumPartSize, boolean compositeEtags) {

  /**
   * Create the policy of a service that gives the objects of completed uploads the entity tag of Amazon S3.
   *
   * @param strictPartSizes whether a part that isn't the last one must have the size that Amazon S3 requires.
   * @return the policy to apply.
   */
  public static MultipartUploadPolicy of(boolean strictPartSizes) {
    return of(strictPartSizes, true);
  }

  /**
   * Create the policy of a service.
   *
   * @param strictPartSizes whether a part that isn't the last one must have the size that Amazon S3 requires.
   * @param compositeEtags whether the object of a completed upload gets the entity tag of Amazon S3.
   * @return the policy to apply.
   */
  public static MultipartUploadPolicy of(boolean strictPartSizes, boolean compositeEtags) {
    return new MultipartUploadPolicy(strictPartSizes ? UploadAssertions.MIN_PART_SIZE : 0, compositeEtags);
  }

}
