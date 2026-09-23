package com.robothy.s3.core.service.manager.s3tables;

import com.robothy.s3.core.iceberg.IcebergMetadataFiles;
import com.robothy.s3.core.s3tables.S3TablesService;
import com.robothy.s3.core.s3tables.S3TablesStore;
import com.robothy.s3.core.service.BucketService;
import com.robothy.s3.core.service.ObjectService;
import com.robothy.s3.core.storage.LocalS3Store;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In-memory implementation of {@linkplain LocalS3TablesManager}, optionally starting from the table buckets of a data
 * directory.
 *
 * <p>The records of the directory are copied into a store that is never written to a file, so the directory keeps the
 * table buckets it had whatever the service does with them — the same promise that the objects, the vectors and the
 * Iceberg catalog of an {@code IN_MEMORY} data path get. The copy is cheap for the same reason: what is copied is the
 * table buckets and the pointers of their tables, not the tables.
 */
final class InMemoryLocalS3TablesManager implements LocalS3TablesManager {

  private static final Logger log = LoggerFactory.getLogger(InMemoryLocalS3TablesManager.class);

  @Nullable
  private final Path initialDataDirectory;

  /**
   * The store of the table buckets, which writes no file. It is kept rather than replaced by a
   * {@linkplain #reset()}, which clears it and loads the initial records again, so that the service built on it stays
   * valid.
   */
  private final LocalS3Store localS3Store = LocalS3Store.inMemory();

  private final S3TablesStore tablesStore = S3TablesStore.create(localS3Store);

  private final S3TablesService s3TablesService;

  InMemoryLocalS3TablesManager(@Nullable Path initialDataDirectory, BucketService bucketService,
                               ObjectService objectService, String region, String accountId) {
    this.initialDataDirectory = initialDataDirectory;
    loadInitialRecords();
    this.s3TablesService = new S3TablesService(tablesStore, bucketService,
        S3TablesCatalogs.factory(localS3Store, new IcebergMetadataFiles(bucketService, objectService)),
        region, accountId);
  }

  private void loadInitialRecords() {
    if (initialDataDirectory == null) {
      return;
    }
    // Reads the store of the path read-only, which answers an empty one for a path that holds none.
    try (LocalS3Store initial = LocalS3Store.readOnly(initialDataDirectory)) {
      tablesStore.loadFrom(S3TablesStore.create(initial));
      // The table buckets are copied first, because they are what names the catalogs to copy.
      S3TablesCatalogs.copy(localS3Store, initial, tablesStore.listBuckets());
      if (tablesStore.size() > 0) {
        log.info("Loaded {} S3 Tables record(s) from {}.", tablesStore.size(), initialDataDirectory);
      }
    }
  }

  @Override
  public S3TablesService s3TablesService() {
    return s3TablesService;
  }

  @Override
  public void reset() {
    // The catalogs before the table buckets: the table buckets are what names them.
    S3TablesCatalogs.clear(localS3Store, tablesStore.listBuckets());
    tablesStore.clear();
    loadInitialRecords();
    s3TablesService.forgetCatalogs();
  }

  @Override
  public void close() {
    localS3Store.close();
  }

}
