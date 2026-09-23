package com.robothy.s3.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.jupiter.LocalS3;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3tables.S3TablesClient;
import software.amazon.awssdk.services.s3tables.model.ConflictException;
import software.amazon.awssdk.services.s3tables.model.CreateTableResponse;
import software.amazon.awssdk.services.s3tables.model.GetTableResponse;
import software.amazon.awssdk.services.s3tables.model.IcebergMetadata;
import software.amazon.awssdk.services.s3tables.model.IcebergSchema;
import software.amazon.awssdk.services.s3tables.model.ListNamespacesResponse;
import software.amazon.awssdk.services.s3tables.model.ListTableBucketsResponse;
import software.amazon.awssdk.services.s3tables.model.ListTablesResponse;
import software.amazon.awssdk.services.s3tables.model.NotFoundException;
import software.amazon.awssdk.services.s3tables.model.OpenTableFormat;
import software.amazon.awssdk.services.s3tables.model.SchemaField;
import software.amazon.awssdk.services.s3tables.model.TableMetadata;

/**
 * The S3 Tables API of LocalS3 through the {@code S3TablesClient} of the AWS SDK, which is the client every application
 * that uses the real service reaches it with.
 */
@LocalS3
class S3TablesIntegrationTest {

  private static final TableMetadata ORDERS = TableMetadata.builder()
      .iceberg(IcebergMetadata.builder()
          .schema(IcebergSchema.builder()
              .fields(SchemaField.builder().name("id").type("long").required(true).build(),
                  SchemaField.builder().name("total").type("double").build())
              .build())
          .build())
      .build();

  @Test
  void a_table_bucket_holds_namespaces_and_tables(S3TablesClient tables) {
    String arn = tables.createTableBucket(request -> request.name("sales")).arn();
    assertEquals("arn:aws:s3tables:us-east-1:000000000000:bucket/sales", arn);

    ListTableBucketsResponse buckets = tables.listTableBuckets(request -> request.prefix("sal"));
    assertEquals(List.of("sales"), buckets.tableBuckets().stream().map(bucket -> bucket.name()).toList());
    assertEquals("sales", tables.getTableBucket(request -> request.tableBucketARN(arn)).name());

    tables.createNamespace(request -> request.tableBucketARN(arn).namespace("shop"));
    ListNamespacesResponse namespaces = tables.listNamespaces(request -> request.tableBucketARN(arn));
    assertEquals(1, namespaces.namespaces().size());
    assertEquals(List.of("shop"), namespaces.namespaces().get(0).namespace());
    assertNotNull(tables.getNamespace(request -> request.tableBucketARN(arn).namespace("shop")).createdAt());

    CreateTableResponse created = tables.createTable(request -> request.tableBucketARN(arn)
        .namespace("shop").name("orders").format(OpenTableFormat.ICEBERG).metadata(ORDERS));
    assertTrue(created.tableARN().startsWith("arn:aws:s3tables:us-east-1:000000000000:bucket/sales/table/"));
    assertNotNull(created.versionToken());

    ListTablesResponse listed = tables.listTables(request -> request.tableBucketARN(arn));
    assertEquals(List.of("orders"), listed.tables().stream().map(table -> table.name()).toList());

    GetTableResponse table = tables.getTable(request -> request.tableBucketARN(arn)
        .namespace("shop").name("orders"));
    assertEquals("orders", table.name());
    assertEquals(OpenTableFormat.ICEBERG, table.format());
    assertEquals(created.versionToken(), table.versionToken());
    assertTrue(table.metadataLocation().endsWith(".metadata.json"), table.metadataLocation());
    assertTrue(table.warehouseLocation().startsWith("s3://sales--table-s3/"), table.warehouseLocation());

    // The same table, addressed by its ARN rather than by its name.
    assertEquals("orders", tables.getTable(request -> request.tableArn(created.tableARN())).name());
  }

  @Test
  void the_files_of_a_table_are_objects_of_a_bucket_of_the_same_service(S3TablesClient tables, S3Client s3) {
    String arn = tables.createTableBucket(request -> request.name("lake")).arn();
    tables.createNamespace(request -> request.tableBucketARN(arn).namespace("db"));
    tables.createTable(request -> request.tableBucketARN(arn).namespace("db").name("events")
        .format(OpenTableFormat.ICEBERG).metadata(ORDERS));

    assertTrue(s3.listBuckets().buckets().stream().anyMatch(bucket -> "lake--table-s3".equals(bucket.name())),
        "The tables of a table bucket live in a bucket of the same service.");
    assertTrue(s3.listObjectsV2(request -> request.bucket("lake--table-s3")).contents().stream()
            .anyMatch(object -> object.key().endsWith(".metadata.json")),
        "The metadata file of the table should be an object of that bucket.");
  }

