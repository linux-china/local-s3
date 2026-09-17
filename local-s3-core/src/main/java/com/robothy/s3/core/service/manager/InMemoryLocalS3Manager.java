package com.robothy.s3.core.service.manager;

import com.robothy.s3.core.model.internal.BucketMetadata;
import com.robothy.s3.core.model.internal.LocalS3Metadata;
import com.robothy.s3.core.service.BucketGuard;
import com.robothy.s3.core.service.BucketService;
import com.robothy.s3.core.service.DefaultBucketGuard;
import com.robothy.s3.core.service.DefaultBucketService;
import com.robothy.s3.core.service.DefaultObjectService;
import com.robothy.s3.core.service.ObjectService;
import com.robothy.s3.core.service.loader.FileSystemS3MetadataLoader;
import com.robothy.s3.core.service.locks.BucketLock;
import com.robothy.s3.core.model.internal.ObjectMetadataRef;
import com.robothy.s3.core.storage.MetadataStore;
import com.robothy.s3.core.storage.Storage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
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
   * Locks the buckets of this service, shared by its bucket and object services. The metadata in heap is the only copy,
   * so a change is not serialized anywhere: the store only forgets what the change recorded. A failed operation
   * therefore keeps whatever it changed in the metadata, which is the trade-off of a service for tests.
   */
  private final BucketGuard bucketGuard = new DefaultBucketGuard<>(BucketLock.create(),
      bucketName -> data.metadata().getBucketMetadata(bucketName).get(), new HeapBucketMetadataStore(), null, null);

  private static final InitialDataCache cache = new InitialDataCache();

  /**
   * The max number of bytes that the content stored in the heap takes, i.e. the objects and parts uploaded to the
   * service; the initial data read from the disk, and its copies in the {@linkplain #cache}, don't count.
   */
  private final long maxInMemoryBytes;

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
    this(initialDataPath, enableInitialDataCache, Long.MAX_VALUE);
  }

  /**
   * Create a {@linkplain InMemoryLocalS3Manager} with initial data, whose uploaded content takes at most
   * {@code maxInMemoryBytes} of heap.
   *
   * @param initialDataPath initial data path.
   * @param enableInitialDataCache whether the initial data is cached.
   * @param maxInMemoryBytes the max number of bytes of the content stored in the heap, positive.
   */
  InMemoryLocalS3Manager(Path initialDataPath, boolean enableInitialDataCache, long maxInMemoryBytes) {
    this(initialDataPath, enableInitialDataCache, cache, maxInMemoryBytes);
  }

  /**
   * Create a {@linkplain InMemoryLocalS3Manager} with initial data, cached in the given cache. For tests.
   *
   * @param initialDataPath initial data path.
   * @param enableInitialDataCache whether the initial data is cached.
   * @param cache the cache of the initial data.
   */
  InMemoryLocalS3Manager(Path initialDataPath, boolean enableInitialDataCache, InitialDataCache cache) {
    this(initialDataPath, enableInitialDataCache, cache, Long.MAX_VALUE);
  }

  private InMemoryLocalS3Manager(Path initialDataPath, boolean enableInitialDataCache, InitialDataCache cache,
      long maxInMemoryBytes) {
    if (maxInMemoryBytes <= 0) {
      throw new IllegalArgumentException("maxInMemoryBytes must be positive.");
    }
    this.maxInMemoryBytes = maxInMemoryBytes;
    this.initialData = () -> initialData(initialDataPath, enableInitialDataCache, cache);
    this.data = initialData.get();
    forgetChanges();
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
    this.maxInMemoryBytes = Long.MAX_VALUE;
    this.initialData = () -> new Data(new LocalS3Metadata(), Storage.createInMemory());
    this.data = new Data(Optional.ofNullable(initialMetadata).orElseGet(LocalS3Metadata::new),
        Optional.ofNullable(initialStorage).orElseGet(Storage::createInMemory));
    forgetChanges();
    this.bucketService = createBucketService();
    this.objectService = createObjectService();
  }

  private Data initialData(Path initialDataPath, boolean enableInitialDataCache, InitialDataCache cache) {
    if (Objects.isNull(initialDataPath) || !Files.exists(initialDataPath)) {
      return new Data(new LocalS3Metadata(), Storage.createInMemory(maxInMemoryBytes));
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
      return new Data(cacheValue.metadata(), cacheValue.storage(maxInMemoryBytes));
    }
    return new Data(loadS3Metadata(initialDataPath),
        Storage.createLayered(Storage.createInMemory(maxInMemoryBytes), Storage.createReadOnlyPersistent(storagePath)));
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
      forgetChanges();
      return null;
    });
  }

  /**
   * Forget the changes that the buckets the service starts with recorded while they were built, which no store will
   * write.
   */
  private void forgetChanges() {
    data.metadata().getBucketMetadataMap().values().forEach(HeapBucketMetadataStore::forgetChanges);
  }

  /**
   * The metadata store of a service whose metadata in heap is the only copy: storing a bucket writes nothing, and only
   * forgets the objects and uploads that its change recorded, so that they don't pile up. Reads answer the metadata in
   * heap.
   */
  private final class HeapBucketMetadataStore implements MetadataStore<BucketMetadata> {

    @Override
    public BucketMetadata fetch(String name) {
      return data.metadata().getBucketMetadata(name).orElse(null);
    }

    @Override
    public String store(String name, BucketMetadata bucketMetadata) {
      forgetChanges(bucketMetadata);
      return name;
    }

    @Override
    public boolean exists(String name) {
      return data.metadata().getBucketMetadata(name).isPresent();
    }

    @Override
    public void delete(String name) {
      // The bucket is gone from the metadata in heap already.
    }

    @Override
    public List<BucketMetadata> fetchAll() {
      return new ArrayList<>(data.metadata().getBucketMetadataMap().values());
    }

    static void forgetChanges(BucketMetadata bucketMetadata) {
      for (String key : bucketMetadata.drainChangedObjectKeys()) {
        ObjectMetadataRef ref = bucketMetadata.getObjectMap().get(key);
        if (ref != null) {
          ref.unpin();
        }
      }
      bucketMetadata.drainChangedUploadKeys();
    }
  }

  private BucketService createBucketService() {
    return DefaultBucketService.create(() -> data.metadata(), bucketGuard);
  }

  private ObjectService createObjectService() {
    return DefaultObjectService.create(() -> data.metadata(), () -> data.storage(), bucketGuard);
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
