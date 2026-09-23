package com.robothy.s3.core.s3tables;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.core.iceberg.IcebergCatalogService;
import com.robothy.s3.core.iceberg.IcebergIdentifier;
import com.robothy.s3.core.iceberg.IcebergJson;
import com.robothy.s3.core.iceberg.IcebergMetadataFiles;
import com.robothy.s3.core.service.BucketService;
import com.robothy.s3.core.service.manager.LocalS3Manager;
import com.robothy.s3.core.service.manager.s3tables.LocalS3TablesManager;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/**
 * The S3 Tables API driven directly, in the documents of the protocol.
 *
 * <p>{@code S3TablesIntegrationTest} of {@code local-s3-integration-test} drives the same API through the real
 * {@code S3TablesClient}, which is what proves the wire format. These tests cover what is awkward to provoke through a
 * client: a stale version token, a table that appeared in the catalog of a table bucket without going through this API,
 * and the failures a client reads.
 */
class S3TablesServiceTest {

  private static final String SCHEMA = """
      {"iceberg":{"schema":{"fields":[
        {"name":"id","type":"long","required":true},
        {"name":"name","type":"string"}]}}}""";

  private S3TablesService s3Tables;

  private BucketService buckets;

  /**
   * The object store of the service, which stands in for the {@code S3FileIO} of a client that writes the next
   * metadata file of a table itself.
   */
  private IcebergMetadataFiles files;

  @BeforeEach
  void createService() {
    LocalS3Manager s3Manager = LocalS3Manager.createInMemoryS3Manager();
    buckets = s3Manager.bucketService();
    files = new IcebergMetadataFiles(s3Manager.bucketService(), s3Manager.objectService());
    LocalS3TablesManager manager = LocalS3TablesManager.createInMemory(null, s3Manager.bucketService(),
        s3Manager.objectService(), "us-east-1", S3TablesArn.DEFAULT_ACCOUNT_ID);
    s3Tables = manager.s3TablesService();
  }

  @Test
  void a_table_bucket_is_created_with_a_bucket_of_the_service_behind_it() {
    String arn = createTableBucket("sales");
    assertEquals("arn:aws:s3tables:us-east-1:000000000000:bucket/sales", arn);
    // The tables of a table bucket are objects of an ordinary bucket, which is what lets an engine's S3FileIO reach
    // them and a test look at what was written.
    assertNotNull(buckets.getBucket("sales--table-s3"));
    assertEquals("s3://sales--table-s3", s3Tables.getTableBucket(arn).path("warehouseLocation").asString(
        "s3://sales--table-s3"));
  }

  @Test
  void an_underscore_in_a_name_is_a_hyphen_in_the_bucket_behind_it() {
    // A table bucket may hold '_', which an Amazon S3 bucket name may not.
    createTableBucket("my_sales");
    assertNotNull(buckets.getBucket("my-sales--table-s3"));
  }

  @Test
  void two_table_buckets_that_want_the_same_bucket_get_one_each() {
    createTableBucket("my_sales");
    String second = createTableBucket("my-sales");
    String warehouse = s3Tables.catalogOfWarehouse(second).warehouse();
    assertTrue(warehouse.startsWith("s3://my-sales--table-s3-"), warehouse);
    // Deleting one must not be able to take the files of the other with it.
    assertNotEquals("s3://my-sales--table-s3", warehouse);
  }

