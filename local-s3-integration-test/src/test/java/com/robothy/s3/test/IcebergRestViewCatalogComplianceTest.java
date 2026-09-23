package com.robothy.s3.test;

import com.robothy.s3.rest.LocalS3;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.rest.RESTCatalog;
import org.apache.iceberg.view.ViewCatalogTests;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;

/**
 * The views of the Iceberg REST catalog of LocalS3, run against {@link ViewCatalogTests} — the suite that Apache
 * Iceberg verifies the view half of a catalog implementation with, the companion of
 * {@linkplain IcebergRestCatalogComplianceTest}.
 *
 * <p>Views are the part of the hand-written catalog with the least of its own testing and the most room to drift: a
 * view is a different document from a table, with versions and representations rather than snapshots and manifests, and
 * LocalS3 builds that document itself as well.
 */
@Tag("data-tools")
class IcebergRestViewCatalogComplianceTest extends ViewCatalogTests<RESTCatalog> {

  private LocalS3 localS3;

  private RESTCatalog catalog;

  @BeforeEach
  void startCatalog() {
    localS3 = LocalS3.builder()
        .port(-1)
        .icebergCatalog(true)
        .netty(netty -> netty.registerShutdownHook(false))
        .build();
    localS3.start();
    catalog = new RESTCatalog();
    // A HashMap, not Map.of(): the Iceberg client probes the properties with containsKey(null), which the immutable
    // maps of the JDK answer with a NullPointerException.
    Map<String, String> properties = new HashMap<>();
    properties.put(CatalogProperties.URI, "http://127.0.0.1:" + localS3.getPort() + "/iceberg");
    // The view defaults and overrides that the suite asserts a created view carries, the same ones the REST catalog
    // test of Iceberg configures its client with.
    properties.put(CatalogProperties.VIEW_DEFAULT_PREFIX + "key1", "catalog-default-key1");
    properties.put(CatalogProperties.VIEW_DEFAULT_PREFIX + "key2", "catalog-default-key2");
    properties.put(CatalogProperties.VIEW_DEFAULT_PREFIX + "key3", "catalog-default-key3");
    properties.put(CatalogProperties.VIEW_OVERRIDE_PREFIX + "key3", "catalog-override-key3");
    properties.put(CatalogProperties.VIEW_OVERRIDE_PREFIX + "key4", "catalog-override-key4");
    catalog.initialize("local", properties);
  }

  /**
   * Where the views of the suite live when it asks for a location: under the warehouse, so that their metadata lands in
   * LocalS3. The base of {@code ViewCatalogTests} is a temporary directory of the JVM, which the catalog of LocalS3
   * refuses — it keeps the views in the object store it is, not on the local disk.
   */
  @Override
  protected String viewLocation(String... paths) {
    StringBuilder location = new StringBuilder("s3://warehouse/views");
    for (String path : paths) {
      location.append('/').append(path);
    }
    return location.toString();
  }

  @AfterEach
  void stopCatalog() throws IOException {
    if (catalog != null) {
      catalog.close();
    }
    if (localS3 != null) {
      localS3.shutdown();
    }
  }

  @Override
  protected RESTCatalog catalog() {
    return catalog;
  }

  /**
   * The tables and the views of LocalS3 are the same catalog, as they are for every REST catalog: one service answers
   * both, which is what lets a view be created over a table and a name be refused to a view because a table has it.
   */
  @Override
  protected Catalog tableCatalog() {
    return catalog;
  }

  @Override
  protected boolean requiresNamespaceCreate() {
    return true;
  }

  @Override
  protected boolean overridesRequestedLocation() {
    return false;
  }

  @Override
  protected boolean supportsServerSideRetry() {
    return true;
  }
}
