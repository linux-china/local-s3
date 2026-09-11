package com.robothy.s3.core.service.locks;

import java.util.Objects;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * The bucket locks of one LocalS3 service. Buckets share a fixed number of read-write locks, chosen by the hash of
 * the bucket name, so that the locks take bounded memory however many buckets are created and deleted.
 *
 * <p>Buckets that share a lock may wait for each other. That is safe, since an operation locks a single bucket.
 */
class DefaultBucketLock implements BucketLock {

  static final int DEFAULT_STRIPES = 64;

  private final ReadWriteLock[] locks;

  DefaultBucketLock(int stripes) {
    if (stripes <= 0) {
      throw new IllegalArgumentException("stripes must be positive.");
    }
    this.locks = new ReadWriteLock[stripes];
    for (int i = 0; i < stripes; i++) {
      locks[i] = new ReentrantReadWriteLock();
    }
  }

  @Override
  public Lock readLock(String bucketName) {
    return getLock(bucketName).readLock();
  }

  @Override
  public Lock writeLock(String bucketName) {
    return getLock(bucketName).writeLock();
  }

  private ReadWriteLock getLock(String bucketName) {
    // A null bucket name is rejected by the service, not by the lock.
    return locks[Math.floorMod(Objects.hashCode(bucketName), locks.length)];
  }

}
