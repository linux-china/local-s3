package com.robothy.s3.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.jupiter.LocalS3;
import com.robothy.s3.jupiter.LocalS3Endpoint;
import java.io.IOException;
import java.util.Map;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.RESTCatalog;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * The Iceberg REST catalog reached through the JUnit 5 extension, which is how a test is meant to get one: one
 * attribute of {@code @LocalS3}, and the injected endpoint tells the client where the catalog is. The service and
 * its catalog are shared by the methods of the class and shut down afterwards, like any {@code @LocalS3} service.
 */
@Tag("data-tools")
@LocalS3(icebergCatalog = true)
class IcebergCatalogJupiterTest {

  private static final Schema SCHEMA = new Schema(
      Types.NestedField.required(1, "id", Types.LongType.get()),
      Types.NestedField.optional(2, "name", Types.StringType.get()));

  @Test
  @Timeout(60)
  void the_annotation_serves_a_catalog_that_a_client_can_use(LocalS3Endpoint endpoint) throws IOException {
    try (RESTCatalog catalog = new RESTCatalog()) {
      catalog.initialize("local", Map.of(CatalogProperties.URI, endpoint.icebergCatalogUri()));

      catalog.createNamespace(Namespace.of("db"));
      TableIdentifier identifier = TableIdentifier.of(Namespace.of("db"), "orders");
      Table table = catalog.createTable(identifier, SCHEMA);

      assertEquals("s3://warehouse/db/orders", table.location());
      assertTrue(catalog.tableExists(identifier));
      assertEquals(2, catalog.loadTable(identifier).schema().columns().size());
    }
  }

  @Test
  @Timeout(60)
  void the_warehouse_bucket_is_an_ordinary_bucket_of_the_same_service(S3Client s3, LocalS3Endpoint endpoint)
      throws IOException {
    try (RESTCatalog catalog = new RESTCatalog()) {
      catalog.initialize("local", Map.of(CatalogProperties.URI, endpoint.icebergCatalogUri()));
      catalog.createNamespace(Namespace.of("shop"));
      catalog.createTable(TableIdentifier.of(Namespace.of("shop"), "items"), SCHEMA);

      // The catalog stores its tables in this service, so the metadata file is visible as an object like any other.
      assertTrue(s3.listBuckets().buckets().stream().anyMatch(bucket -> "warehouse".equals(bucket.name())));
      assertTrue(s3.listObjectsV2(request -> request.bucket("warehouse").prefix("shop/items/metadata/"))
              .contents().stream().anyMatch(object -> object.key().endsWith(".metadata.json")),
          "The table metadata should be an object of the warehouse bucket.");
    }
  }

}
