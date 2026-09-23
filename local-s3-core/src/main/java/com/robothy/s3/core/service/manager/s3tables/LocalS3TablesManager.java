package com.robothy.s3.core.service.manager.s3tables;

import com.robothy.s3.core.s3tables.S3TablesService;
import com.robothy.s3.core.service.BucketService;
import com.robothy.s3.core.service.ObjectService;
import com.robothy.s3.core.storage.LocalS3Store;
import com.robothy.s3.core.storage.PersistencePolicy;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * Owns the table buckets of the S3 Tables API of a LocalS3 service, and the hold on the store they are kept in.
 *
 * <p>It is the counterpart of {@link com.robothy.s3.core.service.manager.iceberg.LocalS3IcebergManager} for the
 * S3 Tables API, and it keeps its records in the same place: the one {@linkplain LocalS3Store} of the service, beside
 * its S3 buckets, its vector buckets and its Iceberg catalog, so that a data directory still holds a single file that
 * a copy of it is a consistent point of.
 *
 * <p>The tables themselves are not kept here either. Each table bucket has an Iceberg catalog of its own, whose
 * pointers live in maps of the same store, and the metadata and data files that those pointers name are ordinary
 * objects of a bucket of the service. So a {@code PERSISTENCE} service persists its tables the way it persists
 * everything else, and an {@code IN_MEMORY} one keeps them in memory and loses them when it stops.
 */
public interface LocalS3TablesManager {

  /**
   * Create table buckets that keep their records in memory, optionally starting from the ones of a data directory.
   *
   * <p>The records of the directory are copied, and the directory is never written: an {@code IN_MEMORY} service that
   * starts from a data path serves the table buckets that path holds and keeps its own changes in memory, like it does
   * with the objects and the vectors of that path.
   *
   * @param initialDataDirectory the data directory of the initial records; {@code null}, or a directory that holds no
   *     store, to start with no table bucket.
   * @param bucketService the buckets of the service, which the bucket behind a table bucket is created in.
   * @param objectService the objects of the service, which the metadata files of the tables are.
   * @param region the region that the ARNs of the service name.
   * @param accountId the account that the ARNs of the service name.
   * @return an in-memory manager.
   */
  static LocalS3TablesManager createInMemory(@Nullable Path initialDataDirectory, BucketService bucketService,
                                             ObjectService objectService, String region, String accountId) {
    return new InMemoryLocalS3TablesManager(initialDataDirectory, bucketService, objectService, region, accountId);
  }

  /**
   * Create table buckets that keep their records in a data directory, in the store that the S3 buckets of the same
   * directory are kept in.
   *
   * @param dataDirectory the data directory, the one that the S3 buckets are kept in.
   * @param persistencePolicy when the changes reach the disk, shared with the S3 buckets of the same directory, which
   *     hold the same store.
   * @param bucketService the buckets of the service.
   * @param objectService the objects of the service, which the metadata files of the tables are.
   * @param region the region that the ARNs of the service name.
   * @param accountId the account that the ARNs of the service name.
   * @return a persistent manager.
   */
  static LocalS3TablesManager createFileSystem(Path dataDirectory, PersistencePolicy persistencePolicy,
                                               BucketService bucketService, ObjectService objectService,
                                               String region, String accountId) {
    return new FileSystemLocalS3TablesManager(dataDirectory, persistencePolicy, bucketService, objectService, region,
        accountId);
  }

  /**
   * The S3 Tables API that the endpoints of the service answer through.
   *
   * @return the service.
   */
  S3TablesService s3TablesService();

  /**
   * Release the hold this manager has on the store of its data directory, which closes the store once the other
   * managers of the same directory have released it too. A manager that holds nothing does nothing.
   */
  default void close() {
  }

  /**
   * Replace the table buckets with the ones the service started with: none, or the records of its data path.
   *
   * @throws UnsupportedOperationException if the manager persists its records, which a reset would delete.
   */
  default void reset() {
    throw new UnsupportedOperationException("Only the table buckets of an IN_MEMORY service can be reset.");
  }

}
