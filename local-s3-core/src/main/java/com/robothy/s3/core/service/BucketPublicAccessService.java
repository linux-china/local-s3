package com.robothy.s3.core.service;

import com.robothy.s3.core.util.BucketPublicAccess;

/**
 * What the static website endpoint serves a bucket by; see {@code docs/semantics.md#which-buckets-are-public}.
 */
public interface BucketPublicAccessService extends LocalS3MetadataApplicable {

  /**
   * A bucket that doesn't exist allows nothing rather than failing: the request may name any bucket, and one that
   * isn't there is answered by the handler it reaches.
   */
  default boolean allowsAnonymousRead(String bucketName, String key) {
    return withBucketReadLock(bucketName, () -> localS3Metadata().getBucketMetadata(bucketName)
        .map(bucket -> BucketPublicAccess.allowsAnonymousRead(bucket, key))
        .orElse(false));
  }

}