  @Test
  void a_table_is_created_in_a_namespace_and_answered_by_name_and_by_arn() {
    String arn = createTableBucket("lake");
    s3Tables.createNamespace(arn, namespaceRequest("db"));
    ObjectNode created = createTable(arn, "db", "orders");

    String tableArn = created.path("tableARN").asString();
    assertTrue(tableArn.startsWith("arn:aws:s3tables:us-east-1:000000000000:bucket/lake/table/"), tableArn);

    ObjectNode table = s3Tables.getTable(arn, "db", "orders", null);
    assertEquals("orders", table.path("name").asString());
    assertEquals("ICEBERG", table.path("format").asString());
    assertEquals("customer", table.path("type").asString());
    assertEquals(created.path("versionToken").asString(), table.path("versionToken").asString());
    assertTrue(table.path("metadataLocation").asString().endsWith(".metadata.json"));
    assertTrue(table.path("warehouseLocation").asString().startsWith("s3://lake--table-s3/db/orders"));
    assertEquals("db", table.path("namespace").get(0).asString());

    // The ARN names the ID the service assigned, so it addresses the same table.
    assertEquals("orders", s3Tables.getTable(null, null, null, tableArn).path("name").asString());
  }

  @Test
  void a_commit_needs_the_current_version_token() {
    String arn = createTableBucket("commits");
    s3Tables.createNamespace(arn, namespaceRequest("db"));
    ObjectNode created = createTable(arn, "db", "t");
    String stale = created.path("versionToken").asString();
    String first = s3Tables.getTableMetadataLocation(arn, "db", "t").path("metadataLocation").asString();

    ObjectNode commit = IcebergJson.newObject();
    commit.put("versionToken", stale);
    commit.put("metadataLocation", copyOfMetadata(arn, first, "00001"));
    ObjectNode committed = s3Tables.updateTableMetadataLocation(arn, "db", "t", commit);
    assertNotEquals(stale, committed.path("versionToken").asString(), "A commit draws a new token.");

    // The same commit again, with the token it started from, is refused rather than overwriting the one that won.
    S3TablesException failure = assertThrows(S3TablesException.class,
        () -> s3Tables.updateTableMetadataLocation(arn, "db", "t", commit));
    assertEquals(409, failure.status());
    assertEquals("ConflictException", failure.errorType());
    // And the commit that won is still the one the table is at.
    assertEquals(committed.path("metadataLocation").asString(),
        s3Tables.getTableMetadataLocation(arn, "db", "t").path("metadataLocation").asString());
  }

  @Test
  void a_commit_to_a_metadata_file_that_isnt_there_leaves_the_token_alone() {
    String arn = createTableBucket("rollback");
    s3Tables.createNamespace(arn, namespaceRequest("db"));
    ObjectNode created = createTable(arn, "db", "t");
    String token = created.path("versionToken").asString();

    ObjectNode commit = IcebergJson.newObject();
    commit.put("versionToken", token);
    commit.put("metadataLocation", "s3://rollback--table-s3/db/t/metadata/does-not-exist.metadata.json");
    assertThrows(RuntimeException.class, () -> s3Tables.updateTableMetadataLocation(arn, "db", "t", commit));

    // The commit didn't land, so the token the client holds is still the current one and it can retry with it.
    assertEquals(token, s3Tables.getTableMetadataLocation(arn, "db", "t").path("versionToken").asString());
  }

  @Test
  void a_table_created_over_the_iceberg_endpoint_is_a_table_of_this_api() {
    String arn = createTableBucket("shared");
    // Straight through the catalog of the table bucket, which is what the Iceberg REST endpoint of that table bucket
    // does: nothing of this API is involved.
    IcebergCatalogService catalog = s3Tables.catalogOfWarehouse(arn);
    catalog.createNamespace(IcebergJson.read("{\"namespace\":[\"db\"]}"));
    catalog.createTable(List.of("db"), IcebergJson.read("""
        {"name":"from_engine","schema":{"type":"struct","fields":[
          {"id":1,"name":"id","required":true,"type":"long"}]}}"""));

    // It is listed, it has an ARN, and it can be addressed by it.
    ObjectNode listed = s3Tables.listTables(arn, null, null, null, null);
    assertEquals(1, listed.path("tables").size());
    assertEquals("from_engine", listed.path("tables").get(0).path("name").asString());
    String tableArn = listed.path("tables").get(0).path("tableARN").asString();
    assertEquals("from_engine", s3Tables.getTable(null, null, null, tableArn).path("name").asString());
    // And the namespace the engine created is a namespace of this API.
    assertEquals(1, s3Tables.listNamespaces(arn, null, null, null).path("namespaces").size());
  }

