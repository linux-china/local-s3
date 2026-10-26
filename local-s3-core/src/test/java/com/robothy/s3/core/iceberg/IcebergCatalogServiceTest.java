package com.robothy.s3.core.iceberg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.core.service.manager.LocalS3Manager;
import com.robothy.s3.core.service.manager.iceberg.LocalS3IcebergManager;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Iceberg catalog driven directly, in the documents of the REST protocol.
 *
 * <p>{@code IcebergRestCatalogIntegrationTest} of {@code local-s3-integration-test} drives the same catalog through
 * the real Iceberg client, which is what proves the metadata it writes is a table. These tests cover what is awkward
 * to provoke through a client that retries and refreshes on its own: a commit whose requirement no longer holds, a
 * commit that lost the race for the pointer, and the shape of the failures a client reads.
 */
class IcebergCatalogServiceTest {

  private static final String SCHEMA = """
      {"type":"struct","schema-id":0,"fields":[
        {"id":1,"name":"id","required":true,"type":"long"},
        {"id":2,"name":"name","required":false,"type":"string"}]}""";

  private IcebergCatalogService catalog;

  private IcebergMetadataFiles files;

  @BeforeEach
  void createCatalog() {
    LocalS3Manager s3Manager = LocalS3Manager.createInMemoryS3Manager();
    s3Manager.bucketService().createBucket("warehouse");
    LocalS3IcebergManager manager = LocalS3IcebergManager.createInMemory(null, s3Manager.bucketService(),
        s3Manager.objectService(), "s3://warehouse/", false);
    catalog = manager.icebergCatalogService();
    files = new IcebergMetadataFiles(s3Manager.bucketService(), s3Manager.objectService());
  }

  @Test
  void a_table_is_created_under_the_warehouse_and_loaded_back() {
    catalog.createNamespace(namespaceRequest("db"));
    ObjectNode created = createTable("db", "orders");

    String metadataLocation = created.path("metadata-location").asString();
    assertTrue(metadataLocation.startsWith("s3://warehouse/db/orders/metadata/00000-"),
        "A new table is placed under the warehouse: " + metadataLocation);
    assertTrue(metadataLocation.endsWith(".metadata.json"));
    assertEquals("s3://warehouse/db/orders", created.path("metadata").path("location").asString());
    assertEquals(2, created.path("metadata").path("format-version").asInt());
    // last-column-id is what a later commit asserts against, so it must be the largest id of the schema.
    assertEquals(2, created.path("metadata").path("last-column-id").asInt());
    assertEquals(-1, created.path("metadata").path("current-snapshot-id").asLong());
    assertNotNull(created.path("metadata").path("table-uuid").asString());

    ObjectNode loaded = catalog.loadTable(IcebergIdentifier.of(List.of("db"), "orders"), false);
    assertEquals(metadataLocation, loaded.path("metadata-location").asString());
    assertEquals(created.path("metadata").path("table-uuid").asString(),
        loaded.path("metadata").path("table-uuid").asString());
  }

  @Test
  void a_commit_whose_requirement_no_longer_holds_is_refused_as_a_commit_failure() {
    catalog.createNamespace(namespaceRequest("db"));
    ObjectNode created = createTable("db", "orders");
    String uuid = created.path("metadata").path("table-uuid").asString();

    // A commit that asserts the UUID of another table: the table moved under the writer.
    ObjectNode stale = IcebergJson.read("""
        {"requirements":[{"type":"assert-table-uuid","uuid":"00000000-0000-0000-0000-000000000000"}],
         "updates":[{"action":"set-properties","updates":{"owner":"tester"}}]}""");
    IcebergCatalogException failure = assertThrows(IcebergCatalogException.class,
        () -> catalog.updateTable(IcebergIdentifier.of(List.of("db"), "orders"), stale, false));
    assertEquals(409, failure.status());
    assertEquals("CommitFailedException", failure.type(),
        "A client only retries a commit when the failure is a CommitFailedException.");

    // The same commit with the right UUID is applied.
    ObjectNode good = IcebergJson.read("""
        {"requirements":[{"type":"assert-table-uuid","uuid":"%s"}],
         "updates":[{"action":"set-properties","updates":{"owner":"tester"}}]}""".formatted(uuid));
    ObjectNode committed = catalog.updateTable(IcebergIdentifier.of(List.of("db"), "orders"), good, false);
    assertEquals("tester", committed.path("metadata").path("properties").path("owner").asString());
    assertFalse(metadataLocation(committed).equals(created.path("metadata-location").asString()),
        "A commit writes a new metadata file rather than overwriting the current one.");
  }

