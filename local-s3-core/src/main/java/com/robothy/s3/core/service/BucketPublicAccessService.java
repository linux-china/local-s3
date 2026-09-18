package com.robothy.s3.core.service;

import com.robothy.s3.core.util.BucketPublicAccess;

/**
 * Tells whether a bucket lets an anonymous request, i.e. one that carries no credentials, read an object, which is
 * what the static website endpoint of LocalS3 serves a bucket by. The rules are the ones of
 * {@linkplain BucketPublicAccess}.
 */
public interface BucketPublicAccessService extends LocalS3MetadataApplicable {

  /**
   * Whether an anonymous request may read an object of a bucket.
   *
   * <p>A bucket that doesn't exist allows nothing, rather than failing: the caller is deciding how to answer a request
   * that may name any bucket, and a bucket that isn't there is answered by the handler it reaches.
   *
   * @param bucketName the bucket name.
   * @param key the object key, which the resources of the bucket policy are matched against; {@code null} for a
   *     request that addresses the bucket itself.
   * @return {@code true} if the bucket is public for that key.
   */
  default boolean allowsAnonymousRead(String bucketName, String key) {
    return withBucketReadLock(bucketName, () -> localS3Metadata().getBucketMetadata(bucketName)
        .map(bucket -> BucketPublicAccess.allowsAnonymousRead(bucket, key))
        .orElse(false));
  }

}
