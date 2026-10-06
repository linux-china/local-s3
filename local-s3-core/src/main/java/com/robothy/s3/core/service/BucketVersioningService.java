package com.robothy.s3.core.service;

import com.robothy.s3.core.model.Bucket;

public interface BucketVersioningService {

  Bucket setVersioningEnabled(String bucketName, boolean versioningEnabled);

  /**
   * The parts that are {@code null} leave the bucket as it is; see {@code docs/semantics.md#versioning}.
   */
  Bucket putVersioningConfiguration(String bucketName, Boolean versioningEnabled, Boolean mfaDeleteEnabled);

  /** {@code null} if versioning was never configured, {@code false} if it is suspended. */
  Boolean getVersioningEnabled(String bucketName);

}
