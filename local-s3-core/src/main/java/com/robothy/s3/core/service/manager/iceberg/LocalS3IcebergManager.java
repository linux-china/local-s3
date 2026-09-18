package com.robothy.s3.core.service.manager.iceberg;

import com.robothy.s3.core.iceberg.IcebergCatalogService;
import com.robothy.s3.core.service.BucketService;
import com.robothy.s3.core.service.ObjectService;
import com.robothy.s3.core.storage.LocalS3Store;
import com.robothy.s3.core.storage.PersistencePolicy;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * Owns the Iceberg REST catalog of a LocalS3 service: its namespaces and its table pointers, and the hold on the
 * store they are kept in.
 *
 * <p>It is the counterpart of {@link com.robothy.s3.core.service.manager.vectors.LocalS3VectorsManager} for the
 * catalog, and it keeps its records in the same place: the one {@linkplain LocalS3Store} of the service, beside its
 * S3 buckets and its vector buckets, so that a data directory still holds a single file that a copy of it is a
 * consistent point of.
 *
 * <p>The metadata files of the tables aren't kept here at all. They are ordinary objects of the warehouse bucket,
 * written through the S3 services of the same service, so a {@code PERSISTENCE} service persists its tables the way it
 * persists everything else, and an {@code IN_MEMORY} one keeps them in memory and loses them when it stops. Nothing
 * had to be built for that: an Iceberg table on LocalS3 is objects in a bucket plus one pointer.
 */
public interface LocalS3IcebergManager {

  /**
   * Create a catalog that keeps its records in memory, optionally starting from the ones of a data directory.
   *
   * <p>The records of the directory are copied, and the directory is never written: an {@code IN_MEMORY} service that
   * starts from a data path serves the tables that path holds and keeps its own changes in memory, like it does with
   * the objects and the vectors of that path.
   *
   * @param initialDataDirectory the data directory of the initial records; {@code null}, or a directory that holds no
   *     store, to start with an empty catalog.
   * @param bucketService the buckets of the service.
   * @param objectService the objects of the service, which the metadata files of the tables are.
   * @param warehouse the warehouse location, e.g. {@code s3://warehouse/}.
   * @return an in-memory manager.
   */
  static LocalS3IcebergManager createInMemory(@Nullable Path initialDataDirectory, BucketService bucketService,
                                              ObjectService objectService, String warehouse) {
    return new InMemoryLocalS3IcebergManager(initialDataDirectory, bucketService, objectService, warehouse);
  }

  /**
   * Create a catalog that keeps its records in a data directory, in the store that the S3 buckets of the same
   * directory are kept in.
   *
   * @param dataDirectory the data directory, the one that the S3 buckets are kept in.
   * @param persistencePolicy when the changes reach the disk, shared with the S3 buckets of the same directory, which
   *     hold the same store.
   * @param bucketService the buckets of the service.
   * @param objectService the objects of the service, which the metadata files of the tables are.
   * @param warehouse the warehouse location, e.g. {@code s3://warehouse/}.
   * @return a persistent manager.
   */
  static LocalS3IcebergManager createFileSystem(Path dataDirectory, PersistencePolicy persistencePolicy,
                                                BucketService bucketService, ObjectService objectService,
                                                String warehouse) {
    return new FileSystemLocalS3IcebergManager(dataDirectory, persistencePolicy, bucketService, objectService,
        warehouse);
  }

  /**
   * The catalog that the REST endpoints answer through.
   *
   * @return the catalog service.
   */
  IcebergCatalogService icebergCatalogService();

  /**
   * Release the hold this manager has on the store of its data directory, which closes the store once the other
   * managers of the same directory have released it too. A manager that holds nothing does nothing.
   */
  default void close() {
  }

  /**
   * Replace the catalog with the one the service started with: empty, or the records of its data path.
   *
   * @throws UnsupportedOperationException if the manager persists its records, which a reset would delete.
   */
  default void reset() {
    throw new UnsupportedOperationException("Only the Iceberg catalog of an IN_MEMORY service can be reset.");
  }

}
