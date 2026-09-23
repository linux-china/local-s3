package com.robothy.s3.core.service.manager.iceberg;

import com.robothy.s3.core.iceberg.IcebergCatalogService;
import com.robothy.s3.core.iceberg.IcebergCatalogStore;
import com.robothy.s3.core.iceberg.IcebergMetadataFiles;
import com.robothy.s3.core.service.BucketService;
import com.robothy.s3.core.service.ObjectService;
import com.robothy.s3.core.storage.LocalS3Store;
import com.robothy.s3.core.storage.PersistencePolicy;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Persistent implementation of {@linkplain LocalS3IcebergManager}, which keeps the namespaces and the table pointers
 * of the catalog in the store of a data directory.
 *
 * <p>It takes a hold of the one {@linkplain LocalS3Store} of the directory, the store that the S3 buckets and the
 * vector buckets of the same directory are written to, and {@linkplain #close()} releases it. The metadata files of
 * the tables are objects of the service, so they are persisted by the S3 half of it and need nothing here.
 */
final class FileSystemLocalS3IcebergManager implements LocalS3IcebergManager {

  private final LocalS3Store localS3Store;

  private final IcebergCatalogService icebergCatalogService;

  FileSystemLocalS3IcebergManager(Path dataPath, PersistencePolicy persistencePolicy, BucketService bucketService,
                                  ObjectService objectService, String warehouse, boolean uniqueTableLocation) {
    Objects.requireNonNull(dataPath, "A data directory is required to persist the Iceberg catalog.");
    Objects.requireNonNull(persistencePolicy, "persistencePolicy");
    this.localS3Store = LocalS3Store.persistent(dataPath, persistencePolicy);
    this.icebergCatalogService = new IcebergCatalogService(IcebergCatalogStore.create(localS3Store),
        new IcebergMetadataFiles(bucketService, objectService), warehouse, uniqueTableLocation);
  }

  @Override
  public IcebergCatalogService icebergCatalogService() {
    return icebergCatalogService;
  }

  @Override
  public void close() {
    localS3Store.close();
  }

}
