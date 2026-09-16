package com.robothy.s3.core.service;

import com.robothy.s3.core.event.S3ChangePublisher;
import com.robothy.s3.core.exception.LocalS3Exception;
import com.robothy.s3.core.exception.vectors.LocalS3VectorException;
import com.robothy.s3.core.model.internal.BucketChangeScope;
import com.robothy.s3.core.service.locks.BucketLock;
import com.robothy.s3.core.storage.MetadataStore;
import com.robothy.s3.core.storage.StorageTransactions;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The {@linkplain BucketGuard} of the services of a LocalS3 service: locks the buckets with a {@linkplain BucketLock},
 * and, if the service persists its buckets, persists a changed bucket to its metadata store within a transaction of
 * its storage.
 *
 * @param <M> the type of the metadata of a bucket, e.g. the metadata of an S3 bucket or of a vector bucket.
 */
public final class DefaultBucketGuard<M> implements BucketGuard {

  private final BucketLock bucketLock;

  private final Function<String, M> bucketMetadataLoader;

  private final MetadataStore<M> bucketMetaStore;

  private final StorageTransactions storage;

  private final Consumer<String> bucketMetadataReloader;

  /**
   * Held for reading by every operation of a bucket, around the lock of the bucket, and for writing by an
   * {@linkplain #exclusive} operation. Reentrant, so that an operation nested in another one doesn't wait for an
   * exclusive operation that waits for the outer one.
   */
  private final ReentrantReadWriteLock serviceLock = new ReentrantReadWriteLock();

  /**
   * The buckets that the current thread is changing, so that a change nested in a change of the same bucket runs
   * within the outer one, which persists the bucket once.
   */
  private final ThreadLocal<Set<String>> changingBuckets = ThreadLocal.withInitial(HashSet::new);

  private final S3ChangePublisher changePublisher = new S3ChangePublisher();

  /**
   * Create a guard.
   *
   * @param bucketLock the bucket locks, shared by all services of the same LocalS3 service.
   * @param bucketMetadataLoader loads the in-memory metadata of a bucket; {@code null} if nothing is persisted.
   * @param bucketMetaStore persists bucket metadata; {@code null} to not persist.
   * @param storage the storage of the service, whose deletions wait for the bucket to be persisted; {@code null} if
   *     data is deleted immediately.
   * @param bucketMetadataReloader replaces the in-memory metadata of a bucket with the persisted one;
   *     {@code null} to keep the in-memory metadata when an operation fails.
   */
  public DefaultBucketGuard(BucketLock bucketLock, Function<String, M> bucketMetadataLoader,
                            MetadataStore<M> bucketMetaStore, StorageTransactions storage,
                            Consumer<String> bucketMetadataReloader) {
    this.bucketLock = Objects.requireNonNull(bucketLock);
    if (bucketMetaStore != null && bucketMetadataLoader == null) {
      throw new IllegalArgumentException("A guard that persists buckets needs to load their metadata.");
    }
    this.bucketMetadataLoader = bucketMetadataLoader;
    this.bucketMetaStore = bucketMetaStore;
    this.storage = storage;
    this.bucketMetadataReloader = bucketMetadataReloader;
  }

  @Override
  public <T> T read(String bucketName, Supplier<T> operation) {
    return locked(bucketLock.readLock(bucketName), operation);
  }

  @Override
  public <T> T write(String bucketName, Supplier<T> operation) {
    return locked(bucketLock.writeLock(bucketName), operation);
  }

  @Override
  public <T> T change(String bucketName, Change change, Supplier<T> operation) {
    Set<String> changing = changingBuckets.get();
    if (changing.contains(bucketName)) {
      // The outer change holds the write lock, and persists the bucket once it is done.
      return operation.get();
    }
    // The changes that the operation publishes are delivered once the write lock is released.
    return changePublisher.withinChange(() -> write(bucketName, () -> {
      changing.add(bucketName);
      // Within the scope, the metadata of the bucket records the objects it hands out as changed, so that the store
      // only writes those. It spans the persistence too, which drains what the operation recorded.
      boolean ownsScope = BucketChangeScope.begin(bucketName);
      try {
        return Objects.isNull(bucketMetaStore) ? operation.get() : invokeAndPersist(bucketName, change, operation);
      } finally {
        if (ownsScope) {
          BucketChangeScope.end(bucketName);
        }
        changing.remove(bucketName);
        if (changing.isEmpty()) {
          changingBuckets.remove();
        }
      }
    }));
  }

  @Override
  public S3ChangePublisher changePublisher() {
    return changePublisher;
  }

  @Override
  public <T> T exclusive(Supplier<T> operation) {
    if (serviceLock.getReadHoldCount() > 0) {
      throw new IllegalStateException("An exclusive operation can't run within an operation of a bucket.");
    }
    serviceLock.writeLock().lock();
    try {
      return operation.get();
    } finally {
      serviceLock.writeLock().unlock();
    }
  }

  private <T> T locked(Lock lock, Supplier<T> operation) {
    serviceLock.readLock().lock();
    try {
      lock.lock();
      try {
        return operation.get();
      } finally {
        lock.unlock();
      }
    } finally {
      serviceLock.readLock().unlock();
    }
  }

  /**
   * Run an operation that changes a bucket and persist the bucket. Objects that the operation deletes are only deleted
   * after the metadata is persisted. If the operation or the persistence fails, the objects written by the operation
   * are deleted.
   *
   * <p>A {@linkplain LocalS3Exception} or a {@linkplain LocalS3VectorException} thrown by the operation rejects the
   * request, which services do before they change the metadata, so the in-memory metadata is kept. After any other
   * failure, or if the persistence fails, the in-memory metadata of the bucket is reloaded from the store, dropping the
   * changes that were made in memory only.
   */
  private <T> T invokeAndPersist(String bucketName, Change change, Supplier<T> operation) {
    boolean ownsTransaction = storage != null && storage.begin();
    T result;
    try {
      result = operation.get();
    } catch (RuntimeException | Error e) {
      rollback(ownsTransaction, bucketName, e, !isRejection(e));
      throw e;
    }

    try {
      persistBucket(bucketName, change);
    } catch (RuntimeException | Error e) {
      rollback(ownsTransaction, bucketName, e, true);
      throw e;
    }

    if (ownsTransaction) {
      storage.commit();
    }
    return result;
  }

  private static boolean isRejection(Throwable e) {
    return e instanceof LocalS3Exception || e instanceof LocalS3VectorException;
  }

  private void persistBucket(String bucketName, Change change) {
    switch (change) {
      case CREATE, UPDATE -> bucketMetaStore.store(bucketName, bucketMetadataLoader.apply(bucketName));
      case DELETE -> bucketMetaStore.delete(bucketName);
    }
  }

  private void rollback(boolean ownsTransaction, String bucketName, Throwable cause, boolean reloadMetadata) {
    if (ownsTransaction) {
      storage.rollback();
    }
    if (reloadMetadata && Objects.nonNull(bucketMetadataReloader) && Objects.nonNull(bucketName)) {
      try {
        bucketMetadataReloader.accept(bucketName);
      } catch (RuntimeException e) {
        cause.addSuppressed(e);
      }
    }
  }

}
