package com.robothy.s3.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.jupiter.LocalS3;
import com.robothy.s3.jupiter.LocalS3Endpoint;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.data.GenericRecord;
import org.apache.iceberg.exceptions.NoSuchWarehouseException;
import org.apache.iceberg.rest.RESTCatalog;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import software.amazon.awssdk.services.s3tables.S3TablesClient;
import software.amazon.awssdk.services.s3tables.model.GetTableResponse;
import software.amazon.awssdk.services.s3tables.model.OpenTableFormat;

/**
 * The two halves of a table bucket, against one another: the control plane of the S3 Tables API and the
 * <a href="https://docs.aws.amazon.com/AmazonS3/latest/userguide/s3-tables-integrating-open-source.html">Iceberg REST
 * endpoint</a> of the same table bucket, reached the way Amazon documents it — the ARN of the table bucket as the
 * warehouse of a {@code RESTCatalog}.
 *
 * <p>This is the test that matters most about this API: a table that an engine creates over REST must be the table that
 * {@code GetTable} answers, and a commit made through either must be what the other then reads. Two stores that only
 * looked alike would pass every test of each half on its own and fail here.
 */
@Tag("data-tools")
@LocalS3(icebergCatalog = true)
class S3TablesIcebergRestEndpointTest {

  private static final Schema SCHEMA = new Schema(
      Types.NestedField.required(1, "id", Types.LongType.get()),
      Types.NestedField.optional(2, "name", Types.StringType.get()));

  @Test
  @Timeout(120)
  void an_engine_reaches_a_table_bucket_by_its_arn_as_the_warehouse(S3TablesClient tables, LocalS3Endpoint endpoint)
      throws IOException {
    String arn = tables.createTableBucket(request -> request.name("lakehouse")).arn();
    tables.createNamespace(request -> request.tableBucketARN(arn).namespace("shop"));

    try (RESTCatalog catalog = restCatalog(endpoint, arn)) {
      // The namespace the control plane created is the namespace the engine sees: one catalog, two ways in.
      assertEquals(List.of(Namespace.of("shop")), catalog.listNamespaces());

      TableIdentifier identifier = TableIdentifier.of(Namespace.of("shop"), "orders");
      Table table = catalog.createTable(identifier, SCHEMA);
      assertTrue(table.location().startsWith("s3://lakehouse--table-s3/shop/orders"), table.location());

      // And the table the engine created is the table the control plane answers, at the metadata file it wrote.
      GetTableResponse answered = tables.getTable(request -> request.tableBucketARN(arn)
          .namespace("shop").name("orders"));
      assertEquals("orders", answered.name());
      assertEquals(OpenTableFormat.ICEBERG, answered.format());
      assertEquals(((org.apache.iceberg.BaseTable) table).operations().current().metadataFileLocation(),
          answered.metadataLocation());
    }
  }

  @Test
  @Timeout(120)
  void a_table_created_through_the_control_plane_is_loaded_by_the_engine(S3TablesClient tables,
                                                                        LocalS3Endpoint endpoint) throws IOException {
    String arn = tables.createTableBucket(request -> request.name("both-ways")).arn();
    tables.createNamespace(request -> request.tableBucketARN(arn).namespace("db"));
    tables.createTable(request -> request.tableBucketARN(arn).namespace("db").name("people")
        .format(OpenTableFormat.ICEBERG)
        .metadata(metadata -> metadata.iceberg(iceberg -> iceberg.schema(schema -> schema.fields(
            field -> field.name("id").type("long").required(true),
            field -> field.name("name").type("string"))))));

    try (RESTCatalog catalog = restCatalog(endpoint, arn)) {
      Table table = catalog.loadTable(TableIdentifier.of(Namespace.of("db"), "people"));
      assertEquals(List.of("id", "name"), table.schema().columns().stream()
          .map(Types.NestedField::name).toList());
      assertNotNull(GenericRecord.create(table.schema()));

      // A commit over REST moves the pointer that the control plane then answers.
      String before = tables.getTableMetadataLocation(request -> request.tableBucketARN(arn)
          .namespace("db").name("people")).metadataLocation();
      table.updateProperties().set("owner", "sales").commit();
      String after = tables.getTableMetadataLocation(request -> request.tableBucketARN(arn)
          .namespace("db").name("people")).metadataLocation();
      assertTrue(!before.equals(after), "The commit should have moved the metadata pointer.");
      assertEquals("sales", catalog.loadTable(TableIdentifier.of(Namespace.of("db"), "people"))
          .properties().get("owner"));
    }
  }

  @Test
  @Timeout(120)
  void the_table_buckets_are_catalogs_of_their_own(S3TablesClient tables, LocalS3Endpoint endpoint)
      throws IOException {
    String first = tables.createTableBucket(request -> request.name("first")).arn();
    String second = tables.createTableBucket(request -> request.name("second")).arn();
    tables.createNamespace(request -> request.tableBucketARN(first).namespace("db"));
    tables.createNamespace(request -> request.tableBucketARN(second).namespace("db"));

    try (RESTCatalog one = restCatalog(endpoint, first); RESTCatalog two = restCatalog(endpoint, second)) {
      one.createTable(TableIdentifier.of(Namespace.of("db"), "only_in_first"), SCHEMA);
      assertEquals(List.of(TableIdentifier.of(Namespace.of("db"), "only_in_first")),
          one.listTables(Namespace.of("db")));
      // The same namespace name in another table bucket is another namespace, and holds nothing.
      assertTrue(two.listTables(Namespace.of("db")).isEmpty());
    }
  }

  @Test
  @Timeout(120)
  void the_warehouse_of_a_table_bucket_that_isnt_there_is_refused(LocalS3Endpoint endpoint) {
    String missing = "arn:aws:s3tables:us-east-1:000000000000:bucket/never-created";
    // Not silently answered with the default catalog of the service, which would leave the engine writing its tables
    // somewhere it never asked for.
    assertThrows(NoSuchWarehouseException.class, () -> {
      try (RESTCatalog catalog = restCatalog(endpoint, missing)) {
        catalog.listNamespaces();
      }
    });
  }

  /**
   * A {@code RESTCatalog} configured the way Amazon S3 Tables documents its Iceberg REST endpoint: the endpoint under
   * {@code /iceberg}, and the ARN of the table bucket as the warehouse. The service answers the prefix of that table
   * bucket's catalog, and the client puts it in every path it then requests.
   */
  private static RESTCatalog restCatalog(LocalS3Endpoint endpoint, String tableBucketArn) {
    RESTCatalog catalog = new RESTCatalog();
    catalog.initialize("s3tables", Map.of(
        CatalogProperties.URI, endpoint.icebergCatalogUri(),
        CatalogProperties.WAREHOUSE_LOCATION, tableBucketArn));
    return catalog;
  }

}
