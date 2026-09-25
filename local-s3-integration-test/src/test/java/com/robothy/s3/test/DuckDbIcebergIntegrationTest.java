package com.robothy.s3.test;

import static com.robothy.s3.test.DuckDb.column;
import static com.robothy.s3.test.DuckDb.execute;
import static com.robothy.s3.test.DuckDb.rows;
import static com.robothy.s3.test.DuckDb.single;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.rest.LocalS3;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.iceberg.BaseTable;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.FileScanTask;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.data.GenericRecord;
import org.apache.iceberg.data.IcebergGenerics;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.data.parquet.GenericParquetWriter;
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
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3tables.S3TablesClient;
import software.amazon.awssdk.services.s3tables.model.TableSummary;

/**
 * DuckDB on the built-in Iceberg REST catalog of LocalS3, the link that the "IDE with an embedded S3 and DuckDB"
 * scenario rests on: one process serves the catalog and the storage, and DuckDB reaches both with an {@code ATTACH}.
 *
 * <pre>{@code
 * ATTACH 'warehouse' AS ice (TYPE ICEBERG, ENDPOINT 'http://localhost:29090/iceberg', AUTHORIZATION_TYPE 'none');
 * CREATE SCHEMA ice.db;
 * CREATE TABLE ice.db.events AS SELECT ...;
 * }</pre>
 *
 * <p><b>No S3 secret is created anywhere in this test.</b> DuckDB is configured with the URI of the catalog alone, and
 * the endpoint, the path-style addressing and the credentials of the storage reach it through the credential vending
 * of the REST protocol — which {@link #the_catalog_vends_the_storage_credentials_to_duckdb()} reads back out of
 * {@code duckdb_secrets()}. That is the whole point of running the catalog inside the object store.
 *
 * <p>The tests that matter most are the crossing ones: what DuckDB writes is read back with the Iceberg Java client,
 * and what that client writes is read by DuckDB. LocalS3 builds its table metadata itself, so a table is only a table
 * if two independent implementations agree on it, and the two here share nothing but the bytes in the bucket.
 *
 * <p>The {@code httpfs} and {@code iceberg} extensions are loaded from the extension directory of DuckDB, and
 * installed from its extension repository if they aren't there; without either, the tests are skipped. See
 * {@link DuckDb}.
 */
