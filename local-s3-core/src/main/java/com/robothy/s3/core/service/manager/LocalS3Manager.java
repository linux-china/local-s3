package com.robothy.s3.core.service.manager;

import com.robothy.s3.core.service.BucketService;
import com.robothy.s3.core.service.ObjectService;
import java.nio.file.Path;

/**
 * A manager abstraction that manages data and services of local-s3.
 */
public interface LocalS3Manager {

  /**
   * Relative path of storage.
   */
   String STORAGE_DIRECTORY = ".storage";

  /**
   * Create an in-memory implementation of {@linkplain LocalS3Manager}.
   *
   * @return an instance of in-memory implementation.
   */
  static LocalS3Manager createInMemoryS3Manager() {
    return new InMemoryLocalS3Manager(null, false);
  }

  static LocalS3Manager createInMemoryS3Manager(Path dataDirectory, boolean enabledInitialDataCache) {
    return new InMemoryLocalS3Manager(dataDirectory, enabledInitialDataCache);
  }

  /**
   * Drop the initial data that the {@linkplain #createInMemoryS3Manager(Path, boolean) in-memory managers}
   * cached, releasing the heap it holds. The cache keeps the data of a bounded number of data paths; call
   * this to release them earlier, e.g. when a test class that used a data path has finished. The data of a
   * dropped path is loaded again when a manager is created for it, so this only costs the loading.
   */
  static void clearInitialDataCache() {
    InMemoryLocalS3Manager.clearInitialDataCache();
  }

  /**
   * Change the limits of the initial data that the {@linkplain #createInMemoryS3Manager(Path, boolean) in-memory
   * managers} of the JVM cache: the number of data paths whose metadata is kept, and the number of bytes of heap that
   * the copies of the objects read from them take. A copy that would exceed the bytes drops the least recently used
   * data paths first; an object that there is still no room for is read from the disk instead. The limits default to
   * 1024 data paths and a quarter of the max heap, or to the environment variables or system properties
   * {@code LOCAL_S3_INITIAL_DATA_CACHE_MAX_ENTRIES} and {@code LOCAL_S3_INITIAL_DATA_CACHE_MAX_BYTES}, e.g.
   * {@code 512m}.
   *
   * @param maxEntries the max number of data paths, positive.
   * @param maxBytes the max number of bytes that the copies of objects take, not negative; {@code 0} copies nothing.
   */
  static void configureInitialDataCache(int maxEntries, long maxBytes) {
    InMemoryLocalS3Manager.configureInitialDataCache(maxEntries, maxBytes);
  }

  /**
   * Create a file system implementation of {@linkplain LocalS3Manager}.
   *
   * @return an instance of file system implementation.
   */
  static LocalS3Manager createFileSystemS3Manager(Path dataDirectory) {
    return new FileSystemLocalS3Manager(dataDirectory);
  }

  /**
   * Get a bucket service.
   *
   * @return a bucket service.
   */
  BucketService bucketService();

  /**
   * Get an object service.
   *
   * @return an object service.
   */
  ObjectService objectService();

  /**
   * Count the data of the service.
   *
   * @return the statistics of the data.
   */
  default ObjectStatistics statistics() {
    return ObjectStatistics.collect(bucketService());
  }

  /**
   * Replace the data of the service with the data it started with: none, or the initial data of its data path. The
   * operations in progress are finished first, and the operations that start meanwhile wait for the reset. The
   * services of the manager keep working, on the new data.
   *
   * @throws UnsupportedOperationException if the manager persists its data, which a reset would delete.
   */
  default void reset() {
    throw new UnsupportedOperationException("Only the data of an IN_MEMORY service can be reset.");
  }

}
