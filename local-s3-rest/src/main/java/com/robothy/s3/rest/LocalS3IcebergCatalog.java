package com.robothy.s3.rest;

import com.robothy.s3.core.iceberg.IcebergMetadataFiles;
import java.util.Objects;

/**
 * The settings of the Iceberg REST catalog that a {@linkplain LocalS3} may serve beside its S3 API, on the same port,
 * under {@code /iceberg/v1}. A service has one if {@linkplain LocalS3Builder#icebergCatalog(boolean)} turned it on,
 * and none otherwise: the catalog is off by default, so a service that doesn't want one carries neither its routes nor
 * its state.
 *
 * <p>The catalog keeps its tables in LocalS3 itself, in the warehouse bucket, so an {@code IN_MEMORY} service holds
 * its tables in memory and a {@code PERSISTENCE} service writes them to its data directory, without either mode
 * needing anything of its own.
 *
 * @param warehouse the warehouse location, an {@code s3://} URI of a bucket of this service, e.g.
 *     {@code s3://warehouse/}. A table that is created without a location of its own is placed under it.
 * @param createWarehouseBucket whether to create the bucket of {@linkplain #warehouse()} when the service starts, if
 *     it doesn't exist. {@code false} leaves that to the test, which then gets a clear failure if it forgot.
 * @param credentialVending whether a loaded table carries the settings to reach LocalS3 with — its endpoint, path-style
 *     access and the credentials of the service. An engine configured with the catalog URI alone then reaches the
 *     storage too; {@code false} means the client is configured by hand.
 * @param uniqueTableLocation whether the default location of a table ends in a random suffix, e.g.
 *     {@code s3://warehouse/db/orders-8f1c...}, rather than in its name alone. The default is {@code false}, which is
 *     Iceberg's: a location that is derived from the name is the predictable one, and the one a test asserts on.
 *     {@code true} is the {@code unique-table-location} of the Iceberg catalogs, and it matters when a table is
 *     dropped and another created under the same name — with a shared location, the new table lives among the files of
 *     the old one.
 */
public record LocalS3IcebergCatalog(String warehouse, boolean createWarehouseBucket, boolean credentialVending,
                                    boolean uniqueTableLocation) {

  /**
   * The warehouse of a catalog that isn't configured with one.
   */
  public static final String DEFAULT_WAREHOUSE = "s3://warehouse/";

  /**
   * Validate the settings.
   *
   * @throws IllegalArgumentException if the warehouse isn't an {@code s3://} URI of a bucket.
   */
  public LocalS3IcebergCatalog {
    Objects.requireNonNull(warehouse, "warehouse");
    // Fails here, where the warehouse is configured, rather than at the first table that is created under it.
    try {
      IcebergMetadataFiles.Location location = IcebergMetadataFiles.parse(warehouse);
      if (location.bucket().isBlank()) {
        throw new IllegalArgumentException("The Iceberg warehouse must name a bucket, e.g. s3://warehouse/.");
      }
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("Invalid Iceberg warehouse '" + warehouse
          + "': it must be an s3:// URI of a bucket of this service, e.g. s3://warehouse/.", e);
    }
  }

  /**
   * The settings of a catalog with the defaults: the {@value #DEFAULT_WAREHOUSE} warehouse, created when the service
   * starts, vending the credentials of the service.
   *
   * @return the settings.
   */
  public static LocalS3IcebergCatalog enabled() {
    return new LocalS3IcebergCatalog(DEFAULT_WAREHOUSE, true, true, false);
  }

  /**
   * These settings with another warehouse.
   *
   * @param newWarehouse the warehouse location.
   * @return new settings.
   */
  public LocalS3IcebergCatalog withWarehouse(String newWarehouse) {
    return new LocalS3IcebergCatalog(newWarehouse, createWarehouseBucket, credentialVending, uniqueTableLocation);
  }

  /**
   * These settings with {@linkplain #createWarehouseBucket()} set.
   *
   * @param newCreateWarehouseBucket whether the warehouse bucket is created when the service starts.
   * @return new settings.
   */
  public LocalS3IcebergCatalog withCreateWarehouseBucket(boolean newCreateWarehouseBucket) {
    return new LocalS3IcebergCatalog(warehouse, newCreateWarehouseBucket, credentialVending, uniqueTableLocation);
  }

  /**
   * These settings with {@linkplain #credentialVending()} set.
   *
   * @param newCredentialVending whether a loaded table carries the settings to reach LocalS3 with.
   * @return new settings.
   */
  public LocalS3IcebergCatalog withCredentialVending(boolean newCredentialVending) {
    return new LocalS3IcebergCatalog(warehouse, createWarehouseBucket, newCredentialVending, uniqueTableLocation);
  }

  /**
   * These settings with {@linkplain #uniqueTableLocation()} set.
   *
   * @param newUniqueTableLocation whether the default location of a table carries a random suffix.
   * @return new settings.
   */
  public LocalS3IcebergCatalog withUniqueTableLocation(boolean newUniqueTableLocation) {
    return new LocalS3IcebergCatalog(warehouse, createWarehouseBucket, credentialVending, newUniqueTableLocation);
  }

}
