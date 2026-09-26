package com.robothy.s3.core.service;

import com.robothy.s3.core.model.Bucket;

public interface BucketVersioningService {

  /**
   * Set if versioning enabled of a given bucket.
   *
   * @param bucketName the bucket name.
   * @param versioningEnabled if enable versioning.
   * @return bucket info.
   */
  Bucket setVersioningEnabled(String bucketName, boolean versioningEnabled);

  /**
   * Put the versioning configuration of a bucket, like {@code PutBucketVersioning}: the parts of it that are
   * {@code null} leave the bucket as it is.
   *
   * @param bucketName the bucket name.
   * @param versioningEnabled {@code true} to enable versioning, {@code false} to suspend it; {@code null} to keep the
   *     versioning state of the bucket.
   * @param mfaDeleteEnabled the {@code MfaDelete} of the configuration, which is kept to be answered back only;
   *     {@code null} to keep the one of the bucket.
   * @return bucket info.
   */
  Bucket putVersioningConfiguration(String bucketName, Boolean versioningEnabled, Boolean mfaDeleteEnabled);

  /**
   * Get versioning enabled by bucket name.
   *
   * @param bucketName bucket name.
   * @return {@code null} - if not set versioning;
   * {@code true} - if versioning enabled;
   * {@code false} - if versioning disabled.
   */
  Boolean getVersioningEnabled(String bucketName);

}
