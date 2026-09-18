package com.robothy.s3.core.service.manager.iceberg;

import com.robothy.s3.core.iceberg.IcebergCatalogService;
import com.robothy.s3.core.iceberg.IcebergCatalogStore;
import com.robothy.s3.core.iceberg.IcebergMetadataFiles;
import com.robothy.s3.core.service.BucketService;
import com.robothy.s3.core.service.ObjectService;
import com.robothy.s3.core.storage.LocalS3Store;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In-memory implementation of {@linkplain LocalS3IcebergManager}, optionally starting from the catalog of a data
 * directory.
 *
 * <p>The records of the directory are copied into a store that is never written to a file, so the directory keeps the
 * catalog it had whatever the service does with it — the same promise that the objects and the vectors of an
 * {@code IN_MEMORY} data path get. The copy is cheap: a catalog record is a namespace or a pointer to a metadata file,
 * not the table.
 */
final class InMemoryLocalS3IcebergManager implements LocalS3IcebergManager {

  private static final Logger log = LoggerFactory.getLogger(InMemoryLocalS3IcebergManager.class);

  @Nullable
  private final Path initialDataDirectory;

  /**
   * The store of the catalog, which writes no file. It is kept rather than replaced by a {@linkplain #reset()}, which
   * clears it and loads the initial records again, so that the service built on it stays valid.
   */
  private final LocalS3Store localS3Store = LocalS3Store.inMemory();

  private final IcebergCatalogStore catalogStore = IcebergCatalogStore.create(localS3Store);

  private final IcebergCatalogService icebergCatalogService;

  InMemoryLocalS3IcebergManager(@Nullable Path initialDataDirectory, BucketService bucketService,
                                ObjectService objectService, String warehouse) {
    this.initialDataDirectory = initialDataDirectory;
    loadInitialRecords();
    this.icebergCatalogService = new IcebergCatalogService(catalogStore,
        new IcebergMetadataFiles(bucketService, objectService), warehouse);
  }

  private void loadInitialRecords() {
    if (initialDataDirectory == null) {
      return;
    }
    // Reads the store of the path read-only, which answers an empty one for a path that holds none.
    try (LocalS3Store initial = LocalS3Store.readOnly(initialDataDirectory)) {
      IcebergCatalogStore source = IcebergCatalogStore.create(initial);
      catalogStore.loadFrom(source);
      if (catalogStore.size() > 0) {
        log.info("Loaded {} Iceberg catalog record(s) from {}.", catalogStore.size(), initialDataDirectory);
      }
    }
  }

  @Override
  public IcebergCatalogService icebergCatalogService() {
    return icebergCatalogService;
  }

  @Override
  public void reset() {
    catalogStore.clear();
    loadInitialRecords();
  }

  @Override
  public void close() {
    localS3Store.close();
  }

}
