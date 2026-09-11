package com.robothy.s3.core.service.manager;

import com.robothy.s3.core.annotations.BucketChanged;
import com.robothy.s3.core.annotations.BucketReadLock;
import com.robothy.s3.core.annotations.BucketWriteLock;
import com.robothy.s3.core.exception.LocalS3Exception;
import com.robothy.s3.core.service.locks.BucketLock;
import com.robothy.s3.core.storage.MetadataStore;
import com.robothy.s3.core.storage.TransactionalStorage;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;


public final class LocalS3ServicesInvocationHandler<T> implements InvocationHandler {

  private final Object proxy;

  private final MetadataStore<T> bucketMetaStore;

  private final Function<String, T> bucketMetadataLoader;

  private final TransactionalStorage storage;

  private final Consumer<String> bucketMetadataReloader;

  public LocalS3ServicesInvocationHandler(Object proxy, Function<String, T> bucketMetadataLoader, MetadataStore<T> bucketMetaStore) {
    this(proxy, bucketMetadataLoader, bucketMetaStore, null, null);
  }

  /**
   * Create an invocation handler that keeps the in-memory metadata, the metadata store and the storage
   * consistent when a bucket changing method fails.
   *
   * @param proxy the service to invoke.
   * @param bucketMetadataLoader loads the in-memory metadata of a bucket.
   * @param bucketMetaStore persists bucket metadata; {@code null} to not persist.
   * @param storage the storage of the service; {@code null} if objects are deleted immediately.
   * @param bucketMetadataReloader replaces the in-memory metadata of a bucket with the persisted one;
   *     {@code null} to keep the in-memory metadata when a method fails.
   */
  public LocalS3ServicesInvocationHandler(Object proxy, Function<String, T> bucketMetadataLoader,
                                          MetadataStore<T> bucketMetaStore, TransactionalStorage storage,
                                          Consumer<String> bucketMetadataReloader) {
    this.proxy = proxy;
    this.bucketMetaStore = bucketMetaStore;
    this.bucketMetadataLoader = bucketMetadataLoader;
    this.storage = storage;
    this.bucketMetadataReloader = bucketMetadataReloader;
  }

  @Override
  public Object invoke(Object __, Method method, Object[] args) throws Throwable {
    BucketChanged bucketChanged = method.getDeclaredAnnotation(BucketChanged.class);
    boolean isReadBucket = Objects.nonNull(method.getDeclaredAnnotation(BucketReadLock.class));
    boolean isWriteBucket = Objects.nonNull(method.getDeclaredAnnotation(BucketWriteLock.class));

    lockIfNeeded(args, isReadBucket, isWriteBucket);
    try {
      if (Objects.isNull(bucketChanged) || Objects.isNull(bucketMetaStore)) {
        return invokeService(method, args);
      }
      return invokeAndPersist(method, args, bucketChanged);
    } finally {
      unlockIfNeeded(args, isReadBucket, isWriteBucket);
    }
  }

  private Object invokeService(Method method, Object[] args) throws Throwable {
    try {
      return method.invoke(proxy, args);
    } catch (InvocationTargetException e) {
      throw e.getCause();
    }
  }

  /**
   * Invoke a bucket changing method and persist the bucket metadata. Objects that the method deletes
   * are only deleted after the metadata is persisted. If the method or the persistence fails, the
   * objects written by the method are deleted.
   *
   * <p>A {@linkplain LocalS3Exception} thrown by the method rejects the request, which services do
   * before they change the metadata, so the in-memory metadata is kept. After any other failure, or if
   * the persistence fails, the in-memory metadata of the bucket is reloaded from the store, dropping
   * the changes that were made in memory only.
   */
  private Object invokeAndPersist(Method method, Object[] args, BucketChanged bucketChanged) throws Throwable {
    boolean ownsTransaction = storage != null && storage.begin();
    Object result;
    try {
      result = invokeService(method, args);
    } catch (Throwable e) {
      rollback(ownsTransaction, args, e, !(e instanceof LocalS3Exception));
      throw e;
    }

    try {
      persistBucketIfNeeded(args, bucketChanged);
    } catch (Throwable e) {
      rollback(ownsTransaction, args, e, true);
      throw e;
    }

    if (ownsTransaction) {
      storage.commit();
    }
    return result;
  }

  private void rollback(boolean ownsTransaction, Object[] args, Throwable cause, boolean reloadMetadata) {
    if (ownsTransaction) {
      storage.rollback();
    }
    if (reloadMetadata) {
      reloadBucketMetadata((String) args[0], cause);
    }
  }

  private void reloadBucketMetadata(String bucketName, Throwable cause) {
    if (Objects.isNull(bucketMetadataReloader) || Objects.isNull(bucketName)) {
      return;
    }

    try {
      bucketMetadataReloader.accept(bucketName);
    } catch (RuntimeException e) {
      cause.addSuppressed(e);
    }
  }

  void lockIfNeeded(Object[] args, boolean isRead, boolean isWrite) {
    if (isRead || isWrite) {
      String bucketName = (String) args[0];
      BucketLock lock = BucketLock.getInstance();
      if (isRead) {
        lock.readLock(bucketName).lock();
      }

      if (isWrite) {
        lock.writeLock(bucketName).lock();
      }
    }
  }

  void unlockIfNeeded(Object[] args, boolean isRead, boolean isWrite) {
    if (isRead || isWrite) {
      String bucketName = (String) args[0];
      BucketLock lock = BucketLock.getInstance();
      if (isRead) {
        lock.readLock(bucketName).unlock();
      }

      if (isWrite) {
        lock.writeLock(bucketName).unlock();
      }
    }
  }

  void persistBucketIfNeeded(Object[] args, BucketChanged bucketChanged) {
    if (Objects.isNull(bucketChanged) || Objects.isNull(bucketMetaStore)) {
      return;
    }

    String bucketName = (String) args[0];
    switch (bucketChanged.type()) {
      case UPDATE:
      case CREATE:
        bucketMetaStore.store(bucketName, bucketMetadataLoader.apply(bucketName));
        break;
      case DELETE:
        bucketMetaStore.delete(bucketName);
        break;
    }
  }


}
