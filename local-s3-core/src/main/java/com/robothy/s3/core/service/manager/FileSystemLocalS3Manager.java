package com.robothy.s3.core.service.manager;

import com.robothy.s3.core.model.internal.BucketMetadata;
import com.robothy.s3.core.model.internal.LocalS3Metadata;
import com.robothy.s3.core.service.BucketGuard;
import com.robothy.s3.core.service.BucketService;
import com.robothy.s3.core.service.DefaultBucketGuard;
import com.robothy.s3.core.service.InMemoryBucketService;
import com.robothy.s3.core.service.InMemoryObjectService;
import com.robothy.s3.core.service.ObjectService;
import com.robothy.s3.core.service.loader.FileSystemS3MetadataLoader;
import com.robothy.s3.core.service.locks.BucketLock;
import com.robothy.s3.core.storage.LocalS3Store;
import com.robothy.s3.core.storage.MVStoreBucketMetadataStore;
import com.robothy.s3.core.storage.MetadataStore;
import com.robothy.s3.core.storage.PersistencePolicy;
import com.robothy.s3.core.storage.Storage;
import com.robothy.s3.core.storage.TransactionalStorage;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Objects;

final class FileSystemLocalS3Manager implements LocalS3Manager {

  private final LocalS3Metadata s3Metadata;

  /**
   * The key-value store of the service, which holds the metadata of every bucket in one file.
   */
  private final LocalS3Store localS3Store;

  private final MetadataStore<BucketMetadata> bucketMetaStore;

  private final TransactionalStorage storage;

  private final BucketService bucketService;

  private final ObjectService objectService;

  /**
   * Locks and persists the buckets of this service, shared by its bucket and object services.
   */
  private final BucketGuard bucketGuard;

  FileSystemLocalS3Manager(Path dataDirectory, PersistencePolicy persistencePolicy) {
    Objects.requireNonNull(dataDirectory, "Data directory is required to create a persistent LocalS3 service.");
    Objects.requireNonNull(persistencePolicy, "persistencePolicy");
    // One store per service, which holds the metadata and stays open: it is both loaded from and written to.
    this.localS3Store = LocalS3Store.persistent(dataDirectory, persistencePolicy);
    this.bucketMetaStore = MVStoreBucketMetadataStore.create(localS3Store);
    this.s3Metadata = FileSystemS3MetadataLoader.create().load(bucketMetaStore);
    this.storage = new TransactionalStorage(
        Storage.createPersistent(Paths.get(dataDirectory.toAbsolutePath().toString(), STORAGE_DIRECTORY)));
    this.bucketGuard = new DefaultBucketGuard<>(BucketLock.create(),
        bucketName -> s3Metadata.getBucketMetadata(bucketName).get(), bucketMetaStore, storage,
        this::reloadBucketMetadata);
    this.bucketService = createBucketService();
    this.objectService = createObjectService();
  }

  @Override
  public BucketService bucketService() {
    return bucketService;
  }

  @Override
  public ObjectService objectService() {
    return objectService;
  }

  private BucketService createBucketService() {
    return InMemoryBucketService.create(s3Metadata, bucketGuard);
  }

  private ObjectService createObjectService() {
    return InMemoryObjectService.create(s3Metadata, storage, bucketGuard);
  }

  /**
   * Close the store of the service, writing what it still holds. The manager can't be used afterwards.
   */
  @Override
  public void close() {
    localS3Store.close();
  }

  /**
   * Replace the in-memory metadata of a bucket with the persisted one, which drops the changes that a
   * failed operation made in memory only.
   */
  private void reloadBucketMetadata(String bucketName) {
    Map<String, BucketMetadata> buckets = s3Metadata.getBucketMetadataMap();
    if (bucketMetaStore.exists(bucketName)) {
      buckets.put(bucketName, bucketMetaStore.fetch(bucketName));
    } else {
      buckets.remove(bucketName);
    }
  }

}