  @Test
  void a_commit_records_the_file_it_supersedes_so_that_the_versions_can_be_walked_back() {
    catalog.createNamespace(namespaceRequest("db"));
    ObjectNode created = createTable("db", "orders");
    String first = created.path("metadata-location").asString();

    ObjectNode committed = catalog.updateTable(IcebergIdentifier.of(List.of("db"), "orders"),
        IcebergJson.read("""
            {"requirements":[],"updates":[{"action":"set-properties","updates":{"owner":"tester"}}]}"""), false);

    assertEquals(1, committed.path("metadata").path("metadata-log").size());
    assertEquals(first, committed.path("metadata").path("metadata-log").get(0).path("metadata-file").asString());
    assertTrue(metadataLocation(committed).contains("/00001-"), "The second version is numbered 00001.");
  }

  @Test
  void an_append_moves_the_main_branch_and_records_the_snapshot() {
    catalog.createNamespace(namespaceRequest("db"));
    createTable("db", "orders");

    ObjectNode commit = IcebergJson.read("""
        {"requirements":[{"type":"assert-ref-snapshot-id","ref":"main","snapshot-id":null}],
         "updates":[
           {"action":"add-snapshot","snapshot":{"snapshot-id":42,"sequence-number":1,"timestamp-ms":1700000000000,
             "manifest-list":"s3://warehouse/db/orders/metadata/snap-42.avro","summary":{"operation":"append"}}},
           {"action":"set-snapshot-ref","ref-name":"main","type":"branch","snapshot-id":42}]}""");
    ObjectNode committed = catalog.updateTable(IcebergIdentifier.of(List.of("db"), "orders"), commit, false);

    ObjectNode metadata = (ObjectNode) committed.get("metadata");
    assertEquals(42, metadata.path("current-snapshot-id").asLong());
    assertEquals(1, metadata.path("last-sequence-number").asLong());
    assertEquals(1, metadata.path("snapshots").size());
    assertEquals(42, metadata.path("refs").path("main").path("snapshot-id").asLong());
    assertEquals(1, metadata.path("snapshot-log").size());
    assertEquals(1700000000000L, metadata.path("snapshot-log").get(0).path("timestamp-ms").asLong());

    // The same commit again: main is no longer absent, so the writer is told the branch moved.
    IcebergCatalogException failure = assertThrows(IcebergCatalogException.class,
        () -> catalog.updateTable(IcebergIdentifier.of(List.of("db"), "orders"), commit, false));
    assertEquals(409, failure.status());
    assertEquals("CommitFailedException", failure.type());
  }

  @Test
  void removing_a_snapshot_removes_its_statistics_too() {
    catalog.createNamespace(namespaceRequest("db"));
    createTable("db", "orders");
    IcebergIdentifier orders = IcebergIdentifier.of(List.of("db"), "orders");

    catalog.updateTable(orders, IcebergJson.read("""
        {"requirements":[],
         "updates":[
           {"action":"add-snapshot","snapshot":{"snapshot-id":1,"sequence-number":1,"timestamp-ms":1700000000000,
             "manifest-list":"s3://warehouse/db/orders/metadata/snap-1.avro","summary":{"operation":"append"}}},
           {"action":"add-snapshot","snapshot":{"snapshot-id":2,"parent-snapshot-id":1,"sequence-number":2,
             "timestamp-ms":1700000001000,"manifest-list":"s3://warehouse/db/orders/metadata/snap-2.avro",
             "summary":{"operation":"append"}}},
           {"action":"set-snapshot-ref","ref-name":"main","type":"branch","snapshot-id":2},
           {"action":"set-statistics","statistics":{"snapshot-id":1,"statistics-path":"s3://warehouse/s1.puffin",
             "file-size-in-bytes":10,"file-footer-size-in-bytes":5,"blob-metadata":[]}},
           {"action":"set-statistics","statistics":{"snapshot-id":2,"statistics-path":"s3://warehouse/s2.puffin",
             "file-size-in-bytes":10,"file-footer-size-in-bytes":5,"blob-metadata":[]}},
           {"action":"set-partition-statistics","partition-statistics":{"snapshot-id":1,
             "statistics-path":"s3://warehouse/p1.parquet","file-size-in-bytes":10}}]}"""), false);

    ObjectNode committed = catalog.updateTable(orders, IcebergJson.read("""
        {"requirements":[],"updates":[{"action":"remove-snapshots","snapshot-ids":[1]}]}"""), false);

    ObjectNode metadata = (ObjectNode) committed.get("metadata");
    assertEquals(1, metadata.path("snapshots").size());
    assertEquals(1, metadata.path("statistics").size(),
        "Expiring a snapshot deletes its puffin file, so the metadata must stop referring to it.");
    assertEquals(2, metadata.path("statistics").get(0).path("snapshot-id").asLong());
    assertEquals(0, metadata.path("partition-statistics").size());
  }

