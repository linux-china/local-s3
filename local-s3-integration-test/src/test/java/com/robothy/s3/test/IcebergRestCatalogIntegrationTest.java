package com.robothy.s3.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.rest.LocalS3;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.Table;
import org.apache.iceberg.Transaction;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.data.GenericRecord;
import org.apache.iceberg.data.IcebergGenerics;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.data.parquet.GenericParquetWriter;
import org.apache.iceberg.exceptions.AlreadyExistsException;
import org.apache.iceberg.exceptions.CommitFailedException;
import org.apache.iceberg.exceptions.NoSuchNamespaceException;
import org.apache.iceberg.exceptions.NoSuchTableException;
import org.apache.iceberg.expressions.Expressions;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.io.DataWriter;
import org.apache.iceberg.io.OutputFile;
import org.apache.iceberg.parquet.Parquet;
import org.apache.iceberg.rest.RESTCatalog;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The Iceberg REST catalog that LocalS3 serves, driven by the real {@link RESTCatalog} of Apache Iceberg — the same
 * client that Spark, Trino, Flink and PyIceberg speak to a catalog with.
 *
 * <p>These tests are the ones that decide whether the catalog is right. LocalS3 builds the table metadata itself,
 * without the Iceberg library, so nothing but a real client reading it back proves that what it writes is a table:
 * that the schema survives a round trip, that {@code last-column-id} and the other assertions a commit makes are the
 * ones the client expects, that an append produces a snapshot the client can scan, and that two writers racing for the
 * same table end with one {@link CommitFailedException} rather than one of them silently losing its data.
 *
 * <p>The catalog is configured with its URI alone. Everything else — the S3 endpoint, path-style access and the
 * credentials — the catalog vends, which is the point of running it inside the object store.
 */
@Tag("data-tools")
class IcebergRestCatalogIntegrationTest {

  private static final Schema SCHEMA = new Schema(
      Types.NestedField.required(1, "id", Types.LongType.get()),
      Types.NestedField.optional(2, "name", Types.StringType.get()),
      Types.NestedField.optional(3, "level", Types.StringType.get()));

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
    // The URI is the whole configuration: the endpoint and the credentials of the storage are vended by the catalog.
    catalog.initialize("local", Map.of(CatalogProperties.URI, "http://127.0.0.1:" + localS3.getPort() + "/iceberg"));
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

  @Test
  @Timeout(60)
  void namespaces_are_created_listed_and_dropped() {
    Namespace db = Namespace.of("db");
    // A HashMap, not Map.of(): the Iceberg client probes the properties with containsKey(null), which the immutable
    // maps of the JDK answer with a NullPointerException.
    catalog.createNamespace(db, new HashMap<>(Map.of("owner", "tester")));

    assertTrue(catalog.namespaceExists(db));
    assertTrue(catalog.listNamespaces().contains(db));
    assertEquals("tester", catalog.loadNamespaceMetadata(db).get("owner"));

    // A nested namespace is listed under its parent, and not beside it.
    Namespace nested = Namespace.of("db", "schema");
    catalog.createNamespace(nested);
    assertEquals(List.of(nested), catalog.listNamespaces(db));
    assertFalse(catalog.listNamespaces().contains(nested));

    assertThrows(AlreadyExistsException.class, () -> catalog.createNamespace(db));
    assertThrows(NoSuchNamespaceException.class, () -> catalog.loadNamespaceMetadata(Namespace.of("nope")));

    catalog.setProperties(db, new HashMap<>(Map.of("tier", "bronze")));
    catalog.removeProperties(db, new java.util.HashSet<>(java.util.Set.of("owner")));
    Map<String, String> properties = catalog.loadNamespaceMetadata(db);
    assertFalse(properties.containsKey("owner"));
    assertEquals("bronze", properties.get("tier"));

    catalog.dropNamespace(nested);
    assertFalse(catalog.namespaceExists(nested));
  }

  @Test
  @Timeout(60)
  void a_created_table_round_trips_through_the_catalog() {
    Namespace db = Namespace.of("db");
    catalog.createNamespace(db);
    TableIdentifier identifier = TableIdentifier.of(db, "events");

    Table created = catalog.createTable(identifier, SCHEMA, PartitionSpec.unpartitioned(),
        Map.of("format-version", "2", "owner", "tester"));
    assertNotNull(created.uuid());
    assertEquals("s3://warehouse/db/events", created.location());
    assertEquals("tester", created.properties().get("owner"));

    // Loaded again through the catalog, i.e. read back from the metadata file that LocalS3 wrote.
    Table loaded = catalog.loadTable(identifier);
    assertEquals(created.uuid(), loaded.uuid());
    assertEquals(SCHEMA.asStruct(), loaded.schema().asStruct());
    assertEquals(3, loaded.schema().columns().size());
    assertTrue(loaded.spec().isUnpartitioned());
    assertEquals(List.of(identifier), catalog.listTables(db));
    assertTrue(catalog.tableExists(identifier));

    assertThrows(AlreadyExistsException.class, () -> catalog.createTable(identifier, SCHEMA));
    assertThrows(NoSuchTableException.class, () -> catalog.loadTable(TableIdentifier.of(db, "missing")));
  }

