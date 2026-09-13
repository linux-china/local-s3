package com.robothy.s3.core.service;

import java.util.function.Supplier;

/**
 * Represent services whose operations on a bucket run within a {@linkplain BucketGuard}.
 */
public interface BucketGuardApplicable {

  /**
   * The guard of the buckets of the service, shared by the services of the same LocalS3 service.
   *
   * @return the guard.
   */
  BucketGuard bucketGuard();

  /**
   * Run an operation that reads a bucket under the read lock of the bucket.
   *
   * @see BucketGuard#read
   */
  default <T> T withBucketReadLock(String bucketName, Supplier<T> operation) {
    return bucketGuard().read(bucketName, operation);
  }

  /**
   * Run an operation that changes a bucket without persisting it, under the write lock of the bucket.
   *
   * @see BucketGuard#write
   */
  default <T> T withBucketWriteLock(String bucketName, Supplier<T> operation) {
    return bucketGuard().write(bucketName, operation);
  }

  /**
   * Run an operation that changes a bucket, or an object of it, under the write lock of the bucket, and persist the
   * bucket.
   *
   * @see BucketGuard#change
   */
  default <T> T changeBucket(String bucketName, Supplier<T> operation) {
    return bucketGuard().change(bucketName, BucketGuard.Change.UPDATE, operation);
  }

  /**
   * Run an operation that changes a bucket, and persist the bucket, like {@linkplain #changeBucket(String, Supplier)}.
   */
  default void changeBucket(String bucketName, Runnable operation) {
    changeBucket(bucketName, () -> {
      operation.run();
      return null;
    });
  }

  /**
   * Run an operation that creates, changes or deletes a bucket, under the write lock of the bucket, and persist the
   * change.
   *
   * @see BucketGuard#change
   */
  default <T> T changeBucket(String bucketName, BucketGuard.Change change, Supplier<T> operation) {
    return bucketGuard().change(bucketName, change, operation);
  }

  /**
   * Run an operation that creates, changes or deletes a bucket, and persist the change, like
   * {@linkplain #changeBucket(String, BucketGuard.Change, Supplier)}.
   */
  default void changeBucket(String bucketName, BucketGuard.Change change, Runnable operation) {
    changeBucket(bucketName, change, () -> {
      operation.run();
      return null;
    });
  }

  /**
   * Run an operation that changes a bucket without persisting it, like
   * {@linkplain #withBucketWriteLock(String, Supplier)}.
   */
  default void withBucketWriteLock(String bucketName, Runnable operation) {
    withBucketWriteLock(bucketName, () -> {
      operation.run();
      return null;
    });
  }

}
