package com.robothy.s3.core.service.manager.s3tables;

import com.robothy.s3.core.iceberg.IcebergCatalogService;
import com.robothy.s3.core.iceberg.IcebergCatalogStore;
import com.robothy.s3.core.iceberg.IcebergMetadataFiles;
import com.robothy.s3.core.s3tables.S3TablesCatalogFactory;
import com.robothy.s3.core.s3tables.S3TablesStore;
import com.robothy.s3.core.s3tables.TableBucketRecord;
import com.robothy.s3.core.storage.LocalS3Store;

/**
 * Opens the Iceberg catalogs of the table buckets of a store, and copies and clears them, which is what the two
 * managers of the S3 Tables API do differently only in <em>which</em> store they open them in.
 *
 * <p>A table bucket is a catalog, kept in maps named after it, see {@link S3TablesStore#catalogMaps}. Nothing here
 * decides anything about a table: it is the plumbing that connects a table bucket to the catalog machinery that
 * {@link com.robothy.s3.core.iceberg.IcebergCatalogService} already is.
 */
final class S3TablesCatalogs {

  private S3TablesCatalogs() {
  }

  /**
   * The factory that opens the catalog of a table bucket in a store.
   *
   * @param localS3Store the store to open the maps of the catalogs in.
   * @param files reads and writes the metadata files of the tables.
   * @return the factory.
   */
  static S3TablesCatalogFactory factory(LocalS3Store localS3Store, IcebergMetadataFiles files) {
    return (tableBucket, warehouse) -> {
      S3TablesStore.CatalogMaps maps = S3TablesStore.catalogMaps(tableBucket);
      // Unique table locations: a table bucket owns the files of its tables and deletes them with the table, so two
      // tables that were created under the same name at different times must not share a location — purging the
      // second would take the files the first left behind with it, or worse, leave them to be read as the new table.
      return new IcebergCatalogService(
          IcebergCatalogStore.create(localS3Store, maps.namespacesMap(), maps.tablesMap()), files, warehouse, true);
    };
  }

  /**
   * Copy the catalogs of the table buckets of one store into another, which an {@code IN_MEMORY} service does with the
   * store of the data directory it starts from.
   *
   * @param target the store to copy into, whose table buckets have already been copied.
   * @param source the store to copy from.
   * @param buckets the table buckets of the target, which name the catalogs to copy.
   */
  static void copy(LocalS3Store target, LocalS3Store source, Iterable<TableBucketRecord> buckets) {
    for (TableBucketRecord bucket : buckets) {
      S3TablesStore.CatalogMaps maps = S3TablesStore.catalogMaps(bucket.name());
      IcebergCatalogStore.create(target, maps.namespacesMap(), maps.tablesMap())
          .loadFrom(IcebergCatalogStore.create(source, maps.namespacesMap(), maps.tablesMap()));
    }
  }

  /**
   * Forget the namespaces and the tables of the catalogs of some table buckets, which a reset of an
   * {@code IN_MEMORY} service does before it loads its initial records again.
   *
   * @param localS3Store the store the catalogs are kept in.
   * @param buckets the table buckets whose catalogs to clear.
   */
  static void clear(LocalS3Store localS3Store, Iterable<TableBucketRecord> buckets) {
    for (TableBucketRecord bucket : buckets) {
      S3TablesStore.CatalogMaps maps = S3TablesStore.catalogMaps(bucket.name());
      IcebergCatalogStore.create(localS3Store, maps.namespacesMap(), maps.tablesMap()).clear();
    }
  }

}
