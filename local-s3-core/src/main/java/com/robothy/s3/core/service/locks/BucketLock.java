package com.robothy.s3.core.service.locks;

import java.util.concurrent.locks.Lock;

/**
 * Read-write locks of the buckets of one LocalS3 service.
 */
public interface BucketLock {

  /**
   * Create the bucket locks of a LocalS3 service. Each service has its own, so that services in the same JVM
   * don't block each other.
   *
   * @return new bucket locks.
   */
  static BucketLock create() {
    return new DefaultBucketLock(DefaultBucketLock.DEFAULT_STRIPES);
  }

  Lock readLock(String bucketName);

  Lock writeLock(String bucketName);

}
