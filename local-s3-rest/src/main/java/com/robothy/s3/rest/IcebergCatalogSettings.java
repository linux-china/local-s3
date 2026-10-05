package com.robothy.s3.rest;

import java.util.Objects;
import java.util.function.Consumer;
import org.jspecify.annotations.NonNull;

/**
 * The settings of the Iceberg REST catalog of a service; see {@linkplain LocalS3IcebergCatalog}.
 * {@linkplain LocalS3Builder#icebergCatalog(Consumer)} hands out the one that the builder builds its configuration
 * from.
 */
public final class IcebergCatalogSettings {

    LocalS3IcebergCatalog icebergCatalog;

    IcebergCatalogSettings() {
    }

    /**
     * Set whether the catalog is served at all, e.g. to turn off a catalog that a shared builder configured.
     *
     * @param enabled {@code true} to serve the catalog with the settings configured here; {@code false} to serve
     *     none, which drops the settings.
     * @return these settings.
     */
    public IcebergCatalogSettings enabled(boolean enabled) {
        this.icebergCatalog = enabled
                ? Objects.requireNonNullElseGet(this.icebergCatalog, LocalS3IcebergCatalog::enabled)
                : null;
        return this;
    }

    /**
     * Set the warehouse of the catalog: the {@code s3://} location of a bucket of this service that the tables
     * created without a location of their own are placed under.
     *
     * @param warehouse the warehouse location, e.g. {@code s3://lakehouse/}.
     * @return these settings.
     * @throws IllegalArgumentException if the warehouse isn't an {@code s3://} URI of a bucket.
     */
    public IcebergCatalogSettings warehouse(@NonNull String warehouse) {
        return settings(catalog().withWarehouse(warehouse));
    }

    /**
     * Set whether the bucket of the {@linkplain #warehouse(String) warehouse} is created when the service starts,
     * if it doesn't exist. The default is {@code true}; {@code false} leaves that to the test, which then gets a
     * clear failure if it forgot.
     *
     * @param createWarehouseBucket whether to create the warehouse bucket.
     * @return these settings.
     */
    public IcebergCatalogSettings createWarehouseBucket(boolean createWarehouseBucket) {
        return settings(catalog().withCreateWarehouseBucket(createWarehouseBucket));
    }

    /**
     * Set whether a loaded table carries the settings to reach LocalS3 with — its endpoint, path-style access and
     * the credentials of the service. The default is {@code true}, so an engine configured with the catalog URI
     * alone reaches the storage too; {@code false} means the client is configured by hand.
     *
     * @param credentialVending whether a loaded table carries the settings of the service.
     * @return these settings.
     */
    public IcebergCatalogSettings credentialVending(boolean credentialVending) {
        return settings(catalog().withCredentialVending(credentialVending));
    }

    /**
     * Set whether the default location of a table ends in a random suffix, e.g.
     * {@code s3://warehouse/db/orders-8f1c...}, rather than in the name of the table. The default is {@code false},
     * which is Iceberg's: the location of a table is then derived from its name, and a test can assert on it.
     *
     * <p>{@code true} is the {@code unique-table-location} of the Iceberg catalogs. It matters when a table is
     * dropped, or renamed, and another is created under the old name: with locations derived from the name, the new
     * table lives among the files of the old one, and purging one of them takes the other's files with it.
     *
     * @param uniqueTableLocation whether the default location of a table carries a random suffix.
     * @return these settings.
     */
    public IcebergCatalogSettings uniqueTableLocation(boolean uniqueTableLocation) {
        return settings(catalog().withUniqueTableLocation(uniqueTableLocation));
    }

    /**
     * Replace the settings with ones the caller holds, e.g. the ones an application read from its own
     * configuration.
     *
     * @param icebergCatalog the settings; {@code null} to serve no catalog.
     * @return these settings.
     */
    public IcebergCatalogSettings settings(LocalS3IcebergCatalog icebergCatalog) {
        this.icebergCatalog = icebergCatalog;
        return this;
    }

    /**
     * The catalog being configured, which {@linkplain LocalS3Builder#icebergCatalog(Consumer)} turned on; the
     * defaults again if {@linkplain #settings(LocalS3IcebergCatalog)} or {@linkplain #enabled(boolean)} dropped
     * it, so that configuring a warehouse after that turns the catalog back on rather than failing.
     */
    private LocalS3IcebergCatalog catalog() {
        return Objects.requireNonNullElseGet(this.icebergCatalog, LocalS3IcebergCatalog::enabled);
    }

}
