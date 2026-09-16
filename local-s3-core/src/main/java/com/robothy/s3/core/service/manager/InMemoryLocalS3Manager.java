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
import com.robothy.s3.core.storage.Storage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * In memory implementation of {@linkplain LocalS3Manager}. Mange in memory
 * local-s3 related services.
 */
final class InMemoryLocalS3Manager implements LocalS3Manager {

  /**
   * The data of the service. Replaced as a whole by {@linkplain #reset()}, within an exclusive operation of the
   * {@linkplain #bucketGuard}, so that an operation of a bucket sees either the old data or the new one.
   */
  private volatile Data data;

  /**
   * Creates the data that the service starts with, and that {@linkplain #reset()} restores.
   */
  private final Supplier<Data> initialData;

  private final BucketService bucketService;

  private final ObjectService objectService;

  /**
   * The key-value store of the service, which keeps the metadata of its buckets in memory and writes no file.
   */
  private final LocalS3Store localS3Store = LocalS3Store.inMemory();

  private final MetadataStore<BucketMetadata> bucketMetaStore = MVStoreBucketMetadataStore.create(localS3Store);

  /**
   * Locks the buckets of this service, shared by its bucket and object services, and writes a changed bucket to the
   * in-memory store.
   */
  private final BucketGuard bucketGuard = new DefaultBucketGuard<>(BucketLock.create(),
      bucketName -> data.metadata().getBucketMetadata(bucketName).get(), bucketMetaStore, null,
      this::reloadBucketMetadata);

  private static final InitialDataCache cache = new InitialDataCache();

  /**
   * The metadata and the storage of the service.
   */
  private record Data(LocalS3Metadata metadata, Storage storage) {
  }

  /**
   * Create a {@linkplain InMemoryLocalS3Manager} with initial data.
   * @param initialDataPath initial data path.
   */
  InMemoryLocalS3Manager(Path initialDataPath, boolean enableInitialDataCache) {
    this(initialDataPath, enableInitialDataCache, cache);
  }

  /**
   * Create a {@linkplain InMemoryLocalS3Manager} with initial data, cached in the given cache. For tests.
   *
   * @param initialDataPath initial data path.
   * @param enableInitialDataCache whether the initial data is cached.
   * @param cache the cache of the initial data.
   */
  InMemoryLocalS3Manager(Path initialDataPath, boolean enableInitialDataCache, InitialDataCache cache) {
    this.initialData = () -> initialData(initialDataPath, enableInitialDataCache, cache);
    this.data = initialData.get();
    seedStore();
    this.bucketService = createBucketService();
    this.objectService = createObjectService();
  }

  /**
   * Create an {@linkplain InMemoryLocalS3Manager} with initial data. A {@linkplain #reset()} starts over without it,
   * since the given data has been changed by then.
   *
   * @param initialMetadata initial metadata.
   * @param initialStorage initial storage.
   */
  InMemoryLocalS3Manager(LocalS3Metadata initialMetadata, Storage initialStorage) {
    this.initialData = () -> new Data(new LocalS3Metadata(), Storage.createInMemory());
    this.data = new Data(Optional.ofNullable(initialMetadata).orElseGet(LocalS3Metadata::new),
        Optional.ofNullable(initialStorage).orElseGet(Storage::createInMemory));
    seedStore();
    this.bucketService = createBucketService();
    this.objectService = createObjectService();
  }