  @Test
  void an_added_schema_spec_or_sort_order_never_overwrites_an_existing_one() {
    catalog.createNamespace(namespaceRequest("db"));
    createTable("db", "orders");
    IcebergIdentifier orders = IcebergIdentifier.of(List.of("db"), "orders");

    // Each names ID 0, which is taken by something different: like Iceberg, the ID is re-assigned.
    ObjectNode metadata = (ObjectNode) catalog.updateTable(orders, IcebergJson.read("""
        {"requirements":[],
         "updates":[
           {"action":"add-schema","schema":{"type":"struct","schema-id":0,"fields":[
             {"id":1,"name":"zzz","required":true,"type":"string"}]}},
           {"action":"add-spec","spec":{"spec-id":0,"fields":[
             {"source-id":1,"field-id":1000,"name":"id_bucket","transform":"bucket[4]"}]}},
           {"action":"set-default-spec","spec-id":-1},
           {"action":"add-sort-order","sort-order":{"order-id":0,"fields":[
             {"source-id":1,"transform":"identity","direction":"asc","null-order":"nulls-first"}]}},
           {"action":"set-default-sort-order","sort-order-id":-1}]}"""), false).get("metadata");

    assertEquals(2, metadata.path("schemas").size());
    assertEquals(0, metadata.path("current-schema-id").asInt(), "No set-current-schema was sent.");
    assertEquals("long", metadata.path("schemas").get(0).path("fields").get(0).path("type").asString());
    assertEquals(1, metadata.path("schemas").get(1).path("schema-id").asInt());
    assertEquals(2, metadata.path("partition-specs").size());
    assertEquals(1, metadata.path("default-spec-id").asInt());
    assertEquals(2, metadata.path("sort-orders").size());
    assertEquals(1, metadata.path("default-sort-order-id").asInt());

    // Adding one that is already there, under whatever ID, reuses it, and -1 resolves to it.
    metadata = (ObjectNode) catalog.updateTable(orders, IcebergJson.read("""
        {"requirements":[],
         "updates":[
           {"action":"add-schema","schema":%s},
           {"action":"set-current-schema","schema-id":-1},
           {"action":"add-spec","spec":{"spec-id":7,"fields":[]}},
           {"action":"set-default-spec","spec-id":-1}]}""".formatted(SCHEMA.replace("\"schema-id\":0",
        "\"schema-id\":5"))), false).get("metadata");
    assertEquals(2, metadata.path("schemas").size());
    assertEquals(0, metadata.path("current-schema-id").asInt());
    assertEquals(2, metadata.path("partition-specs").size());
    assertEquals(0, metadata.path("default-spec-id").asInt());
  }

