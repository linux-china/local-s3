package com.robothy.s3.core.service.manager.s3tables;

import com.robothy.s3.core.iceberg.IcebergMetadataFiles;
import com.robothy.s3.core.s3tables.S3TablesService;
import com.robothy.s3.core.s3tables.S3TablesStore;
import com.robothy.s3.core.service.BucketService;
import com.robothy.s3.core.service.ObjectService;
import com.robothy.s3.core.storage.LocalS3Store;
import com.robothy.s3.core.storage.PersistencePolicy;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Persistent implementation of {@linkplain LocalS3TablesManager}, which keeps the table buckets and the catalogs of
 * their tables in the store of a data directory.
 *
 * <p>It takes a hold of the one {@linkplain LocalS3Store} of the directory, the store that the S3 buckets, the vector
 * buckets and the Iceberg catalog of the same directory are written to, and {@linkplain #close()} releases it. The
 * metadata and data files of the tables are objects of the service, so they are persisted by the S3 half of it and
 * need nothing here.
 */
final class FileSystemLocalS3TablesManager implements LocalS3TablesManager {

  private final LocalS3Store localS3Store;

  private final S3TablesService s3TablesService;

  FileSystemLocalS3TablesManager(Path dataPath, PersistencePolicy persistencePolicy, BucketService bucketService,
                                 ObjectService objectService, String region, String accountId) {
    Objects.requireNonNull(dataPath, "A data directory is required to persist the table buckets.");
    Objects.requireNonNull(persistencePolicy, "persistencePolicy");
    this.localS3Store = LocalS3Store.persistent(dataPath, persistencePolicy);
    this.s3TablesService = new S3TablesService(S3TablesStore.create(localS3Store), bucketService,
        S3TablesCatalogs.factory(localS3Store, new IcebergMetadataFiles(bucketService, objectService)),
        region, accountId);
  }

  @Override
  public S3TablesService s3TablesService() {
    return s3TablesService;
  }

  @Override
  public void close() {
    localS3Store.close();
  }

}