  private Data initialData(Path initialDataPath, boolean enableInitialDataCache, InitialDataCache cache) {
    if (Objects.isNull(initialDataPath) || !Files.exists(initialDataPath)) {
      return new Data(new LocalS3Metadata(), Storage.createInMemory());
    }

    String absPath = initialDataPath.toAbsolutePath().toString();
    Path storagePath = Paths.get(initialDataPath.toAbsolutePath().toString(), STORAGE_DIRECTORY);
    if (enableInitialDataCache) {
      InitialDataCache.CacheValue cacheValue = cache.computeIfAbsent(absPath, key -> {
        LocalS3Metadata metadata = loadS3Metadata(initialDataPath);
        Storage persistent = Storage.createReadOnlyPersistent(storagePath);
        // Copies of the objects of the persistent storage reduce disk I/O, within the byte budget of the cache.
        return new InitialDataCache.CacheValue(metadata, Storage.createCopyOnAccess(persistent, cache));
      });
      // Each call makes a copy of the metadata and a storage of its own over the cached one.
      return new Data(cacheValue.metadata(), cacheValue.storage());
    }
    return new Data(loadS3Metadata(initialDataPath),
        Storage.createLayered(Storage.createInMemory(), Storage.createReadOnlyPersistent(storagePath)));
  }

  @Override
  public BucketService bucketService() {
    return bucketService;
  }

  @Override
  public ObjectService objectService() {
    return objectService;
  }

  /**
   * Replace the data with the data that the service started with: a new copy of its initial data, or no data. The
   * initial data is loaded again, unless it is cached.
   */
  @Override
  public void reset() {
    bucketGuard.exclusive(() -> {
      data = initialData.get();
      clearStore();
      seedStore();
      return null;
    });
  }

  /**
   * Write the buckets that the service starts with to its store, which the operations then change.
   */
  private void seedStore() {
    for (BucketMetadata bucketMetadata : data.metadata().getBucketMetadataMap().values()) {
      bucketMetadata.markAllChanged();
      bucketMetaStore.store(bucketMetadata.getBucketName(), bucketMetadata);
    }
  }

  /**
   * Drop everything the store holds, so that the data of a {@linkplain #reset()} replaces it.
   */
  private void clearStore() {
    for (String mapName : new ArrayList<>(localS3Store.store().getMapNames())) {
      localS3Store.store().removeMap(localS3Store.store().openMap(mapName));
    }
  }

  /**
   * Replace the in-memory metadata of a bucket with the stored one, which drops the changes that a failed operation
   * made in the metadata only.
   */
  private void reloadBucketMetadata(String bucketName) {
    Map<String, BucketMetadata> buckets = data.metadata().getBucketMetadataMap();
    if (bucketMetaStore.exists(bucketName)) {
      buckets.put(bucketName, bucketMetaStore.fetch(bucketName));
    } else {
      buckets.remove(bucketName);
    }
  }

  private BucketService createBucketService() {
    return InMemoryBucketService.create(() -> data.metadata(), bucketGuard);
  }

  private ObjectService createObjectService() {
    return InMemoryObjectService.create(() -> data.metadata(), () -> data.storage(), bucketGuard);
  }

  private LocalS3Metadata loadS3Metadata(Path initialDataDirectory) {
    if (Objects.isNull(initialDataDirectory)) {
      return new LocalS3Metadata();
    }

    if (!Files.exists(initialDataDirectory)) {
      throw new IllegalArgumentException(initialDataDirectory.toAbsolutePath() + " not found.");
    }

    return FileSystemS3MetadataLoader.create().load(initialDataDirectory);
  }

  /**
   * Drop the initial data cached for every data path, releasing the heap it holds.
   */
  static void clearInitialDataCache() {
    cache.clear();
  }

  /**
   * Change the limits of the initial data cache.
   *
   * @param maxEntries the max number of data paths, positive.
   * @param maxBytes the max number of bytes that the copies of objects take, not negative.
   */
  static void configureInitialDataCache(int maxEntries, long maxBytes) {
    cache.setLimits(maxEntries, maxBytes);
  }

  /**
   * The number of data paths whose initial data is cached. For tests.
   */
  static int initialDataCacheSize() {
    return cache.size();
  }

  /**
   * The initial data cache shared by the managers of the JVM. For tests.
   */
  static InitialDataCache initialDataCache() {
    return cache;
  }

}
