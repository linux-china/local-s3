package com.robothy.s3.core.service;

import com.robothy.s3.core.service.locks.BucketLock;
import java.util.function.Supplier;

/**
 * Runs the operations of the services on a bucket under the lock of the bucket, and keeps the changes of a bucket
 * consistent with the metadata store and the storage of the service, if it has them.
 *
 * <p>The services call it explicitly, through the methods of {@linkplain BucketGuardApplicable}, e.g.
 * {@code withBucketReadLock(bucketName, () -> ...)}, so the lock and the persistence of an operation are visible where
 * the operation is implemented.
 *
 * <p>The locks are reentrant: an operation may call another operation on the same bucket, and a change nested in a
 * change of the same bucket runs within the outer one, which persists the bucket once. A read lock can't be upgraded,
 * so an operation that holds the read lock of a bucket must not call an operation that changes it.
 */
public interface BucketGuard {

  /**
   * How an operation changes a bucket, which tells how the bucket is persisted.
   */
  enum Change {

    /**
     * The bucket is created.
     */
    CREATE,

    /**
     * The bucket, or an object of it, is changed.
     */
    UPDATE,

    /**
     * The bucket is deleted.
     */
    DELETE
  }

  /**
   * Create a guard that only locks the buckets, for services that keep their buckets in memory.
   *
   * @return a new guard with its own locks.
   */
  static BucketGuard inMemory() {
    return new DefaultBucketGuard<>(BucketLock.create(), null, null, null, null);
  }

  /**
   * Run an operation that reads a bucket, under the read lock of the bucket.
   *
   * @param bucketName the bucket.
   * @param operation the operation.
   * @return the result of the operation.
   */
  <T> T read(String bucketName, Supplier<T> operation);

  /**
   * Run an operation that changes a bucket without persisting it, under the write lock of the bucket.
   *
   * @param bucketName the bucket.
   * @param operation the operation.
   * @return the result of the operation.
   */
  <T> T write(String bucketName, Supplier<T> operation);

  /**
   * Run an operation that changes a bucket, under the write lock of the bucket, and persist the bucket once it has
   * changed. If the operation fails, the objects that it stored are deleted, and the in-memory metadata of the bucket
   * is restored from the metadata store; the objects that it deleted are only deleted once the bucket is persisted.
   *
   * @param bucketName the bucket.
   * @param change how the operation changes the bucket.
   * @param operation the operation.
   * @return the result of the operation.
   */
  <T> T change(String bucketName, Change change, Supplier<T> operation);

}