  @Test
  @Timeout(120)
  void rows_written_to_a_table_are_read_back_through_the_catalog() throws IOException {
    Namespace db = Namespace.of("db");
    catalog.createNamespace(db);
    TableIdentifier identifier = TableIdentifier.of(db, "logs");
    Table table = catalog.createTable(identifier, SCHEMA);

    append(table, records(1, 3, "info"));

    table.refresh();
    Snapshot snapshot = table.currentSnapshot();
    assertNotNull(snapshot, "The append should have produced a snapshot.");
    assertEquals("append", snapshot.operation());
    assertEquals("3", snapshot.summary().get("added-records"));

    List<Long> ids = new ArrayList<>();
    try (CloseableIterable<Record> rows = IcebergGenerics.read(catalog.loadTable(identifier)).build()) {
      rows.forEach(row -> ids.add((Long) row.getField("id")));
    }
    assertEquals(List.of(1L, 2L, 3L), ids.stream().sorted().toList());

    // A second append adds a snapshot without losing the first one, and the history records both.
    append(table, records(4, 5, "warn"));
    table.refresh();
    assertEquals(2, table.history().size());
    assertEquals(5, count(catalog.loadTable(identifier), null));
    assertEquals(3, count(catalog.loadTable(identifier), Expressions.equal("level", "info")));
  }

  @Test
  @Timeout(120)
  void an_earlier_snapshot_is_still_readable_after_a_later_append() throws IOException {
    Namespace db = Namespace.of("db");
    catalog.createNamespace(db);
    Table table = catalog.createTable(TableIdentifier.of(db, "travel"), SCHEMA);

    append(table, records(1, 2, "first"));
    table.refresh();
    long firstSnapshot = table.currentSnapshot().snapshotId();

    append(table, records(3, 4, "second"));
    table.refresh();

    assertEquals(4, count(table, null));
    // Time travel: the snapshot log that LocalS3 keeps is what makes the earlier state addressable.
    List<Long> earlier = new ArrayList<>();
    try (CloseableIterable<Record> rows = IcebergGenerics.read(table).useSnapshot(firstSnapshot).build()) {
      rows.forEach(row -> earlier.add((Long) row.getField("id")));
    }
    assertEquals(List.of(1L, 2L), earlier.stream().sorted().toList());
  }

  @Test
  @Timeout(120)
  void concurrent_appends_never_lose_a_writer_s_rows() throws Exception {
    Namespace db = Namespace.of("db");
    catalog.createNamespace(db);
    TableIdentifier identifier = TableIdentifier.of(db, "contended");
    catalog.createTable(identifier, SCHEMA, PartitionSpec.unpartitioned(),
        new HashMap<>(Map.of("commit.retry.num-retries", "20")));

    int writers = 4;
    // Every writer reads the table, writes its file and then commits at the same moment, so their commits collide.
    // The Iceberg client answers a rejected commit by refreshing and retrying, so what a correct catalog produces is
    // every writer's rows and one snapshot each; a catalog that let a stale commit through would drop some of them.
    CyclicBarrier ready = new CyclicBarrier(writers);
    AtomicInteger committed = new AtomicInteger();
    ExecutorService executor = Executors.newFixedThreadPool(writers);
    try {
      List<Future<?>> futures = new ArrayList<>();
      for (int writer = 0; writer < writers; writer++) {
        long id = (writer + 1) * 10L;
        futures.add(executor.submit(() -> {
          Table table = catalog.loadTable(identifier);
          DataFile file = write(table, records(id, id, "w" + id));
          ready.await(60, TimeUnit.SECONDS);
          table.newAppend().appendFile(file).commit();
          committed.incrementAndGet();
          return null;
        }));
      }
      for (Future<?> future : futures) {
        future.get(90, TimeUnit.SECONDS);
      }
    } finally {
      executor.shutdownNow();
    }

    assertEquals(writers, committed.get());
    Table table = catalog.loadTable(identifier);
    assertEquals(writers, table.history().size(), "Each append should have produced a snapshot of its own.");
    assertEquals(writers, count(table, null), "No writer's rows may be lost to a concurrent commit.");

    List<Long> ids = new ArrayList<>();
    try (CloseableIterable<Record> rows = IcebergGenerics.read(table).build()) {
      rows.forEach(row -> ids.add((Long) row.getField("id")));
    }
    assertEquals(List.of(10L, 20L, 30L, 40L), ids.stream().sorted().toList());
  }

