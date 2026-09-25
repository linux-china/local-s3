package com.robothy.s3.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.rest.LocalS3;
import com.robothy.s3.rest.LocalS3Builder;
import com.robothy.s3.rest.LocalS3IcebergCatalog;
import com.robothy.s3.rest.admin.RequestStatistics;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.data.GenericRecord;
import org.apache.iceberg.data.IcebergGenerics;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.data.parquet.GenericParquetWriter;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.io.DataWriter;
import org.apache.iceberg.io.OutputFile;
import org.apache.iceberg.parquet.Parquet;
import org.apache.iceberg.rest.RESTCatalog;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The remote signing of the Iceberg REST catalog: an {@code S3FileIO} configured with
 * {@code s3.remote-signing-enabled} holds no usable credentials and asks the catalog to sign every S3 request, which
 * LocalS3 does with the credentials of the service. The service verifies signatures here, so a table that is written
 * and read back is proof that the signatures the catalog made are right.
 */
@Tag("data-tools")
class IcebergRemoteSigningIntegrationTest {

  private static final Schema SCHEMA = new Schema(
      Types.NestedField.required(1, "id", Types.LongType.get()),
      Types.NestedField.optional(2, "name", Types.StringType.get()));

  @Test
  @Timeout(60)
  void a_client_signs_at_the_route_of_the_table_that_the_catalog_vends() throws Exception {
    try (LocalS3 localS3 = start(LocalS3.builder().port(-1).icebergCatalog(true)
        .credentials("signing-key", "signing-secret"));
         RESTCatalog catalog = new RESTCatalog()) {
      catalog.initialize("local", new HashMap<>(Map.of(
          CatalogProperties.URI, "http://127.0.0.1:" + localS3.getPort() + "/iceberg",
          "s3.remote-signing-enabled", "true")));

      writeAndReadBack(catalog, TableIdentifier.of(Namespace.of("db"), "events"));

      assertTrue(count(localS3, "IcebergRemoteSign") > 0, "The S3 requests are signed at the route of the table.");
      assertEquals(0, count(localS3, "IcebergSignS3Request"));
    }
  }

  @Test
  @Timeout(60)
  void a_client_configured_by_hand_signs_at_the_default_route() throws Exception {
    try (LocalS3 localS3 = start(LocalS3.builder().port(-1).credentials("signing-key", "signing-secret")
        .icebergCatalog(iceberg -> iceberg.settings(new LocalS3IcebergCatalog("s3://warehouse/", true, false, false))));
         RESTCatalog catalog = new RESTCatalog()) {
      String endpoint = "http://127.0.0.1:" + localS3.getPort();
      // Nothing is vended, so the client names the storage itself, with credentials the service doesn't know: only
      // the signatures of the catalog get its requests in.
      catalog.initialize("local", new HashMap<>(Map.of(
          CatalogProperties.URI, endpoint + "/iceberg",
          "s3.endpoint", endpoint,
          "s3.path-style-access", "true",
          "client.region", "us-east-1",
          "s3.access-key-id", "unknown-key",
          "s3.secret-access-key", "unknown-secret",
          "s3.remote-signing-enabled", "true")));

      writeAndReadBack(catalog, TableIdentifier.of(Namespace.of("db"), "events"));

      assertTrue(count(localS3, "IcebergSignS3Request") > 0, "The S3 requests are signed at v1/aws/s3/sign.");
    }
  }

  private static void writeAndReadBack(RESTCatalog catalog, TableIdentifier identifier) throws IOException {
    catalog.createNamespace(identifier.namespace());
    Table table = catalog.createTable(identifier, SCHEMA, PartitionSpec.unpartitioned(),
        Map.of("format-version", "2"));
    table.newAppend().appendFile(write(table, List.of(record(1, "a"), record(2, "b"), record(3, "c")))).commit();

    Table loaded = catalog.loadTable(identifier);
    int rows = 0;
    try (CloseableIterable<Record> records = IcebergGenerics.read(loaded).build()) {
      for (Record ignored : records) {
        rows++;
      }
    }
    assertEquals(3, rows);
  }

  private static Record record(long id, String name) {
    Record record = GenericRecord.create(SCHEMA);
    record.setField("id", id);
    record.setField("name", name);
    return record;
  }

  private static DataFile write(Table table, List<Record> records) {
    OutputFile file = table.io().newOutputFile(table.location() + "/data/" + UUID.randomUUID() + ".parquet");
    try {
      DataWriter<Record> writer = Parquet.writeData(file)
          .schema(table.schema())
          .createWriterFunc(GenericParquetWriter::create)
          .overwrite()
          .withSpec(PartitionSpec.unpartitioned())
          .build();
      try {
        records.forEach(writer::write);
      } finally {
        writer.close();
      }
      return writer.toDataFile();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static long count(LocalS3 localS3, String operation) {
    RequestStatistics.OperationStatistics statistics = localS3.statistics().operations().get(operation);
    return statistics == null ? 0 : statistics.count();
  }

  private static LocalS3 start(LocalS3Builder builder) {
    LocalS3 localS3 = builder.netty(netty -> netty.registerShutdownHook(false)).build();
    localS3.start();
    return localS3;
  }

}
