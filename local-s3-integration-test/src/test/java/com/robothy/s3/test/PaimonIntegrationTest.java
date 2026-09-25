package com.robothy.s3.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.rest.LocalS3;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import org.apache.paimon.Snapshot;
import org.apache.paimon.catalog.Catalog;
import org.apache.paimon.catalog.CatalogContext;
import org.apache.paimon.catalog.CatalogFactory;
import org.apache.paimon.catalog.Identifier;
import org.apache.paimon.data.BinaryString;
import org.apache.paimon.data.GenericRow;
import org.apache.paimon.data.InternalRow;
import org.apache.paimon.fs.hadoop.HadoopFileIO;
import org.apache.paimon.options.ExpireConfig;
import org.apache.paimon.options.Options;
import org.apache.paimon.schema.Schema;
import org.apache.paimon.table.Table;
import org.apache.paimon.table.sink.BatchTableCommit;
import org.apache.paimon.table.sink.BatchTableWrite;
import org.apache.paimon.table.sink.BatchWriteBuilder;
import org.apache.paimon.table.source.DataSplit;
import org.apache.paimon.table.source.ReadBuilder;
import org.apache.paimon.table.source.Split;
import org.apache.paimon.types.DataTypes;
import org.apache.paimon.types.RowKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * Apache Paimon on LocalS3, without Flink or Spark: its filesystem catalog, with the warehouse on {@code s3a://}.
 * Paimon has no FileIO of its own for that scheme in the bundle, so it falls back to Hadoop's, and reaches LocalS3
 * through S3A, the way Flink and Spark jobs with {@code hadoop-aws} on their classpath do. The catalog passes its
 * options with the prefix {@code hadoop.} to Hadoop, without the prefix, so the catalog of the test is the one of
 * Flink SQL:
 *
 * <pre>{@code
 * CREATE CATALOG paimon WITH (
 *   'type' = 'paimon',
 *   'warehouse' = 's3a://lake/paimon',
 *   'hadoop.fs.s3a.endpoint' = 'http://localhost:29090',
 *   'hadoop.fs.s3a.path.style.access' = 'true',
 *   'hadoop.fs.s3a.access.key' = 'admin',
 *   'hadoop.fs.s3a.secret.key' = 'admin'
 * );
 * }</pre>
 *
 * <p>A table is a directory of {@code schema/}, {@code snapshot/}, {@code manifest/} and one directory per partition
 * and bucket; a primary-key table is an LSM tree per bucket, whose levels a compaction merges into one file.
 */
@Tag("data-tools")
@Timeout(value = 3, unit = TimeUnit.MINUTES)
class PaimonIntegrationTest {

  private static final String BUCKET = "lake";

  private static final Identifier ORDERS = Identifier.create("shop", "orders");

  private LocalS3 localS3;

  private S3Client s3;

  private Catalog catalog;

  /**
   * Where S3A buffers the blocks of a file before it uploads them.
   */
  @TempDir
  java.nio.file.Path bufferDir;

  @BeforeEach
  void setUp() {
    localS3 = LocalS3.builder().port(-1).buckets(BUCKET).build();
    localS3.start();
    s3 = S3Client.builder()
        .endpointOverride(URI.create(localS3.endpoint()))
        .region(Region.US_EAST_1)
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("admin", "admin")))
        .forcePathStyle(true)
        .build();
    Options options = new Options();
    options.set("warehouse", "s3a://" + BUCKET + "/paimon");
    options.set("hadoop.hadoop.tmp.dir", bufferDir.toString());
    options.set("hadoop.fs.s3a.endpoint", localS3.endpoint());
    options.set("hadoop.fs.s3a.endpoint.region", "us-east-1");
    options.set("hadoop.fs.s3a.path.style.access", "true");
    options.set("hadoop.fs.s3a.connection.ssl.enabled", "false");
    options.set("hadoop.fs.s3a.aws.credentials.provider", "org.apache.hadoop.fs.s3a.SimpleAWSCredentialsProvider");
    options.set("hadoop.fs.s3a.access.key", "admin");
    options.set("hadoop.fs.s3a.secret.key", "admin");
    // A file system of its own rather than the one Hadoop caches for s3a://lake, with the port of an earlier test.
    options.set("hadoop.fs.s3a.impl.disable.cache", "true");
    catalog = CatalogFactory.createCatalog(CatalogContext.create(options));
  }

  @AfterEach
  void tearDown() throws Exception {
    if (catalog != null) {
      catalog.close();
    }
    if (s3 != null) {
      s3.close();
    }
    if (localS3 != null) {
      localS3.shutdown();
    }
  }

  /**
   * A partitioned primary-key table: rows written, then updated and deleted by key in a second commit, read merged,
   * read as of the first snapshot, compacted to one file per bucket, and its old snapshots expired.
   */
  @Test
  void writesUpsertsReadsAndCompactsAPrimaryKeyTable() throws Exception {
    catalog.createDatabase("shop", false);
    catalog.createTable(ORDERS, Schema.newBuilder()
        .column("dt", DataTypes.STRING())
        .column("id", DataTypes.INT())
        .column("item", DataTypes.STRING())
        .partitionKeys("dt")
        .primaryKey("dt", "id")
        .option("bucket", "1")
        .option("file.format", "parquet")
        // Each commit leaves its own sorted run, so that the compaction below has something to merge.
        .option("num-sorted-run.compaction-trigger", "10")
        .build(), false);
    assertEquals(List.of("shop"), catalog.listDatabases());
    assertEquals(List.of("orders"), catalog.listTables("shop"));
    Table table = catalog.getTable(ORDERS);
    assertTrue(table.fileIO() instanceof HadoopFileIO, "Paimon reaches s3a:// with S3A: " + table.fileIO());

    write(table, List.of(
        row("2026-09-01", 1, "apple"), row("2026-09-01", 2, "banana"), row("2026-09-01", 3, "cherry"),
        row("2026-09-02", 1, "durian"), row("2026-09-02", 2, "elderberry")));
    write(table, List.of(
        row("2026-09-01", 2, "blueberry"),
        GenericRow.ofKind(RowKind.DELETE, s("2026-09-01"), 3, s("cherry")),
        row("2026-09-02", 3, "fig")));

    Map<String, String> merged = Map.of(
        "2026-09-01/1", "apple", "2026-09-01/2", "blueberry",
        "2026-09-02/1", "durian", "2026-09-02/2", "elderberry", "2026-09-02/3", "fig");
    assertEquals(merged, read(table));
    assertEquals(2L, latestSnapshot(table).id());
    Table first = table.copy(Map.of("scan.snapshot-id", "1"));
    assertEquals(Map.of("2026-09-01/1", "apple", "2026-09-01/2", "banana", "2026-09-01/3", "cherry",
        "2026-09-02/1", "durian", "2026-09-02/2", "elderberry"), read(first));
    assertEquals(Map.of("2026-09-01", 2, "2026-09-02", 2), filesPerPartition(table), "One file per commit.");

    compact(table);
    assertEquals(Snapshot.CommitKind.COMPACT, latestSnapshot(table).commitKind());
    assertEquals(Map.of("2026-09-01", 1, "2026-09-02", 1), filesPerPartition(table), "Merged into one file.");
    assertEquals(merged, read(table), "A compaction changes no row.");

    // The files the compaction replaced go when the snapshots that refer to them do.
    List<String> before = keys("paimon/shop.db/orders/dt=");
    int expired = table.newExpireSnapshots().config(ExpireConfig.builder()
        .snapshotRetainMin(1).snapshotRetainMax(1).snapshotTimeRetain(Duration.ZERO).build()).expire();
    assertEquals(2, expired);
    List<String> after = keys("paimon/shop.db/orders/dt=");
    assertEquals(before.size() - 4, after.size(), "The four files of the two writes are deleted: " + after);
    assertEquals(merged, read(catalog.getTable(ORDERS)));
    assertFalse(keys("paimon/shop.db/orders/snapshot/").contains("paimon/shop.db/orders/snapshot/snapshot-1"));

    catalog.dropTable(ORDERS, false);
    assertEquals(List.of(), catalog.listTables("shop"));
    assertEquals(List.of(), keys("paimon/shop.db/orders/"), "Dropping the table deletes its directory.");
  }

  private static void write(Table table, List<GenericRow> rows) throws Exception {
    BatchWriteBuilder builder = table.newBatchWriteBuilder();
    try (BatchTableWrite write = builder.newWrite(); BatchTableCommit commit = builder.newCommit()) {
      for (GenericRow row : rows) {
        write.write(row);
      }
      commit.commit(write.prepareCommit());
    }
  }

  /**
   * A full compaction of every bucket of the table, which is what {@code CALL sys.compact} runs in Flink and Spark.
   */
  private static void compact(Table table) throws Exception {
    List<DataSplit> splits = splits(table);
    BatchWriteBuilder builder = table.newBatchWriteBuilder();
    try (BatchTableWrite write = builder.newWrite(); BatchTableCommit commit = builder.newCommit()) {
      for (DataSplit split : splits) {
        write.compact(split.partition(), split.bucket(), true);
      }
      commit.commit(write.prepareCommit());
    }
  }

  /**
   * The rows of the table, by {@code dt/id}.
   */
  private static Map<String, String> read(Table table) throws Exception {
    ReadBuilder builder = table.newReadBuilder();
    List<Split> splits = builder.newScan().plan().splits();
    Map<String, String> rows = new TreeMap<>();
    builder.newRead().createReader(splits).forEachRemaining((InternalRow row) ->
        rows.put(row.getString(0) + "/" + row.getInt(1), row.getString(2).toString()));
    return rows;
  }

  private static List<DataSplit> splits(Table table) {
    List<DataSplit> splits = new ArrayList<>();
    for (Split split : table.newReadBuilder().newScan().plan().splits()) {
      splits.add((DataSplit) split);
    }
    return splits;
  }

  private static Map<String, Integer> filesPerPartition(Table table) {
    Map<String, Integer> files = new TreeMap<>();
    for (DataSplit split : splits(table)) {
      files.merge(split.partition().getString(0).toString(), split.dataFiles().size(), Integer::sum);
    }
    return files;
  }

  private static Snapshot latestSnapshot(Table table) {
    return table.latestSnapshot().orElseThrow();
  }

  private static GenericRow row(String dt, int id, String item) {
    return GenericRow.of(s(dt), id, s(item));
  }

  private static BinaryString s(String value) {
    return BinaryString.fromString(value);
  }

  private List<String> keys(String prefix) {
    return s3.listObjectsV2Paginator(request -> request.bucket(BUCKET).prefix(prefix)).contents().stream()
        .map(S3Object::key)
        .filter(key -> !key.endsWith("/"))
        .toList();
  }

}
