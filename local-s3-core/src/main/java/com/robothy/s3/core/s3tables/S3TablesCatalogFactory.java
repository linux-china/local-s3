package com.robothy.s3.core.s3tables;

import com.robothy.s3.core.iceberg.IcebergCatalogService;

/**
 * Opens the Iceberg catalog of a table bucket.
 *
 * <p>Each table bucket of the S3 Tables API is a catalog of its own — its namespaces and its tables are kept in maps
 * named after it, see {@link S3TablesStore#catalogMaps} — so a service serves as many catalogs as it has table
 * buckets. Which store those maps are opened in is the manager's to know, not the service's, which is why
 * {@linkplain S3TablesService} is handed this rather than a store.
 */
@FunctionalInterface
public interface S3TablesCatalogFactory {

  /**
   * Open the catalog of a table bucket.
   *
   * @param tableBucket the name of the table bucket.
   * @param warehouse the {@code s3://} location that its tables are written under.
   * @return the catalog, which the caller keeps: this may be called once per table bucket.
   */
  IcebergCatalogService create(String tableBucket, String warehouse);

}