  @Test
  void a_commit_over_the_iceberg_endpoint_draws_a_new_version_token() {
    String arn = createTableBucket("cross");
    s3Tables.createNamespace(arn, namespaceRequest("db"));
    String token = createTable(arn, "db", "t").path("versionToken").asString();

    // A commit that went through the catalog rather than through this API, which is what an engine on the Iceberg REST
    // endpoint of the same table bucket does.
    IcebergCatalogService catalog = s3Tables.catalogOfWarehouse(arn);
    String first = catalog.metadataLocationOf(IcebergIdentifier.of(List.of("db"), "t"));
    catalog.setMetadataLocation(IcebergIdentifier.of(List.of("db"), "t"), copyOfMetadata(arn, first, "00001"));

    // The token this API vends follows it, so the one the client is still holding is refused: the two protocols commit
    // against one another safely.
    String now = s3Tables.getTableMetadataLocation(arn, "db", "t").path("versionToken").asString();
    assertNotEquals(token, now);
    ObjectNode commit = IcebergJson.newObject();
    commit.put("versionToken", token);
    commit.put("metadataLocation", first);
    assertEquals(409, assertThrows(S3TablesException.class,
        () -> s3Tables.updateTableMetadataLocation(arn, "db", "t", commit)).status());
  }

  @Test
  void a_table_bucket_and_a_namespace_are_deleted_only_once_they_are_empty() {
    String arn = createTableBucket("ordered");
    s3Tables.createNamespace(arn, namespaceRequest("db"));
    createTable(arn, "db", "t");

    assertEquals(409, assertThrows(S3TablesException.class, () -> s3Tables.deleteTableBucket(arn)).status());
    assertEquals(409, assertThrows(S3TablesException.class, () -> s3Tables.deleteNamespace(arn, "db")).status());

    s3Tables.deleteTable(arn, "db", "t", null);
    s3Tables.deleteNamespace(arn, "db");
    s3Tables.deleteTableBucket(arn);
    assertEquals(404, assertThrows(S3TablesException.class, () -> s3Tables.getTableBucket(arn)).status());
    // The bucket behind the table bucket goes with it.
    assertThrows(RuntimeException.class, () -> buckets.getBucket("ordered--table-s3"));
  }

  @Test
  void deleting_a_table_takes_its_files_with_it() {
    String arn = createTableBucket("purged");
    s3Tables.createNamespace(arn, namespaceRequest("db"));
    createTable(arn, "db", "t");
    String location = s3Tables.getTable(arn, "db", "t", null).path("warehouseLocation").asString();
    String bucket = location.substring("s3://".length(), location.indexOf('/', "s3://".length()));

    s3Tables.deleteTable(arn, "db", "t", null);
    assertTrue(buckets.getBucket(bucket) != null);
    assertFalse(s3Tables.listTables(arn, null, null, null, null).path("tables").iterator().hasNext());
  }

  @Test
  void a_rename_moves_the_table_and_keeps_its_arn() {
    String arn = createTableBucket("renames");
    s3Tables.createNamespace(arn, namespaceRequest("db"));
    s3Tables.createNamespace(arn, namespaceRequest("other"));
    String tableArn = createTable(arn, "db", "before").path("tableARN").asString();

    ObjectNode rename = IcebergJson.newObject();
    rename.put("newNamespaceName", "other");
    rename.put("newName", "after");
    s3Tables.renameTable(arn, "db", "before", rename);

    ObjectNode table = s3Tables.getTable(null, null, null, tableArn);
    assertEquals("after", table.path("name").asString());
    assertEquals("other", table.path("namespace").get(0).asString());
    assertEquals(404, assertThrows(S3TablesException.class,
        () -> s3Tables.getTable(arn, "db", "before", null)).status());
  }

