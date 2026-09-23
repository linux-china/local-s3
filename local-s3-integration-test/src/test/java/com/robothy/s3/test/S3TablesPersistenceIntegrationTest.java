package com.robothy.s3.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.rest.LocalS3;
import com.robothy.s3.rest.bootstrap.LocalS3Mode;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3tables.S3TablesClient;
import software.amazon.awssdk.services.s3tables.model.ListTableBucketsRequest;
import software.amazon.awssdk.services.s3tables.model.NotFoundException;
import software.amazon.awssdk.services.s3tables.model.OpenTableFormat;

/**
 * The table buckets of a {@code PERSISTENCE} LocalS3, and of an {@code IN_MEMORY} one that starts from the same data
 * directory.
 *
 * <p>Nothing was built to persist them: a table bucket is a record in the store of the data directory, its tables are
 * pointers in the same store, and the files those pointers name are objects of a bucket of the service — all three are
 * already persisted by the service. These tests are what shows it: a table created by one service is the table the next
 * one answers, and an {@code IN_MEMORY} service started from the same directory reads it without writing to it.
 */
class S3TablesPersistenceIntegrationTest {

  @Test
  void a_table_bucket_survives_a_restart(@TempDir Path dataPath) {
    withService(dataPath, LocalS3Mode.PERSISTENCE, tables -> {
      String arn = tables.createTableBucket(request -> request.name("kept")).arn();
      tables.createNamespace(request -> request.tableBucketARN(arn).namespace("db"));
      tables.createTable(request -> request.tableBucketARN(arn).namespace("db").name("orders")
          .format(OpenTableFormat.ICEBERG)
          .metadata(metadata -> metadata.iceberg(iceberg -> iceberg.schema(schema -> schema.fields(
              field -> field.name("id").type("long").required(true))))));
    });

    withService(dataPath, LocalS3Mode.PERSISTENCE, tables -> {
      assertEquals(List.of("kept"), tables.listTableBuckets(ListTableBucketsRequest.builder().build())
          .tableBuckets().stream().map(bucket -> bucket.name()).toList());
      String arn = "arn:aws:s3tables:us-east-1:000000000000:bucket/kept";
      assertEquals(List.of("orders"), tables.listTables(request -> request.tableBucketARN(arn))
          .tables().stream().map(table -> table.name()).toList());
      assertTrue(tables.getTable(request -> request.tableBucketARN(arn).namespace("db").name("orders"))
          .metadataLocation().endsWith(".metadata.json"));
    });
  }

  @Test
  void an_in_memory_service_starts_from_a_data_directory_and_never_writes_to_it(@TempDir Path dataPath) {
    withService(dataPath, LocalS3Mode.PERSISTENCE, tables -> {
      String arn = tables.createTableBucket(request -> request.name("initial")).arn();
      tables.createNamespace(request -> request.tableBucketARN(arn).namespace("db"));
    });

    withService(dataPath, LocalS3Mode.IN_MEMORY, tables -> {
      String arn = "arn:aws:s3tables:us-east-1:000000000000:bucket/initial";
      assertEquals("initial", tables.getTableBucket(request -> request.tableBucketARN(arn)).name());
      assertEquals(List.of(List.of("db")), tables.listNamespaces(request -> request.tableBucketARN(arn))
          .namespaces().stream().map(namespace -> namespace.namespace()).toList());
      // Changed only in memory.
      tables.createTableBucket(request -> request.name("in-memory-only"));
    });

    withService(dataPath, LocalS3Mode.PERSISTENCE, tables -> assertEquals(List.of("initial"),
        tables.listTableBuckets(ListTableBucketsRequest.builder().build())
            .tableBuckets().stream().map(bucket -> bucket.name()).toList()));
  }

  @Test
  void a_reset_drops_the_table_buckets_of_an_in_memory_service() {
    try (LocalS3 service = LocalS3.builder().port(-1).mode(LocalS3Mode.IN_MEMORY).build()) {
      service.start();
      try (S3TablesClient tables = client(service.getPort())) {
        String arn = tables.createTableBucket(request -> request.name("temporary")).arn();
        tables.createNamespace(request -> request.tableBucketARN(arn).namespace("db"));

        service.reset();

        assertTrue(tables.listTableBuckets(ListTableBucketsRequest.builder().build()).tableBuckets().isEmpty());
        assertThrows(NotFoundException.class, () -> tables.getTableBucket(request -> request.tableBucketARN(arn)));
        // And the name is free again, with a catalog that holds none of the namespaces it held before.
        String again = tables.createTableBucket(request -> request.name("temporary")).arn();
        assertTrue(tables.listNamespaces(request -> request.tableBucketARN(again)).namespaces().isEmpty());
      }
    }
  }

  private static void withService(Path dataPath, LocalS3Mode mode, Consumer<S3TablesClient> test) {
    try (LocalS3 service = LocalS3.builder().port(-1).mode(mode).dataPath(dataPath.toString()).build()) {
      service.start();
      try (S3TablesClient tables = client(service.getPort())) {
        test.accept(tables);
      }
    }
  }

  /**
   * A client of a service without credentials, which signs nothing and so reaches the API under its path; see
   * {@code S3TablesController}.
   */
  private static S3TablesClient client(int port) {
    return S3TablesClient.builder()
        .endpointOverride(URI.create("http://localhost:" + port + "/s3tables"))
        .region(Region.US_EAST_1)
        .credentialsProvider(AnonymousCredentialsProvider.create())
        .build();
  }

}
