package com.robothy.s3.rest.service;

import com.robothy.s3.core.assertions.UploadAssertions;

/**
 * What a multipart upload of this service must satisfy, besides the rules that always apply, and how the
 * object of a completed upload is identified.
 *
 * @param minimumPartSize the smallest size of a part that isn't the last one of an upload; the 5 MiB that
 *     Amazon S3 requires.
 * @param compositeEtags whether the object of a completed upload gets the entity tag that Amazon S3 gives an
 *     object uploaded in parts, i.e. the digest of the digests of its parts with a {@code -<parts>} suffix;
 *     {@code false} gives it the MD5 digest of its whole content, which is what LocalS3 did before 2.5.
 */
public record MultipartUploadPolicy(long minimumPartSize, boolean compositeEtags) {

  /**
   * Create the policy of a service, which requires a part that isn't the last one to have the size that
   * Amazon S3 requires.
   *
   * @param compositeEtags whether the object of a completed upload gets the entity tag of Amazon S3.
   * @return the policy to apply.
   */
  public static MultipartUploadPolicy of(boolean compositeEtags) {
    return new MultipartUploadPolicy(UploadAssertions.MIN_PART_SIZE, compositeEtags);
  }

}