@Tag("data-tools")
@Timeout(value = 5, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class DuckDbIcebergIntegrationTest {

  /**
   * The warehouse bucket that the catalog of LocalS3 creates on startup and writes its tables into.
   */
  private static final String WAREHOUSE = "warehouse";

  private static final String ACCESS_KEY = "duck-key";

  private static final String SECRET_KEY = "duck-secret";

  private static final Schema SCHEMA = new Schema(
      Types.NestedField.required(1, "id", Types.LongType.get()),
      Types.NestedField.optional(2, "name", Types.StringType.get()),
      Types.NestedField.optional(3, "level", Types.StringType.get()));

  private LocalS3 localS3;

  private RESTCatalog catalog;

  private S3Client s3;

  private Connection duckdb;

  @BeforeEach
  void setUp() throws SQLException {
    localS3 = LocalS3.builder()
        .port(-1)
        // With a key pair, LocalS3 verifies the signature of every S3 request, so DuckDB only reaches the storage if
        // it actually uses the credentials the catalog vended it.
        .credentials(ACCESS_KEY, SECRET_KEY)
        .icebergCatalog(true)
        .netty(netty -> netty.registerShutdownHook(false))
        .build();
    localS3.start();

    catalog = new RESTCatalog();
    catalog.initialize("local", Map.of(CatalogProperties.URI, catalogUri()));
    s3 = S3Client.builder()
        .endpointOverride(URI.create("http://127.0.0.1:" + localS3.getPort()))
        .region(Region.US_EAST_1)
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
        .forcePathStyle(true)
        .build();

    duckdb = DriverManager.getConnection("jdbc:duckdb:");
    DuckDb.loadExtension(duckdb, "httpfs");
    DuckDb.loadExtension(duckdb, "iceberg");
    // The URI of the catalog is the whole configuration; AUTHORIZATION_TYPE 'none' only tells DuckDB not to fetch an
    // OAuth2 token first, which it would otherwise insist on before the first request.
    execute(duckdb, "ATTACH '" + WAREHOUSE + "' AS ice (TYPE ICEBERG, ENDPOINT '" + catalogUri()
        + "', AUTHORIZATION_TYPE 'none')");
  }

  @AfterEach
  void tearDown() throws SQLException, IOException {
    // LocalS3 first, so that a DuckDB thread still reading from it fails fast rather than hanging on a closed pool.
    if (localS3 != null) {
      localS3.shutdown();
    }
    if (catalog != null) {
      catalog.close();
    }
    if (s3 != null) {
      s3.close();
    }
    if (duckdb != null) {
      duckdb.close();
    }
  }

  /**
   * A table that DuckDB creates through the catalog is an Iceberg table of that catalog: the Iceberg Java client lists
   * it, reads its schema and its rows, and the metadata and data files are objects of the warehouse bucket.
   */
  @Test
  void a_table_created_by_duckdb_is_a_table_of_the_catalog() throws Exception {
    execute(duckdb, "CREATE SCHEMA ice.db");
    execute(duckdb, "CREATE TABLE ice.db.events (id BIGINT, name VARCHAR, level VARCHAR)");
    execute(duckdb, "INSERT INTO ice.db.events VALUES (1, 'row-1', 'info'), (2, 'row-2', 'warn'), (3, 'row-3', 'info')");

    assertEquals(List.of(List.of(1L, "row-1", "info"), List.of(2L, "row-2", "warn"), List.of(3L, "row-3", "info")),
        rows(duckdb, "SELECT id, name, level FROM ice.db.events ORDER BY id"));

    // The same table, through the Iceberg Java client: the namespace, the schema and the rows.
    TableIdentifier identifier = TableIdentifier.of(Namespace.of("db"), "events");
    assertTrue(catalog.namespaceExists(Namespace.of("db")), "CREATE SCHEMA must have created the namespace.");
    assertEquals(List.of(identifier), catalog.listTables(Namespace.of("db")));
    Table table = catalog.loadTable(identifier);
    assertEquals(List.of("id", "name", "level"), table.schema().columns().stream().map(Types.NestedField::name).toList());
    assertEquals("s3://" + WAREHOUSE + "/db/events", table.location());
    assertEquals(List.of(1L, 2L, 3L), ids(table));

    // And the files that hold it are objects of the warehouse bucket of LocalS3.
    List<String> objects = keys("db/events/");
    assertTrue(objects.stream().anyMatch(key -> key.endsWith(".metadata.json")), objects.toString());
    assertTrue(objects.stream().anyMatch(key -> key.startsWith("db/events/metadata/snap-")), objects.toString());
    assertTrue(objects.stream().anyMatch(key -> key.endsWith(".parquet")), objects.toString());
  }

  /**
   * DuckDB is never given an S3 secret here. It reaches the storage because the catalog vends it: {@code GET
   * /v1/config} carries the endpoint and the path-style addressing, and every loaded table the credentials of LocalS3,
   * out of which DuckDB makes an S3 secret of its own, scoped to the location of the table.
   */
  @Test
  void the_catalog_vends_the_storage_credentials_to_duckdb() throws Exception {
    assertEquals(List.of(), rows(duckdb, "SELECT name FROM duckdb_secrets() WHERE type = 's3'"),
        "The test creates no S3 secret; everything DuckDB knows about the storage must come from the catalog.");

    execute(duckdb, "CREATE SCHEMA ice.db");
    execute(duckdb, "CREATE TABLE ice.db.vended AS SELECT range AS id FROM range(100)");
    assertEquals(100L, single(duckdb, "SELECT count(*) FROM ice.db.vended"));

    List<String> vended = column(duckdb,
        "SELECT secret_string FROM duckdb_secrets() WHERE type = 's3' AND provider = 'iceberg'");
    assertFalse(vended.isEmpty(), "The catalog should have vended the credentials of the storage.");
    String secret = vended.get(0);
    assertTrue(secret.contains("endpoint=127.0.0.1:" + localS3.getPort()), secret);
    assertTrue(secret.contains("url_style=path"), secret);
    assertTrue(secret.contains("use_ssl=false"), secret);
    assertTrue(secret.contains("key_id=" + ACCESS_KEY), secret);
    assertTrue(secret.contains("secret=redacted"), secret);
    assertEquals(List.of("s3://" + WAREHOUSE + "/db/vended/"),
        column(duckdb, "SELECT unnest(scope) FROM duckdb_secrets() WHERE type = 's3' AND provider = 'iceberg' LIMIT 1"));
  }

  /**
   * The other direction: a table written with the Iceberg Java client, read by DuckDB through the catalog — the
   * lakehouse shape of "an application writes, the IDE queries".
   */
  @Test
  void rows_written_by_the_iceberg_client_are_read_by_duckdb() throws Exception {
    catalog.createNamespace(Namespace.of("db"));
    Table table = catalog.createTable(TableIdentifier.of(Namespace.of("db"), "logs"), SCHEMA);
    append(table, records(1, 3, "info"));
    append(table, records(4, 5, "warn"));

    assertEquals(List.of(List.of("db", "logs")),
        rows(duckdb, "SELECT schema, name FROM (SHOW ALL TABLES) WHERE database = 'ice'"));
    assertEquals(5L, single(duckdb, "SELECT count(*) FROM ice.db.logs"));
    assertEquals(List.of(List.of(1L, "row-1", "info"), List.of(2L, "row-2", "info"), List.of(3L, "row-3", "info"),
            List.of(4L, "row-4", "warn"), List.of(5L, "row-5", "warn")),
        rows(duckdb, "SELECT id, name, level FROM ice.db.logs ORDER BY id"));
    // A filter, which DuckDB answers from the data files of both snapshots.
    assertEquals(3L, single(duckdb, "SELECT count(*) FROM ice.db.logs WHERE level = 'info'"));
  }

  /**
   * And back again: what DuckDB writes, the Iceberg Java client reads, including the snapshot summary of the commit
   * that DuckDB made.
   */
  @Test
  void rows_written_by_duckdb_are_read_by_the_iceberg_client() throws Exception {
    execute(duckdb, "CREATE SCHEMA ice.db");
    execute(duckdb, "CREATE TABLE ice.db.numbers AS SELECT range AS id, 'row-' || range AS name FROM range(1000)");

    Table table = catalog.loadTable(TableIdentifier.of(Namespace.of("db"), "numbers"));
    Snapshot snapshot = table.currentSnapshot();
    assertNotNull(snapshot, "The write of DuckDB must have produced a snapshot.");
    assertEquals("append", snapshot.operation());
    assertEquals("1000", snapshot.summary().get("added-records"));

    long sum = 0;
    int count = 0;
    try (CloseableIterable<Record> records = IcebergGenerics.read(table).build()) {
      for (Record record : records) {
        sum += (Long) record.getField("id");
        count++;
      }
    }
    assertEquals(1000, count);
    assertEquals(499_500L, sum);
  }

  /**
   * Time travel: {@code iceberg_snapshots} lists the snapshots that the catalog recorded, and
   * {@code AT (VERSION => <snapshot id>)} reads the table as it was at one of them. The ids DuckDB reports are the
   * ones of the history the Iceberg client sees, so both clients address the same snapshots.
   */
  @Test
  void duckdb_reads_an_earlier_snapshot_of_a_table() throws Exception {
    catalog.createNamespace(Namespace.of("db"));
    Table table = catalog.createTable(TableIdentifier.of(Namespace.of("db"), "travel"), SCHEMA);
    append(table, records(1, 2, "first"));
    table.refresh();
    long firstSnapshot = table.currentSnapshot().snapshotId();
    append(table, records(3, 4, "second"));
    table.refresh();

    List<Long> snapshots = column(duckdb,
        "SELECT snapshot_id::BIGINT FROM iceberg_snapshots('ice.db.travel') ORDER BY sequence_number");
    assertEquals(table.history().stream().map(entry -> entry.snapshotId()).toList(), snapshots);

    assertEquals(4L, single(duckdb, "SELECT count(*) FROM ice.db.travel"));
    assertEquals(List.of(1L, 2L),
        column(duckdb, "SELECT id FROM ice.db.travel AT (VERSION => " + firstSnapshot + ") ORDER BY id"));
  }

  /**
   * A table is not only written and read: DuckDB deletes and updates rows through the catalog, and the Iceberg client
   * reads the result — so the delete files and the commits that DuckDB writes are ones a second implementation
   * understands, not only ones DuckDB reads back itself.
   */
  @Test
  void rows_deleted_and_updated_by_duckdb_are_seen_by_the_iceberg_client() throws Exception {
    execute(duckdb, "CREATE SCHEMA ice.db");
    execute(duckdb, "CREATE TABLE ice.db.changing AS "
        + "SELECT range AS id, 'row-' || range AS name, 'info' AS level FROM range(1, 6)");

    execute(duckdb, "DELETE FROM ice.db.changing WHERE id = 1");
    execute(duckdb, "UPDATE ice.db.changing SET level = 'error' WHERE id = 2");

    assertEquals(List.of(2L, 3L, 4L, 5L), column(duckdb, "SELECT id FROM ice.db.changing ORDER BY id"));

    Table table = catalog.loadTable(TableIdentifier.of(Namespace.of("db"), "changing"));
    assertEquals(List.of(2L, 3L, 4L, 5L), ids(table));
    assertEquals(List.of("error"), levels(table, 2L));
    // The catalog recorded a commit for each statement, and the Iceberg client reads the last one.
    assertTrue(table.history().size() >= 3, "Every statement should have committed: " + table.history());
  }

  /**
   * A column added by DuckDB is a schema update of the table, with the field id the catalog hands out, and the
   * Iceberg client reads the new column back as an optional field of the schema.
   */
  @Test
  void a_column_added_by_duckdb_is_a_schema_update_of_the_table() throws Exception {
    catalog.createNamespace(Namespace.of("db"));
    Table table = catalog.createTable(TableIdentifier.of(Namespace.of("db"), "evolving"), SCHEMA);
    append(table, records(1, 2, "info"));

    execute(duckdb, "ALTER TABLE ice.db.evolving ADD COLUMN score DOUBLE");
    execute(duckdb, "UPDATE ice.db.evolving SET score = id * 1.5");

    Schema evolved = catalog.loadTable(TableIdentifier.of(Namespace.of("db"), "evolving")).schema();
    assertNotNull(evolved.findField("score"), "The column DuckDB added must be in the schema of the table.");
    assertEquals(4, evolved.findField("score").fieldId(), "The field id must follow the ones of the table.");
    assertTrue(evolved.findField("score").isOptional(), "A column added to a written table has to be optional.");
    assertEquals(List.of(List.of(1L, 1.5d), List.of(2L, 3.0d)),
        rows(duckdb, "SELECT id, score FROM ice.db.evolving ORDER BY id"));
  }

  /**
   * {@code PARTITIONED BY} with the transforms of Iceberg: the partition spec is the one the Iceberg client reads,
   * every data file carries the partition values of its rows, and a filter on a source column lets the client plan
   * the files of the matching partitions alone.
   */
  @Test
  void a_table_partitioned_by_duckdb_is_planned_by_partition() throws Exception {
    execute(duckdb, "CREATE SCHEMA ice.db");
    execute(duckdb, "CREATE TABLE ice.db.partitioned (id BIGINT, day DATE, name VARCHAR) "
        + "PARTITIONED BY (bucket(4, id), month(day))");
    execute(duckdb, "INSERT INTO ice.db.partitioned "
        + "SELECT range, DATE '2026-01-01' + (range * 20)::INTEGER, 'row-' || range FROM range(20)");
    assertEquals(12L, single(duckdb, "SELECT count(*) FROM ice.db.partitioned WHERE day >= DATE '2026-06-01'"));

    Table table = catalog.loadTable(TableIdentifier.of(Namespace.of("db"), "partitioned"));
    assertEquals(List.of("bucket[4]", "month"),
        table.spec().fields().stream().map(field -> field.transform().toString()).toList());
    assertEquals(20, ids(table).size());

    int files = 0;
    try (CloseableIterable<FileScanTask> tasks = table.newScan().planFiles()) {
      for (FileScanTask task : tasks) {
        assertEquals(2, task.file().partition().size(), task.file().location());
        files++;
      }
    }
    int january = 0;
    try (CloseableIterable<FileScanTask> tasks = table.newScan()
        .filter(Expressions.lessThan("day", "2026-02-01")).planFiles()) {
      for (FileScanTask ignored : tasks) {
        january++;
      }
    }
    // Rows 0 and 1 fall into January, each into a partition of its own id bucket at most.
    assertTrue(january > 0 && january <= 2 && january < files, january + " of " + files + " files");
  }

  /**
   * The statements beyond INSERT, UPDATE and DELETE: {@code MERGE INTO}, {@code TRUNCATE}, and a transaction of
   * several statements, which becomes visible to the Iceberg client as a whole once committed and not at all once
   * rolled back.
   */
  @Test
  void merge_truncate_and_transactions_of_duckdb_are_commits_of_the_catalog() throws Exception {
    execute(duckdb, "CREATE SCHEMA ice.db");
    execute(duckdb, "CREATE TABLE ice.db.merged (id BIGINT, name VARCHAR, level VARCHAR)");
    execute(duckdb, "INSERT INTO ice.db.merged VALUES (1, 'row-1', 'info'), (2, 'row-2', 'info')");
    execute(duckdb, """
        MERGE INTO ice.db.merged USING (VALUES (2, 'row-2', 'warn'), (3, 'row-3', 'error')) source(id, name, level)
        ON merged.id = source.id
        WHEN MATCHED THEN UPDATE SET level = source.level
        WHEN NOT MATCHED THEN INSERT VALUES (source.id, source.name, source.level)""");

    TableIdentifier identifier = TableIdentifier.of(Namespace.of("db"), "merged");
    Table table = catalog.loadTable(identifier);
    assertEquals(List.of(1L, 2L, 3L), ids(table));
    assertEquals(List.of("warn"), levels(table, 2L));
    assertEquals(List.of("error"), levels(table, 3L));

    execute(duckdb, "BEGIN");
    execute(duckdb, "INSERT INTO ice.db.merged VALUES (4, 'row-4', 'info')");
    execute(duckdb, "DELETE FROM ice.db.merged WHERE id = 1");
    assertEquals(List.of(1L, 2L, 3L), ids(catalog.loadTable(identifier)),
        "The statements of an open transaction must not be visible to another client.");
    execute(duckdb, "COMMIT");
    assertEquals(List.of(2L, 3L, 4L), ids(catalog.loadTable(identifier)));

    long committed = catalog.loadTable(identifier).currentSnapshot().snapshotId();
    execute(duckdb, "BEGIN");
    execute(duckdb, "INSERT INTO ice.db.merged VALUES (5, 'row-5', 'info')");
    execute(duckdb, "ROLLBACK");
    assertEquals(committed, catalog.loadTable(identifier).currentSnapshot().snapshotId(),
        "A transaction rolled back must not commit anything.");

    execute(duckdb, "TRUNCATE ice.db.merged");
    assertEquals(List.of(), ids(catalog.loadTable(identifier)));
    assertEquals(0L, single(duckdb, "SELECT count(*) FROM ice.db.merged"));
  }

  /**
   * The schema changes beyond {@code ADD COLUMN}: a column renamed keeps its field id, so the files written before
   * the rename still read under the new name; a column dropped is gone from the schema; and an {@code INT} widened to
   * a {@code BIGINT} is the type promotion that Iceberg allows.
   */
  @Test
  void columns_renamed_dropped_and_widened_by_duckdb_are_schema_updates() throws Exception {
    execute(duckdb, "CREATE SCHEMA ice.db");
    execute(duckdb, "CREATE TABLE ice.db.reshaped (id BIGINT, label VARCHAR, note VARCHAR, amount INTEGER)");
    execute(duckdb, "INSERT INTO ice.db.reshaped VALUES (1, 'a', 'x', 10), (2, 'b', 'y', 20)");
    TableIdentifier identifier = TableIdentifier.of(Namespace.of("db"), "reshaped");
    int labelId = catalog.loadTable(identifier).schema().findField("label").fieldId();

    execute(duckdb, "ALTER TABLE ice.db.reshaped RENAME COLUMN label TO name");
    execute(duckdb, "ALTER TABLE ice.db.reshaped DROP COLUMN note");
    execute(duckdb, "ALTER TABLE ice.db.reshaped ALTER COLUMN amount SET DATA TYPE BIGINT");
    execute(duckdb, "INSERT INTO ice.db.reshaped VALUES (3, 'c', 3000000000)");

    Schema schema = catalog.loadTable(identifier).schema();
    assertEquals(List.of("id", "name", "amount"), schema.columns().stream().map(Types.NestedField::name).toList());
    assertEquals(labelId, schema.findField("name").fieldId(), "A rename must keep the field id of the column.");
    assertEquals(Types.LongType.get(), schema.findField("amount").type());

    List<String> names = new ArrayList<>();
    try (CloseableIterable<Record> records = IcebergGenerics.read(catalog.loadTable(identifier)).build()) {
      records.forEach(record -> names.add(record.getField("name") + "=" + record.getField("amount")));
    }
    assertEquals(List.of("a=10", "b=20", "c=3000000000"), names.stream().sorted().toList());
  }

  /**
   * The lifecycle of tables and namespaces: a table DuckDB renames or drops is renamed or dropped in the catalog, a
   * {@code CREATE TABLE AS} rolled back leaves no table behind, and an empty namespace dropped is gone. DuckDB 1.5
   * refuses {@code DROP SCHEMA ... CASCADE} on an Iceberg catalog, so the tables are dropped first.
   */
  @Test
  void tables_and_schemas_renamed_and_dropped_by_duckdb_are_gone_from_the_catalog() throws Exception {
    execute(duckdb, "CREATE SCHEMA ice.staging");
    execute(duckdb, "CREATE TABLE ice.staging.draft AS SELECT range AS id FROM range(3)");
    execute(duckdb, "CREATE TABLE ice.staging.scratch (id BIGINT)");

    execute(duckdb, "ALTER TABLE ice.staging.draft RENAME TO final");
    assertEquals(List.of("final", "scratch"), catalog.listTables(Namespace.of("staging")).stream()
        .map(TableIdentifier::name).sorted().toList());
    assertEquals(List.of(0L, 1L, 2L), ids(catalog.loadTable(TableIdentifier.of(Namespace.of("staging"), "final"))));

    execute(duckdb, "DROP TABLE ice.staging.scratch");
    assertEquals(List.of(TableIdentifier.of(Namespace.of("staging"), "final")),
        catalog.listTables(Namespace.of("staging")));

    execute(duckdb, "BEGIN");
    execute(duckdb, "CREATE TABLE ice.staging.abandoned AS SELECT 1 AS id");
    execute(duckdb, "ROLLBACK");
    assertFalse(catalog.tableExists(TableIdentifier.of(Namespace.of("staging"), "abandoned")));

    execute(duckdb, "DROP TABLE ice.staging.final");
    execute(duckdb, "DROP SCHEMA ice.staging");
    assertFalse(catalog.namespaceExists(Namespace.of("staging")));
    assertEquals(List.of(), column(duckdb, "SELECT name FROM (SHOW ALL TABLES) WHERE schema = 'staging'"));
  }

  /**
   * The nested and logical types of DuckDB, written through the catalog: lists, structs, maps, timestamps with a time
   * zone and decimals are Iceberg types that the Iceberg client reads back with the same values.
   */
  @Test
  void nested_types_written_by_duckdb_are_read_by_the_iceberg_client() throws Exception {
    execute(duckdb, "CREATE SCHEMA ice.db");
    execute(duckdb, "CREATE TABLE ice.db.typed (id BIGINT, tags VARCHAR[], point STRUCT(x INTEGER, y INTEGER), "
        + "attributes MAP(VARCHAR, INTEGER), created_at TIMESTAMPTZ, amount DECIMAL(10, 2))");
    execute(duckdb, "INSERT INTO ice.db.typed VALUES (1, ['a', 'b'], {'x': 1, 'y': 2}, MAP {'k': 7}, "
        + "TIMESTAMPTZ '2026-01-01 00:00:00+00', 12.34)");

    Table table = catalog.loadTable(TableIdentifier.of(Namespace.of("db"), "typed"));
    Schema schema = table.schema();
    assertTrue(schema.findField("tags").type().isListType(), schema.toString());
    assertTrue(schema.findField("point").type().isStructType(), schema.toString());
    assertTrue(schema.findField("attributes").type().isMapType(), schema.toString());
    assertEquals(Types.TimestampType.withZone(), schema.findField("created_at").type());
    assertEquals(Types.DecimalType.of(10, 2), schema.findField("amount").type());

    try (CloseableIterable<Record> records = IcebergGenerics.read(table).build()) {
      Record record = records.iterator().next();
      assertEquals(List.of("a", "b"), record.getField("tags"));
      Record point = (Record) record.getField("point");
      assertEquals(List.of(1, 2), List.of(point.getField("x"), point.getField("y")));
      assertEquals(Map.of("k", 7), record.getField("attributes"));
      assertEquals(OffsetDateTime.parse("2026-01-01T00:00:00Z"), record.getField("created_at"));
      assertEquals(new BigDecimal("12.34"), record.getField("amount"));
    }
  }

  /**
   * Without the catalog: {@code iceberg_scan} reads a table straight out of the bucket, given the metadata file that
   * the table currently is. This is the path a query takes when it addresses a table by location rather than by name,
   * and it needs an S3 secret of its own, because nothing vends one.
   */
  @Test
  void iceberg_scan_reads_a_table_from_the_bucket_without_the_catalog() throws Exception {
    catalog.createNamespace(Namespace.of("db"));
    Table table = catalog.createTable(TableIdentifier.of(Namespace.of("db"), "scanned"), SCHEMA);
    append(table, records(1, 4, "info"));
    String metadata = ((BaseTable) catalog.loadTable(TableIdentifier.of(Namespace.of("db"), "scanned")))
        .operations().current().metadataFileLocation();

    execute(duckdb, "CREATE SECRET local_s3 (TYPE s3, ENDPOINT '127.0.0.1:" + localS3.getPort() + "', "
        + "URL_STYLE 'path', USE_SSL false, KEY_ID '" + ACCESS_KEY + "', SECRET '" + SECRET_KEY + "', REGION 'us-east-1')");

    assertEquals(List.of(1L, 2L, 3L, 4L),
        column(duckdb, "SELECT id FROM iceberg_scan('" + metadata + "') ORDER BY id"));
  }

  /**
   * The DuckDB script of {@code GET /_admin/ui/snippets} attaches the catalog of the service in a DuckDB that was
   * given nothing else, and the table it then writes is a table of the catalog.
   */
  @Test
  void the_duckdb_snippet_of_the_console_attaches_the_catalog() throws Exception {
    String basic = java.util.Base64.getEncoder().encodeToString((ACCESS_KEY + ":" + SECRET_KEY).getBytes());
    java.net.http.HttpResponse<String> response = java.net.http.HttpClient.newHttpClient().send(
        java.net.http.HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + localS3.getPort() + "/_admin/ui/snippets"))
            .header("Authorization", "Basic " + basic).build(),
        java.net.http.HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode(), response.body());
    String script = new tools.jackson.databind.ObjectMapper().readTree(response.body())
        .get("snippets").get(0).get("content").asText();

    try (Connection pasted = DriverManager.getConnection("jdbc:duckdb:")) {
      DuckDb.loadExtension(pasted, "httpfs");
      DuckDb.loadExtension(pasted, "iceberg");
      String code = script.lines().filter(line -> !line.startsWith("--")).reduce("", (a, b) -> a + b + "\n");
      for (String statement : code.split(";\\s*\n")) {
        if (!statement.isBlank()) {
          execute(pasted, statement.trim());
        }
      }
      execute(pasted, "CREATE SCHEMA ice.pasted");
      execute(pasted, "CREATE TABLE ice.pasted.t AS SELECT range AS id FROM range(5)");
      assertEquals(10L, single(pasted, "SELECT sum(id)::BIGINT FROM ice.pasted.t"));
    }
    assertTrue(catalog.tableExists(TableIdentifier.of("pasted", "t")));
  }

  /**
   * A table bucket of the S3 Tables API, attached by its ARN. DuckDB reaches Amazon S3 Tables with
   * {@code ENDPOINT_TYPE s3_tables}, but that always sends its requests to {@code s3tables.<region>.amazonaws.com}
   * and ignores an {@code ENDPOINT}, and its {@code AUTHORIZATION_TYPE 'sigv4'} refuses a host that isn't one of AWS.
   * So LocalS3 is attached the way any REST catalog is, with the ARN as the warehouse, and the table that DuckDB
   * creates there is one that the S3 Tables API lists.
   */
  @Test
  void duckdb_attaches_a_table_bucket_by_its_arn() throws Exception {
    try (S3TablesClient tables = S3TablesClient.builder()
        .endpointOverride(URI.create("http://127.0.0.1:" + localS3.getPort()))
        .region(Region.US_EAST_1)
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
        .build()) {
      String arn = tables.createTableBucket(request -> request.name("duck-tables")).arn();
      execute(duckdb, "ATTACH '" + arn + "' AS tb (TYPE ICEBERG, ENDPOINT '" + catalogUri()
          + "', AUTHORIZATION_TYPE 'none')");

      execute(duckdb, "CREATE SCHEMA tb.sales");
      execute(duckdb, "CREATE TABLE tb.sales.orders AS SELECT range AS id FROM range(10)");
      execute(duckdb, "INSERT INTO tb.sales.orders VALUES (10)");
      assertEquals(55L, single(duckdb, "SELECT sum(id)::BIGINT FROM tb.sales.orders"));

      assertEquals(List.of("orders"), tables.listTables(request -> request.tableBucketARN(arn).namespace("sales"))
          .tables().stream().map(TableSummary::name).toList());
      String location = tables.getTableMetadataLocation(request -> request.tableBucketARN(arn).namespace("sales")
          .name("orders")).metadataLocation();
      assertEquals(55L, single(duckdb, "SELECT sum(id)::BIGINT FROM iceberg_scan('" + location + "')"),
          "The metadata location of the S3 Tables API is the current version of the table DuckDB wrote.");
      assertFalse(keys("sales/").stream().findAny().isPresent(),
          "A table bucket keeps its tables out of the warehouse of the built-in catalog.");
    }
  }

  private String catalogUri() {
    return "http://127.0.0.1:" + localS3.getPort() + "/iceberg";
  }

  private List<String> keys(String prefix) {
    return s3.listObjectsV2Paginator(request -> request.bucket(WAREHOUSE).prefix(prefix)).contents().stream()
        .map(S3Object::key).sorted().toList();
  }

  /*
   * The Iceberg Java client: reading a table, and writing Parquet rows into it the way an engine does.
   */

  private static List<Long> ids(Table table) throws IOException {
    List<Long> ids = new ArrayList<>();
    try (CloseableIterable<Record> records = IcebergGenerics.read(table).build()) {
      records.forEach(record -> ids.add((Long) record.getField("id")));
    }
    return ids.stream().sorted().toList();
  }

  private static List<String> levels(Table table, long id) throws IOException {
    List<String> levels = new ArrayList<>();
    try (CloseableIterable<Record> records = IcebergGenerics.read(table).build()) {
      records.forEach(record -> {
        if (((Long) record.getField("id")) == id) {
          levels.add((String) record.getField("level"));
        }
      });
    }
    return levels;
  }

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

  private static void append(Table table, List<Record> records) {
    table.newAppend().appendFile(write(table, records)).commit();
  }

  /**
   * Write the records as one Parquet file under the table, through the {@code FileIO} that the catalog vended, and
   * describe it as a {@link DataFile} that a commit can add.
   */
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
        // The metrics of the file are only complete once the footer is written, so toDataFile() needs it closed.
        writer.close();
      }
      return writer.toDataFile();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

}