  @Test
  void a_snapshot_built_on_a_stale_table_is_refused_even_on_another_branch() {
    catalog.createNamespace(namespaceRequest("db"));
    catalog.createTable(List.of("db"), IcebergJson.read("""
        {"name":"orders","schema":%s,"properties":{"format-version":"3"}}""".formatted(SCHEMA)));
    IcebergIdentifier orders = IcebergIdentifier.of(List.of("db"), "orders");
    catalog.updateTable(orders, IcebergJson.read(snapshotCommit("main", 1, null, 1, 0)), false);

    // Two writers read the table at snapshot 1, and each commits to a branch of its own.
    ObjectNode committed = catalog.updateTable(orders, IcebergJson.read(snapshotCommit("main", 2, 1L, 2, 10)), false);
    assertEquals(20, committed.path("metadata").path("next-row-id").asLong());
    IcebergCatalogException stale = assertThrows(IcebergCatalogException.class,
        () -> catalog.updateTable(orders, IcebergJson.read(snapshotCommit("b", 3, 1L, 2, 10)), false));
    assertEquals(409, stale.status());
    assertEquals("CommitFailedException", stale.type(),
        "The second writer must refresh and retry, or its rows would reuse the row IDs of the first.");

    // A snapshot built on the refreshed table is accepted.
    committed = catalog.updateTable(orders, IcebergJson.read(snapshotCommit("b", 3, 1L, 3, 20)), false);
    assertEquals(30, committed.path("metadata").path("next-row-id").asLong());
    assertEquals(3, committed.path("metadata").path("last-sequence-number").asLong());

    IcebergCatalogException missing = assertThrows(IcebergCatalogException.class,
        () -> catalog.updateTable(orders, IcebergJson.read(snapshotCommit("c", 4, 3L, 4, null)), false));
    assertEquals(400, missing.status());
  }

  /** A commit that adds a snapshot of 10 rows and points {@code ref} at it. */
  private static String snapshotCommit(String ref, long snapshotId, Long parentId, long sequenceNumber,
                                       Integer firstRowId) {
    return """
        {"requirements":[],
         "updates":[
           {"action":"add-snapshot","snapshot":{"snapshot-id":%d,%s"sequence-number":%d,%s"added-rows":10,
             "timestamp-ms":1700000000000,"manifest-list":"s3://warehouse/db/orders/metadata/snap-%d.avro",
             "summary":{"operation":"append"}}},
           {"action":"set-snapshot-ref","ref-name":"%s","type":"branch","snapshot-id":%d}]}""".formatted(
        snapshotId, parentId == null ? "" : "\"parent-snapshot-id\":" + parentId + ",", sequenceNumber,
        firstRowId == null ? "" : "\"first-row-id\":" + firstRowId + ",", snapshotId, ref, snapshotId);
  }

  @Test
  void a_staged_create_writes_nothing_until_its_transaction_commits() {
    catalog.createNamespace(namespaceRequest("db"));
    ObjectNode request = IcebergJson.read(
        """
        {"name":"staged","stage-create":true,"schema":%s}""".formatted(SCHEMA));

    ObjectNode staged = catalog.createTable(List.of("db"), request);
    assertNull(staged.get("metadata-location"), "A staged create has no metadata file yet.");
    assertFalse(catalog.tableExists(IcebergIdentifier.of(List.of("db"), "staged"), false));

    // The transaction of the client then creates it with an assert-create requirement.
    ObjectNode commit = IcebergJson.read("""
        {"requirements":[{"type":"assert-create"}],
         "updates":[
           {"action":"assign-uuid","uuid":"11111111-1111-1111-1111-111111111111"},
           {"action":"upgrade-format-version","format-version":2},
           {"action":"add-schema","schema":%s},
           {"action":"set-current-schema","schema-id":-1},
           {"action":"set-location","location":"s3://warehouse/db/staged"}]}""".formatted(SCHEMA));
    ObjectNode committed = catalog.updateTable(IcebergIdentifier.of(List.of("db"), "staged"), commit, false);

    assertTrue(catalog.tableExists(IcebergIdentifier.of(List.of("db"), "staged"), false));
    assertEquals("11111111-1111-1111-1111-111111111111", committed.path("metadata").path("table-uuid").asString());
    assertEquals(0, committed.path("metadata").path("current-schema-id").asInt());
    assertEquals(2, committed.path("metadata").path("last-column-id").asInt());
    // The fields LocalS3 uses to resolve "the schema I just added" must never reach a client.
    assertFalse(IcebergJson.write(committed).contains("__last-added"));

    // A second assert-create now finds the table there.
    assertEquals("CommitFailedException", assertThrows(IcebergCatalogException.class,
        () -> catalog.updateTable(IcebergIdentifier.of(List.of("db"), "staged"), commit, false)).type());
  }