  @Test
  void the_names_and_the_shapes_the_api_refuses() {
    assertEquals(400, assertThrows(S3TablesException.class,
        () -> s3Tables.createTableBucket(IcebergJson.read("{\"name\":\"Not Valid\"}"))).status());
    assertEquals(400, assertThrows(S3TablesException.class,
        () -> s3Tables.createTableBucket(IcebergJson.newObject())).status());
    assertEquals(400, assertThrows(S3TablesException.class,
        () -> s3Tables.getTableBucket("arn:aws:s3:::not-a-table-bucket")).status());

    String arn = createTableBucket("refusals");
    // One level of namespace, whatever the shape of the field suggests.
    assertEquals(400, assertThrows(S3TablesException.class,
        () -> s3Tables.createNamespace(arn, IcebergJson.read("{\"namespace\":[\"a\",\"b\"]}"))).status());
    s3Tables.createNamespace(arn, namespaceRequest("db"));
    assertEquals(400, assertThrows(S3TablesException.class, () -> s3Tables.createTable(arn, "db",
        IcebergJson.read("{\"name\":\"t\",\"format\":\"DELTA\"}"))).status());
  }

  @Test
  void the_table_buckets_cannot_see_one_anothers_namespaces() {
    String first = createTableBucket("first");
    String second = createTableBucket("second");
    s3Tables.createNamespace(first, namespaceRequest("db"));

    assertEquals(1, s3Tables.listNamespaces(first, null, null, null).path("namespaces").size());
    assertEquals(0, s3Tables.listNamespaces(second, null, null, null).path("namespaces").size());
    assertEquals(404, assertThrows(S3TablesException.class,
        () -> s3Tables.getNamespace(second, "db")).status());
  }

  @Test
  void a_listing_is_paged_by_a_continuation_token() {
    String arn = createTableBucket("paged");
    for (String name : List.of("a", "b", "c")) {
      s3Tables.createNamespace(arn, namespaceRequest(name));
    }
    ObjectNode first = s3Tables.listNamespaces(arn, null, null, 2);
    assertEquals(2, first.path("namespaces").size());
    assertEquals("b", first.path("continuationToken").asString());
    ObjectNode second = s3Tables.listNamespaces(arn, null, first.path("continuationToken").asString(), 2);
    assertEquals(1, second.path("namespaces").size());
    assertEquals("c", second.path("namespaces").get(0).path("namespace").get(0).asString());
    assertTrue(second.path("continuationToken").isMissingNode());
  }

  private String createTableBucket(String name) {
    ObjectNode request = IcebergJson.newObject();
    request.put("name", name);
    return s3Tables.createTableBucket(request).path("arn").asString();
  }

  private static ObjectNode namespaceRequest(String namespace) {
    ObjectNode request = IcebergJson.newObject();
    request.set("namespace", IcebergJson.newArray().add(namespace));
    return request;
  }

  private ObjectNode createTable(String tableBucketArn, String namespace, String name) {
    ObjectNode request = IcebergJson.newObject();
    request.put("name", name);
    request.put("format", "ICEBERG");
    request.set("metadata", IcebergJson.read(SCHEMA));
    return s3Tables.createTable(tableBucketArn, namespace, request);
  }

  /**
   * A copy of a metadata file under another version number, which stands in for the next metadata file that a client of
   * this API writes itself before it asks for the pointer to be moved.
   */
  private String copyOfMetadata(String tableBucketArn, String metadataLocation, String version) {
    assertNotNull(s3Tables.catalogOfWarehouse(tableBucketArn));
    String next = metadataLocation.replaceFirst("/[0-9]+-", "/" + version + "-");
    files.write(next, files.read(metadataLocation));
    return next;
  }

}