  @Test
  @Timeout(120)
  void a_transaction_creates_the_table_only_once_its_data_is_written() throws IOException {
    Namespace db = Namespace.of("db");
    catalog.createNamespace(db);
    TableIdentifier identifier = TableIdentifier.of(db, "staged");

    // The staged create is what CREATE TABLE AS SELECT uses: nothing exists until the transaction commits.
    Transaction transaction = catalog.buildTable(identifier, SCHEMA).createTransaction();
    DataFile file = write(transaction.table(), records(1, 2, "staged"));
    assertFalse(catalog.tableExists(identifier), "A staged create should not have created the table yet.");

    transaction.newAppend().appendFile(file).commit();
    transaction.commitTransaction();

    assertTrue(catalog.tableExists(identifier));
    Table table = catalog.loadTable(identifier);
    assertNotNull(table.currentSnapshot());
    assertEquals(2, count(table, null));
  }

  @Test
  @Timeout(60)
  void a_schema_change_is_committed_and_read_back() {
    Namespace db = Namespace.of("db");
    catalog.createNamespace(db);
    TableIdentifier identifier = TableIdentifier.of(db, "evolving");
    Table table = catalog.createTable(identifier, SCHEMA);

    table.updateSchema().addColumn("score", Types.DoubleType.get()).commit();
    table.updateProperties().set("owner", "tester").commit();

    Table reloaded = catalog.loadTable(identifier);
    assertNotNull(reloaded.schema().findField("score"), "The added column should survive the commit.");
    assertEquals(4, reloaded.schema().columns().size());
    assertEquals("tester", reloaded.properties().get("owner"));
    // The id of the new column must follow the ones of the table, which the catalog tracks as last-column-id.
    assertEquals(4, reloaded.schema().findField("score").fieldId());
  }

  @Test
  @Timeout(60)
  void a_partitioned_table_keeps_its_spec() {
    Namespace db = Namespace.of("db");
    catalog.createNamespace(db);
    TableIdentifier identifier = TableIdentifier.of(db, "partitioned");
    PartitionSpec spec = PartitionSpec.builderFor(SCHEMA).identity("level").build();

    catalog.createTable(identifier, SCHEMA, spec);

    Table loaded = catalog.loadTable(identifier);
    assertFalse(loaded.spec().isUnpartitioned());
    assertEquals(1, loaded.spec().fields().size());
    assertEquals("level", loaded.spec().fields().get(0).name());
    assertEquals(1000, loaded.spec().fields().get(0).fieldId());
  }

  @Test
  @Timeout(60)
  void tables_are_renamed_and_dropped() {
    Namespace db = Namespace.of("db");
    catalog.createNamespace(db);
    TableIdentifier from = TableIdentifier.of(db, "before");
    TableIdentifier to = TableIdentifier.of(db, "after");
    Table table = catalog.createTable(from, SCHEMA);
    String uuid = table.uuid().toString();

    catalog.renameTable(from, to);
    assertFalse(catalog.tableExists(from));
    assertEquals(uuid, catalog.loadTable(to).uuid().toString());

    assertTrue(catalog.dropTable(to, false));
    assertFalse(catalog.tableExists(to));
    assertFalse(catalog.dropTable(to, false), "Dropping a table that is gone should answer false.");
  }

  /*
   * The helpers that write Parquet rows into a table, the way an engine does.
   */

  private static List<Record> records(long firstId, long lastId, String level) {
    List<Record> records = new ArrayList<>();
    GenericRecord template = GenericRecord.create(SCHEMA);
    for (long id = firstId; id <= lastId; id++) {
      Map<String, Object> values = new HashMap<>();
      values.put("id", id);
      values.put("name", "row-" + id);
      values.put("level", level);
      records.add(template.copy(values));
    }
    return records;
  }

  private static void append(Table table, List<Record> records) throws IOException {
    table.newAppend().appendFile(write(table, records)).commit();
  }

  /**
   * Write the records as one Parquet file under the table, through the {@code FileIO} that the catalog vended, and
   * describe it as a {@link DataFile} that a commit can add.
   */
  private static DataFile write(Table table, List<Record> records) {
    OutputFile file = table.io().newOutputFile(
        table.location() + "/data/" + UUID.randomUUID() + ".parquet");
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
        // The metrics of the file are only complete once the footer is written, so toDataFile() needs it closed.
        writer.close();
      }
      return writer.toDataFile();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static int count(Table table, org.apache.iceberg.expressions.Expression filter) throws IOException {
    IcebergGenerics.ScanBuilder scan = IcebergGenerics.read(table);
    if (filter != null) {
      scan = scan.where(filter);
    }
    int count = 0;
    try (CloseableIterable<Record> rows = scan.build()) {
      for (Record ignored : rows) {
        count++;
      }
    }
    return count;
  }

}
