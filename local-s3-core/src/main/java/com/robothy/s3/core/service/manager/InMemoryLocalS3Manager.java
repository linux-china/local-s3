package com.robothy.s3.core.service.manager;

import com.robothy.s3.core.model.internal.LocalS3Metadata;
import com.robothy.s3.core.service.BucketGuard;
import com.robothy.s3.core.service.BucketService;
import com.robothy.s3.core.service.InMemoryBucketService;
import com.robothy.s3.core.service.InMemoryObjectService;
import com.robothy.s3.core.service.ObjectService;
import com.robothy.s3.core.service.loader.FileSystemS3MetadataLoader;
import com.robothy.s3.core.storage.Storage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.Optional;

/**
 * In memory implementation of {@linkplain LocalS3Manager}. Mange in memory
 * local-s3 related services.
 */
final class InMemoryLocalS3Manager implements LocalS3Manager {

  private final LocalS3Metadata s3Metadata;

  private final Storage storage;

  private final BucketService bucketService;

  private final ObjectService objectService;

  /**
   * Locks the buckets of this service, shared by its bucket and object services. The buckets are kept in memory, so
   * nothing is persisted.
   */
  private final BucketGuard bucketGuard = BucketGuard.inMemory();

  private static final InitialDataCache cache = new InitialDataCache();

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
    if (Objects.isNull(initialDataPath) || !Files.exists(initialDataPath)) {
      this.storage = Storage.createInMemory();
      this.s3Metadata = new LocalS3Metadata();
    } else {

      String absPath = initialDataPath.toAbsolutePath().toString();
      Path storagePath = Paths.get(initialDataPath.toAbsolutePath().toString(), STORAGE_DIRECTORY);
      if (enableInitialDataCache) {
        InitialDataCache.CacheValue cacheValue = cache.computeIfAbsent(absPath, key -> {
          LocalS3Metadata metadata = loadS3Metadata(initialDataPath);
          Storage persistent = Storage.createPersistent(storagePath);
          // Copies of the objects of the persistent storage reduce disk I/O, within the byte budget of the cache.
          return new InitialDataCache.CacheValue(metadata, Storage.createCopyOnAccess(persistent, cache));
        });
        this.storage = cacheValue.storage();
        this.s3Metadata = cacheValue.metadata();

      } else {
        this.storage = Storage.createLayered(Storage.createInMemory(), Storage.createPersistent(storagePath));
        this.s3Metadata = loadS3Metadata(initialDataPath);
      }

    }
    this.bucketService = createBucketService();
    this.objectService = createObjectService();
  }

  /**
   * Create an {@linkplain InMemoryLocalS3Manager} with initial data.
   *
   * @param initialMetadata initial metadata.
   * @param initialStorage initial storage.
   */
  InMemoryLocalS3Manager(LocalS3Metadata initialMetadata, Storage initialStorage) {
    this.s3Metadata = Optional.ofNullable(initialMetadata).orElseGet(LocalS3Metadata::new);
    this.storage = Optional.ofNullable(initialStorage).orElseGet(Storage::createInMemory);
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
