package com.robothy.s3.test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.robothy.s3.rest.LocalS3;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.HasTableOperations;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.CatalogTests;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.NoSuchTableException;
import org.apache.iceberg.rest.RESTCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Iceberg REST catalog of LocalS3, run against {@link CatalogTests} — the suite that Apache Iceberg verifies a
 * catalog implementation with, taken from the test jar of {@code iceberg-core} rather than written here.
 *
 * <p>This is the regression test that {@linkplain IcebergRestCatalogIntegrationTest} cannot be. That one asserts what
 * LocalS3 set out to support, which is self-certifying: it grows when LocalS3 grows, and says nothing about the parts
 * of the spec nobody thought of. {@code CatalogTests} is the other way round — it is Iceberg's own account of what a
 * catalog must do, some hundred cases over namespace semantics, requirement evaluation, concurrent commits, staged
 * creates, {@code replaceTransaction}, metadata log trimming and table registration. LocalS3 builds table metadata by
 * hand, without the Iceberg library, so bumping the {@code iceberg} version in {@code libs.versions.toml} and running
 * this is what tells us whether the hand-written implementation still is one, instead of a user finding out.
 *
 * <p>The overrides below are the honest declaration of what this catalog does. They are not knobs to turn a failure
 * off: each one answers a question the suite asks about the implementation, and the few tests that are overridden
 * outright say in their own comment why the case cannot apply to a catalog that keeps its metadata in an object store.
 *
 * @see <a href="https://iceberg.apache.org/spec/">The Iceberg table specification</a>
 */
@Tag("data-tools")
class IcebergRestCatalogComplianceTest extends CatalogTests<RESTCatalog> {

  /**
   * Where the tables of the suite are created when it asks for a location of its own: under the warehouse, so that the
   * files land in LocalS3. The base of {@code CatalogTests} is {@code file:/tmp}, which the catalog of LocalS3 refuses
   * — it keeps the tables in the object store it is, not on the local disk.
   */
  private static final String CUSTOM_LOCATION_PREFIX = "s3://warehouse/custom";

  private LocalS3 localS3;

  private RESTCatalog catalog;

  /**
   * Every service this test started, including the ones {@link #initCatalog(String, Map)} needed one of, so that they
   * are all shut down again.
   */
  private final List<LocalS3> servers = new ArrayList<>();

  /**
   * Every catalog this test opened, so that they are all closed again.
   */
  private final List<RESTCatalog> catalogs = new ArrayList<>();

  @BeforeEach
  void startCatalog() {
    localS3 = startService(false);
    catalog = initCatalog("local", Map.of());
  }

  @AfterEach
  void stopCatalog() throws IOException {
    for (RESTCatalog opened : catalogs) {
      opened.close();
    }
    catalogs.clear();
    for (LocalS3 server : servers) {
      server.shutdown();
    }
    servers.clear();
  }

  @Override
  protected RESTCatalog catalog() {
    return catalog;
  }

  @Override
  protected RESTCatalog initCatalog(String catalogName, Map<String, String> additionalProperties) {
    // unique-table-location is a property of the catalog rather than of its client, so a client cannot ask a running
    // catalog for it: the suite asks for a catalog that has it, which here is a service started with it.
    boolean uniqueTableLocation = Boolean.parseBoolean(
        additionalProperties.getOrDefault(CatalogProperties.UNIQUE_TABLE_LOCATION, "false"));
    LocalS3 service = uniqueTableLocation ? startService(true) : localS3;

    RESTCatalog opened = new RESTCatalog();
    // A HashMap, not Map.of(): the Iceberg client probes the properties with containsKey(null), which the immutable
    // maps of the JDK answer with a NullPointerException.
    Map<String, String> properties = new HashMap<>();
    // The URI is the whole configuration of the storage: the endpoint and the credentials are vended by the catalog.
    properties.put(CatalogProperties.URI, "http://127.0.0.1:" + service.getPort() + "/iceberg");
    // The table defaults and overrides that the suite asserts a created table carries, the same ones the REST catalog
    // test of Iceberg configures its client with.
    properties.put(CatalogProperties.TABLE_DEFAULT_PREFIX + "default-key1", "catalog-default-key1");
    properties.put(CatalogProperties.TABLE_DEFAULT_PREFIX + "default-key2", "catalog-default-key2");
    properties.put(CatalogProperties.TABLE_DEFAULT_PREFIX + "override-key3", "catalog-default-key3");
    properties.put(CatalogProperties.TABLE_OVERRIDE_PREFIX + "override-key3", "catalog-override-key3");
    properties.put(CatalogProperties.TABLE_OVERRIDE_PREFIX + "override-key4", "catalog-override-key4");
    properties.putAll(additionalProperties);
    opened.initialize(catalogName, properties);
    catalogs.add(opened);
    return opened;
  }