  @Test
  void a_namespace_that_holds_something_is_not_dropped() {
    catalog.createNamespace(namespaceRequest("db"));
    catalog.createNamespace(namespaceRequest("db", "schema"));

    // db holds the namespace db.schema.
    IcebergCatalogException holdsNamespace = assertThrows(IcebergCatalogException.class,
        () -> catalog.dropNamespace(List.of("db")));
    assertEquals(409, holdsNamespace.status());
    assertEquals("NamespaceNotEmptyException", holdsNamespace.type());

    // db.schema holds nothing yet, so it is dropped; once it holds a table it isn't.
    catalog.dropNamespace(List.of("db", "schema"));
    catalog.createNamespace(namespaceRequest("db", "schema"));
    catalog.createTable(List.of("db", "schema"),
        IcebergJson.read("""
            {"name":"items","schema":%s}""".formatted(SCHEMA)));
    assertEquals("NamespaceNotEmptyException", assertThrows(IcebergCatalogException.class,
        () -> catalog.dropNamespace(List.of("db", "schema"))).type(),
        "A namespace that holds a table is not empty either.");

    // Dropping the table empties it again.
    catalog.dropTable(IcebergIdentifier.of(List.of("db", "schema"), "items"), false, false);
    catalog.dropNamespace(List.of("db", "schema"));
    assertFalse(catalog.namespaceExists(List.of("db", "schema")));
  }

  @Test
  void a_purge_deletes_the_metadata_kept_elsewhere_with_write_metadata_path() {
    catalog.createNamespace(namespaceRequest("db"));
    ObjectNode created = catalog.createTable(List.of("db"),
        IcebergJson.read("""
            {"name":"orders","schema":%s,"properties":{"write.metadata.path":"s3://warehouse/meta/orders"}}"""
            .formatted(SCHEMA)));
    String metadataLocation = created.path("metadata-location").asString();
    assertTrue(metadataLocation.startsWith("s3://warehouse/meta/orders/"), metadataLocation);
    files.write("s3://warehouse/db/orders/data/part-0.parquet", "data");
    files.write("s3://warehouse/meta/orders/snap-1.avro", "manifest list");

    catalog.dropTable(IcebergIdentifier.of(List.of("db"), "orders"), false, true);

    assertThrows(IcebergCatalogException.class, () -> files.read(metadataLocation));
    assertThrows(IcebergCatalogException.class, () -> files.read("s3://warehouse/meta/orders/snap-1.avro"));
    assertThrows(IcebergCatalogException.class, () -> files.read("s3://warehouse/db/orders/data/part-0.parquet"));
  }

  @Test
  void a_purge_leaves_a_location_outside_the_warehouse_alone() {
    files.createBucketIfAbsent("s3://app-data");
    files.write("s3://app-data/shared/unrelated.csv", "not the table's");
    catalog.createNamespace(namespaceRequest("db"));
    catalog.createTable(List.of("db"), IcebergJson.read("""
        {"name":"orders","schema":%s,"location":"s3://app-data/shared"}""".formatted(SCHEMA)));

    catalog.dropTable(IcebergIdentifier.of(List.of("db"), "orders"), false, true);

    assertNotNull(files.read("s3://app-data/shared/unrelated.csv"));
    assertFalse(catalog.tableExists(IcebergIdentifier.of(List.of("db"), "orders"), false));
  }

  @Test
  void a_purge_keeps_a_metadata_directory_another_table_still_uses() {
    catalog.createNamespace(namespaceRequest("db"));
    String request = """
        {"name":"%s","schema":%s,"properties":{"write.metadata.path":"s3://warehouse/shared-meta"}}""";
    catalog.createTable(List.of("db"), IcebergJson.read(request.formatted("first", SCHEMA)));
    String second = catalog.createTable(List.of("db"), IcebergJson.read(request.formatted("second", SCHEMA)))
        .path("metadata-location").asString();

    catalog.dropTable(IcebergIdentifier.of(List.of("db"), "first"), false, true);

    assertNotNull(files.read(second));
  }

