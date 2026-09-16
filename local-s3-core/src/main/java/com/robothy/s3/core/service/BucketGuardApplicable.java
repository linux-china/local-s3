package com.robothy.s3.core.service;

import com.robothy.s3.core.event.S3Change;
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

  /**
   * Publish a change that the operation made, which is delivered once the change of the bucket that it runs in is
   * committed.
   *
   * @see com.robothy.s3.core.event.S3ChangePublisher#publish
   */
  default void publishChange(S3Change change) {
    bucketGuard().changePublisher().publish(change);
  }

  /**
   * Run an action that changes buckets, and deliver the changes it publishes once the action has ended, rather than once
   * each change it makes has ended. Within the action, a change that returns has been committed, and one that throws
   * hasn't: a failure to deliver its changes, e.g. an {@linkplain Error} that a listener throws on the thread of the
   * change, reaches the caller of this method after the action rather than the action itself. So an action that cleans
   * up after a failed change, e.g. deletes the content that it stored for it, doesn't clean up after a committed one.
   *
   * @param action the action.
   * @return the result of the action.
   * @see com.robothy.s3.core.event.S3ChangePublisher#withinChange
   */
  default <T> T deliverChangesAfter(Supplier<T> action) {
    return bucketGuard().changePublisher().withinChange(action);
  }

  /**
   * Run an operation as a part of another one, which the changes that it publishes name, e.g. {@code CopyObject}.
   *
   * @see com.robothy.s3.core.event.S3ChangePublisher#asOperation
   */
  default <T> T asOperation(String operationName, Supplier<T> action) {
    return bucketGuard().changePublisher().asOperation(operationName, action);
  }

}
