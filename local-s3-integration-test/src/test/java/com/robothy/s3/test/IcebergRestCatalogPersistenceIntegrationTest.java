package com.robothy.s3.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.rest.LocalS3;
import com.robothy.s3.rest.bootstrap.LocalS3Mode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
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
import org.junit.jupiter.api.io.TempDir;

/**
 * The Iceberg catalog of a {@code PERSISTENCE} LocalS3, and of an {@code IN_MEMORY} one that starts from the same data
 * directory.
 *
 * <p>Nothing was built to persist the catalog: a table is a pointer in the store of the data directory and a pile of
 * objects in the warehouse bucket, and both halves are already persisted by the service. These tests are what shows
 * that — a table written by one service is read by the next one, and an {@code IN_MEMORY} service started from the
 * same directory reads it without ever writing to it.
 */
@Tag("data-tools")
class IcebergRestCatalogPersistenceIntegrationTest {

  private static final Schema SCHEMA = new Schema(
      Types.NestedField.required(1, "id", Types.LongType.get()),
      Types.NestedField.optional(2, "name", Types.StringType.get()));

  private static final Namespace DB = Namespace.of("db");

  private static final TableIdentifier ORDERS = TableIdentifier.of(DB, "orders");

  @Test
  @Timeout(180)
  void a_table_written_by_a_persistent_service_is_there_after_a_restart(@TempDir Path dataPath) throws IOException {
    withCatalog(LocalS3Mode.PERSISTENCE, dataPath, catalog -> {
      catalog.createNamespace(DB, new HashMap<>(Map.of("owner", "tester")));
      Table table = catalog.createTable(ORDERS, SCHEMA);
      append(table, records(1, 3));
    });

    // A new service over the same directory: it loads the catalog records and the objects from the disk.
    withCatalog(LocalS3Mode.PERSISTENCE, dataPath, catalog -> {
      assertTrue(catalog.namespaceExists(DB), "The namespace should have survived the restart.");
      assertEquals("tester", catalog.loadNamespaceMetadata(DB).get("owner"));
      assertEquals(List.of(ORDERS), catalog.listTables(DB));

      Table table = catalog.loadTable(ORDERS);
      assertNotNull(table.currentSnapshot(), "The snapshot of the append should have survived the restart.");
      assertEquals(List.of(1L, 2L, 3L), ids(table));

      // And the restarted service can be committed to, which is what proves its pointer is writable, not just read.
      append(table, records(4, 4));
      assertEquals(List.of(1L, 2L, 3L, 4L), ids(catalog.loadTable(ORDERS)));
    });

    withCatalog(LocalS3Mode.PERSISTENCE, dataPath, catalog ->
        assertEquals(List.of(1L, 2L, 3L, 4L), ids(catalog.loadTable(ORDERS))));
  }

  @Test
  @Timeout(180)
  void an_in_memory_service_reads_the_tables_of_a_data_path_and_never_writes_to_it(@TempDir Path dataPath)
      throws IOException {
    withCatalog(LocalS3Mode.PERSISTENCE, dataPath, catalog -> {
      catalog.createNamespace(DB);
      append(catalog.createTable(ORDERS, SCHEMA), records(1, 2));
    });

    // An IN_MEMORY service over the same directory starts from its tables and keeps its own changes in memory.
    withCatalog(LocalS3Mode.IN_MEMORY, dataPath, catalog -> {
      assertEquals(List.of(1L, 2L), ids(catalog.loadTable(ORDERS)));

      append(catalog.loadTable(ORDERS), records(3, 4));
      assertEquals(List.of(1L, 2L, 3L, 4L), ids(catalog.loadTable(ORDERS)));

      catalog.createNamespace(Namespace.of("scratch"));
      assertTrue(catalog.namespaceExists(Namespace.of("scratch")));
    });

    // The data path is untouched: the rows the in-memory service appended and the namespace it created are gone.
    withCatalog(LocalS3Mode.PERSISTENCE, dataPath, catalog -> {
      assertEquals(List.of(1L, 2L), ids(catalog.loadTable(ORDERS)),
          "An IN_MEMORY service must not have written to the data path it started from.");
      assertFalse(catalog.namespaceExists(Namespace.of("scratch")));
    });
  }

  /**
   * Start a LocalS3 with an Iceberg catalog over a data path, run the body against it, and shut it down, so that the
   * next service over the same path finds the directory released.
   */
  private static void withCatalog(LocalS3Mode mode, Path dataPath, Consumer<RESTCatalog> body) throws IOException {
    LocalS3 localS3 = LocalS3.builder()
        .port(-1)
        .mode(mode)
        .dataPath(dataPath.toString())
        .icebergCatalog(true)
        .registerShutdownHook(false)
        .build();
    localS3.start();
    RESTCatalog catalog = new RESTCatalog();
    try {
      catalog.initialize("local", Map.of(CatalogProperties.URI, "http://127.0.0.1:" + localS3.getPort() + "/iceberg"));
      body.accept(catalog);
    } finally {
      try {
        catalog.close();
      } finally {
        localS3.shutdown();
      }
    }
  }

  private static List<Long> ids(Table table) {
    List<Long> ids = new ArrayList<>();
    try (CloseableIterable<Record> rows = IcebergGenerics.read(table).build()) {
      rows.forEach(row -> ids.add((Long) row.getField("id")));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return ids.stream().sorted().toList();
  }

  private static List<Record> records(long firstId, long lastId) {
    List<Record> records = new ArrayList<>();
    GenericRecord template = GenericRecord.create(SCHEMA);
    for (long id = firstId; id <= lastId; id++) {
      Map<String, Object> values = new HashMap<>();
      values.put("id", id);
      values.put("name", "row-" + id);
      records.add(template.copy(values));
    }
    return records;
  }

  private static void append(Table table, List<Record> records) {
    table.newAppend().appendFile(write(table, records)).commit();
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

}