  @Test
  void a_commit_moves_the_metadata_location_and_the_version_token_guards_it(S3TablesClient tables, S3Client s3) {
    String arn = tables.createTableBucket(request -> request.name("commits")).arn();
    tables.createNamespace(request -> request.tableBucketARN(arn).namespace("db"));
    CreateTableResponse created = tables.createTable(request -> request.tableBucketARN(arn)
        .namespace("db").name("t").format(OpenTableFormat.ICEBERG).metadata(ORDERS));

    String first = tables.getTableMetadataLocation(request -> request.tableBucketARN(arn)
        .namespace("db").name("t")).metadataLocation();
    // A commit of this API is the client writing the next metadata file and then asking for the pointer to be moved.
    String next = first.replace(".metadata.json", "-next.metadata.json");
    String bucket = next.substring("s3://".length(), next.indexOf('/', "s3://".length()));
    String key = next.substring("s3://".length() + bucket.length() + 1);
    s3.putObject(request -> request.bucket(bucket).key(key),
        software.amazon.awssdk.core.sync.RequestBody.fromString(
            s3.getObjectAsBytes(request -> request.bucket(bucket)
                .key(first.substring("s3://".length() + bucket.length() + 1))).asUtf8String()));

    String committed = tables.updateTableMetadataLocation(request -> request.tableBucketARN(arn)
        .namespace("db").name("t").versionToken(created.versionToken()).metadataLocation(next)).versionToken();
    assertFalse(created.versionToken().equals(committed), "A commit draws a new version token.");
    assertEquals(next, tables.getTableMetadataLocation(request -> request.tableBucketARN(arn)
        .namespace("db").name("t")).metadataLocation());

    // The token of the first read is stale now, so a commit built on it is refused rather than overwriting the commit
    // that won.
    assertThrows(ConflictException.class, () -> tables.updateTableMetadataLocation(request ->
        request.tableBucketARN(arn).namespace("db").name("t")
            .versionToken(created.versionToken()).metadataLocation(first)));
  }

  @Test
  void a_table_is_renamed_and_then_deleted(S3TablesClient tables) {
    String arn = tables.createTableBucket(request -> request.name("lifecycle")).arn();
    tables.createNamespace(request -> request.tableBucketARN(arn).namespace("db"));
    tables.createTable(request -> request.tableBucketARN(arn).namespace("db").name("before")
        .format(OpenTableFormat.ICEBERG).metadata(ORDERS));

    tables.renameTable(request -> request.tableBucketARN(arn).namespace("db").name("before").newName("after"));
    assertEquals(List.of("after"), tables.listTables(request -> request.tableBucketARN(arn))
        .tables().stream().map(table -> table.name()).toList());

    tables.deleteTable(request -> request.tableBucketARN(arn).namespace("db").name("after"));
    assertTrue(tables.listTables(request -> request.tableBucketARN(arn)).tables().isEmpty());
    tables.deleteNamespace(request -> request.tableBucketARN(arn).namespace("db"));
    tables.deleteTableBucket(request -> request.tableBucketARN(arn));
    assertThrows(NotFoundException.class, () -> tables.getTableBucket(request -> request.tableBucketARN(arn)));
  }

  @Test
  void what_isnt_there_is_a_not_found_and_a_name_that_is_taken_a_conflict(S3TablesClient tables) {
    String missing = "arn:aws:s3tables:us-east-1:000000000000:bucket/nope";
    assertThrows(NotFoundException.class, () -> tables.getTableBucket(request -> request.tableBucketARN(missing)));

    String arn = tables.createTableBucket(request -> request.name("errors")).arn();
    assertThrows(ConflictException.class, () -> tables.createTableBucket(request -> request.name("errors")));
    assertThrows(NotFoundException.class, () -> tables.listTables(request -> request.tableBucketARN(arn)
        .namespace("absent")));
    tables.createNamespace(request -> request.tableBucketARN(arn).namespace("db"));
    assertThrows(ConflictException.class, () ->
        tables.createNamespace(request -> request.tableBucketARN(arn).namespace("db")));
  }

  @Test
  void tags_and_the_configuration_of_a_table_bucket_round_trip(S3TablesClient tables) {
    String arn = tables.createTableBucket(request -> request.name("configured")
        .tags(Map.of("team", "data"))).arn();
    assertEquals(Map.of("team", "data"), tables.listTagsForResource(request -> request.resourceArn(arn)).tags());
    tables.tagResource(request -> request.resourceArn(arn).tags(Map.of("env", "test")));
    assertEquals(Map.of("team", "data", "env", "test"),
        tables.listTagsForResource(request -> request.resourceArn(arn)).tags());
    tables.untagResource(request -> request.resourceArn(arn).tagKeys("team"));
    assertEquals(Map.of("env", "test"), tables.listTagsForResource(request -> request.resourceArn(arn)).tags());

    // Nothing is encrypted, tiered or compacted here; what a client stores is what it reads back, so the code path
    // that configures a table bucket on the way to using it runs through.
    tables.putTableBucketEncryption(request -> request.tableBucketARN(arn)
        .encryptionConfiguration(configuration -> configuration.sseAlgorithm("AES256")));
    assertEquals("AES256", tables.getTableBucketEncryption(request -> request.tableBucketARN(arn))
        .encryptionConfiguration().sseAlgorithmAsString());
    tables.putTableBucketPolicy(request -> request.tableBucketARN(arn).resourcePolicy("{\"Version\":\"2012-10-17\"}"));
    assertEquals("{\"Version\":\"2012-10-17\"}",
        tables.getTableBucketPolicy(request -> request.tableBucketARN(arn)).resourcePolicy());
    assertNotNull(tables.getTableBucketMaintenanceConfiguration(request -> request.tableBucketARN(arn))
        .configuration());
  }

}
