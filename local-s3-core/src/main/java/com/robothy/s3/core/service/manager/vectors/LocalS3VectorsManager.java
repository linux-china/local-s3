package com.robothy.s3.core.service.manager.vectors;

import com.robothy.s3.core.service.s3vectors.S3VectorsService;
import java.nio.file.Path;

public interface LocalS3VectorsManager {

  /**
   * The directory that holds the vectors of a data directory, relative to the data directory.
   *
   * <p>It holds the data files of the vectors only. The metadata of the vector buckets is kept in the
   * {@link com.robothy.s3.core.storage.LocalS3Store} of the data directory, together with the metadata of its S3
   * buckets, so that a data directory has one metadata file and one consistent point rather than two.
   */
  String VECTORS_DIRECTORY = "vectors";

  /**
   * The directory of the vector data files, relative to {@value #VECTORS_DIRECTORY}.
   */
  String VECTOR_STORAGE_DIRECTORY = ".storage";

  /**
   * The directory that holds the data files of the vectors of a data directory.
   *
   * @param dataPath the data directory.
   * @return {@code dataPath/}{@value #VECTORS_DIRECTORY}{@code /}{@value #VECTOR_STORAGE_DIRECTORY}.
   */
  static Path vectorStorageDirectory(Path dataPath) {
    return dataPath.resolve(VECTORS_DIRECTORY).resolve(VECTOR_STORAGE_DIRECTORY);
  }

  static LocalS3VectorsManager createInMemory() {
    return new InMemoryLocalS3VectorsManager(null);
  }

  /**
   * Create an in-memory manager that starts from the vectors of a data directory, like an {@code IN_MEMORY} service
   * starts from the objects of its data path. The vector buckets, indexes and vectors of the directory are loaded, and
   * the changes of the service are kept in memory: the directory is never changed.
   *
   * @param initialDataDirectory the data directory of the initial data; {@code null}, or a directory that holds no
   *     store, to start without vectors.
   * @return an in-memory manager.
   */
  static LocalS3VectorsManager createInMemory(Path initialDataDirectory) {
    return new InMemoryLocalS3VectorsManager(initialDataDirectory);
  }

  /**
   * Create a manager that keeps the vectors in a data directory, writing the metadata of the vector buckets to the
   * store of the directory, which it shares with the S3 buckets of the same directory.
   *
   * @param dataDirectory the data directory, the one that the S3 buckets are kept in.
   * @return a persistent manager.
   */
  static LocalS3VectorsManager createFileSystem(Path dataDirectory) {
    return new FileSystemLocalS3VectorsManager(dataDirectory);
  }

  S3VectorsService s3VectorsService();

  /**
   * Count the data of the service.
   *
   * @return the statistics of the data.
   */
  default VectorStatistics statistics() {
    return VectorStatistics.collect(s3VectorsService());
  }

  /**
   * Close the resources of the manager, e.g. the hold it has on the store that the metadata of its vector buckets is
   * written to, which closes the store once the S3 buckets of the same data directory have released it too. The
   * service of the manager can't be used afterwards. A manager that holds nothing to close does nothing.
   */
  default void close() {
  }

  /**
   * Replace the vectors of the service with the vectors it started with: none, or the initial data of its data path.
   * The operations in progress are finished first, and the operations that start meanwhile wait for the reset.
   *
   * @throws UnsupportedOperationException if the manager persists its data, which a reset would delete.
   */
  default void reset() {
    throw new UnsupportedOperationException("Only the vectors of an IN_MEMORY service can be reset.");
  }

}