  @Test
  void a_missing_namespace_and_a_missing_table_are_told_apart() {
    IcebergCatalogException missingNamespace = assertThrows(IcebergCatalogException.class,
        () -> catalog.loadNamespace(List.of("nope")));
    assertEquals(404, missingNamespace.status());
    assertEquals("NoSuchNamespaceException", missingNamespace.type());

    catalog.createNamespace(namespaceRequest("db"));
    IcebergCatalogException missingTable = assertThrows(IcebergCatalogException.class,
        () -> catalog.loadTable(IcebergIdentifier.of(List.of("db"), "nope"), false));
    assertEquals(404, missingTable.status());
    assertEquals("NoSuchTableException", missingTable.type(),
        "The type is how a client tells a missing table from a missing namespace.");
  }

  @Test
  void nested_namespaces_are_listed_under_their_parent_only() {
    catalog.createNamespace(namespaceRequest("db"));
    catalog.createNamespace(namespaceRequest("db", "schema"));
    catalog.createNamespace(namespaceRequest("db", "schema", "inner"));
    catalog.createNamespace(namespaceRequest("other"));

    assertEquals(2, catalog.listNamespaces(List.of()).path("namespaces").size());
    assertEquals(1, catalog.listNamespaces(List.of("db")).path("namespaces").size());
    assertEquals("schema", catalog.listNamespaces(List.of("db")).path("namespaces").get(0).get(1).asString());
    assertEquals(1, catalog.listNamespaces(List.of("db", "schema")).path("namespaces").size());
    assertEquals(0, catalog.listNamespaces(List.of("other")).path("namespaces").size());
  }

  @Test
  void a_table_of_one_namespace_is_not_a_table_of_another_with_the_same_prefix() {
    catalog.createNamespace(namespaceRequest("db"));
    catalog.createNamespace(namespaceRequest("db", "schema"));
    createTable("db", "orders");
    ObjectNode nested = catalog.createTable(List.of("db", "schema"),
        IcebergJson.read("""
            {"name":"items","schema":%s}""".formatted(SCHEMA)));
    assertNotNull(nested.path("metadata-location").asString());

    assertEquals(1, catalog.listTables(List.of("db"), false).path("identifiers").size());
    assertEquals("orders",
        catalog.listTables(List.of("db"), false).path("identifiers").get(0).path("name").asString());
    assertEquals(1, catalog.listTables(List.of("db", "schema"), false).path("identifiers").size());
    assertEquals("items",
        catalog.listTables(List.of("db", "schema"), false).path("identifiers").get(0).path("name").asString());
  }

  @Test
  void a_location_outside_the_object_store_is_refused() {
    catalog.createNamespace(namespaceRequest("db"));
    ObjectNode request = IcebergJson.read("""
        {"name":"elsewhere","location":"file:///tmp/orders","schema":%s}""".formatted(SCHEMA));

    IcebergCatalogException refused = assertThrows(IcebergCatalogException.class,
        () -> catalog.createTable(List.of("db"), request));
    assertEquals(400, refused.status());
    assertTrue(refused.getMessage().contains("s3://"), refused.getMessage());
  }

  @Test
  void an_update_that_the_catalog_does_not_know_is_refused_rather_than_ignored() {
    catalog.createNamespace(namespaceRequest("db"));
    createTable("db", "orders");

    IcebergCatalogException refused = assertThrows(IcebergCatalogException.class,
        () -> catalog.updateTable(IcebergIdentifier.of(List.of("db"), "orders"),
            IcebergJson.read("""
                {"requirements":[],"updates":[{"action":"teleport-table","to":"mars"}]}"""), false));
    assertEquals(400, refused.status(),
        "Silently dropping an update would answer the client metadata it never asked for.");
  }

  private ObjectNode createTable(String namespace, String name) {
    return catalog.createTable(List.of(namespace),
        IcebergJson.read("""
            {"name":"%s","schema":%s}""".formatted(name, SCHEMA)));
  }

  private static ObjectNode namespaceRequest(String... levels) {
    ObjectNode request = IcebergJson.newObject();
    var namespace = request.putArray("namespace");
    for (String level : levels) {
      namespace.add(level);
    }
    return request;
  }

  private static String metadataLocation(ObjectNode loadTableResult) {
    return loadTableResult.path("metadata-location").asString();
  }

}