  /**
   * Start a LocalS3 that serves an Iceberg catalog on a free port.
   *
   * @param uniqueTableLocation whether its catalog gives every table a location of its own.
   */
  private LocalS3 startService(boolean uniqueTableLocation) {
    LocalS3 service = LocalS3.builder()
        .port(-1)
        .icebergCatalog(iceberg -> iceberg.uniqueTableLocation(uniqueTableLocation))
        .netty(netty -> netty.registerShutdownHook(false))
        .build();
    service.start();
    servers.add(service);
    return service;
  }

  /**
   * The metadata of a table of this catalog is an object of LocalS3, not a file on disk, so the case the suite
   * describes — moving the metadata file away and loading the table — is reproduced by deleting that object instead.
   *
   * <p>The exception differs from the suite's for the same reason. A REST catalog answers a loaded table's metadata
   * inline, so the client never opens the file and cannot report that it failed to: it is LocalS3 that reads the object,
   * and a table whose metadata has been deleted is answered {@code 404 NoSuchTableException}, which the client raises as
   * {@link NoSuchTableException}.
   */
  @Test
  @Override
  public void testLoadTableWithMissingMetadataFile(@TempDir Path tempDir) {
    catalog.createNamespace(TBL.namespace());
    catalog.buildTable(TBL, SCHEMA).create();

    Table table = catalog.loadTable(TBL);
    String metadataFileLocation = ((HasTableOperations) table).operations().current().metadataFileLocation();
    table.io().deleteFile(metadataFileLocation);

    assertThatThrownBy(() -> catalog.loadTable(TBL))
        .isInstanceOf(NoSuchTableException.class)
        .hasMessageContaining(metadataFileLocation);
  }

  @Override
  protected String baseTableLocation(TableIdentifier identifier) {
    return CUSTOM_LOCATION_PREFIX + "/" + String.join("/", identifier.namespace().levels()) + "/" + identifier.name();
  }

  /**
   * A namespace of the catalog carries properties, which {@code POST /v1/namespaces/{ns}/properties} updates.
   */
  @Override
  protected boolean supportsNamespaceProperties() {
    return true;
  }

  /**
   * A namespace has as many levels as the client gives it, and {@code GET /v1/namespaces?parent=} lists the children
   * of one.
   */
  @Override
  protected boolean supportsNestedNamespaces() {
    return true;
  }

  /**
   * A table is created in a namespace that exists; LocalS3 doesn't invent one, like the REST spec says.
   */
  @Override
  protected boolean requiresNamespaceCreate() {
    return true;
  }

  /**
   * The requirements of a commit are evaluated by the catalog, which is what lets a client retry a commit that lost a
   * race rather than only be told the commit failed.
   */
  @Override
  protected boolean supportsServerSideRetry() {
    return true;
  }

  /**
   * A create request that names a location gets that location.
   */
  @Override
  protected boolean overridesRequestedLocation() {
    return false;
  }

  /**
   * A namespace level, or a table name, may hold a {@code /}: the levels of a namespace travel in one path segment,
   * separated by {@code 0x1F}, and a table name is one segment of its own, so a slash in either is escaped by the
   * client and decoded here without being read as a path separator.
   */
  @Override
  protected boolean supportsNamesWithSlashes() {
    return true;
  }

  /**
   * A namespace level, or a table name, may hold a {@code .}.
   */
  @Override
  protected boolean supportsNamesWithDot() {
    return true;
  }
}
