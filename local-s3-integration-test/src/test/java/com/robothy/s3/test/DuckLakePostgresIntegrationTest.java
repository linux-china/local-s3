package com.robothy.s3.test;

import static com.robothy.s3.test.DuckDb.column;
import static com.robothy.s3.test.DuckDb.execute;
import static com.robothy.s3.test.DuckDb.loadExtension;
import static com.robothy.s3.test.DuckDb.rows;
import static com.robothy.s3.test.DuckDb.single;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.rest.LocalS3;
import com.robothy.s3.rest.admin.RequestStatistics;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * DuckLake with its catalog in PostgreSQL and its data files on LocalS3, the setup of a DuckLake that several DuckDB
 * processes share:
 *
 * <pre>{@code
 * ATTACH 'ducklake:postgres:host=... dbname=lake user=postgres' AS lake
 *     (DATA_PATH 's3://lake/data/', DATA_INLINING_ROW_LIMIT 100);
 * INSERT INTO lake.t VALUES (...);                 -- inlined: rows in PostgreSQL, no object on LocalS3
 * CALL ducklake_flush_inlined_data('lake');        -- the inlined rows become Parquet files on LocalS3
 * CALL ducklake_rewrite_data_files('lake');        -- compaction
 * CALL ducklake_merge_adjacent_files('lake');
 * CALL ducklake_expire_snapshots('lake', older_than => now());
 * CALL ducklake_cleanup_old_files('lake', cleanup_all => true);
 * }</pre>
 *
 * <p>Unlike {@link DuckLakeIntegrationTest}, these tests keep data inlining on: changes below the row limit live in
 * tables of the catalog until they are flushed, and only then reach LocalS3. Each test attaches the same catalog from
 * two DuckDB databases, which share nothing but PostgreSQL and LocalS3, as two processes would.
 *
 * <p>PostgreSQL runs in a container, one database per test; without Docker, the tests are skipped. The {@code httpfs},
 * {@code ducklake} and {@code postgres} extensions are loaded like in {@link DuckLakeIntegrationTest}.
 */
@Tag("data-tools")
@Testcontainers(disabledWithoutDocker = true)
@Timeout(value = 3, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class DuckLakePostgresIntegrationTest {

  private static final String BUCKET = "lake";

  private static final String PASSWORD = "local-s3";

  private static final int INLINING_ROW_LIMIT = 100;

  private static final AtomicInteger DATABASES = new AtomicInteger();

  @Container
  static final GenericContainer<?> POSTGRES = new GenericContainer<>(DockerImageName.parse("postgres:17-alpine"))
      .withEnv("POSTGRES_PASSWORD", PASSWORD)
      .withExposedPorts(5432)
      // The server restarts once after the initialization of its data directory.
      .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2));

  private LocalS3 localS3;

  private S3Client s3;

  /**
   * The DuckDB databases that attach the lake, closed after each test.
   */
  private final List<Connection> duckdbs = new ArrayList<>();

  private String catalog;

  @BeforeEach
  void setUp() throws Exception {
    localS3 = LocalS3.builder().port(-1).buckets(BUCKET).build();
    localS3.start();
    s3 = S3Client.builder()
        .endpointOverride(URI.create("http://127.0.0.1:" + localS3.getPort()))
        .region(Region.US_EAST_1)
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("any-access-key", "any-secret-key")))
        .forcePathStyle(true)
        .build();

    String database = "lake_" + DATABASES.incrementAndGet();
    var created = POSTGRES.execInContainer("psql", "-U", "postgres", "-c", "CREATE DATABASE " + database);
    assertEquals(0, created.getExitCode(), created.getStderr());
    catalog = "postgres:host=" + POSTGRES.getHost() + " port=" + POSTGRES.getMappedPort(5432) + " dbname=" + database
        + " user=postgres password=" + PASSWORD;
  }

  @AfterEach
  void tearDown() throws SQLException {
    // LocalS3 first, see DuckDbParquetIntegrationTest.
    if (localS3 != null) {
      localS3.shutdown();
    }
    if (s3 != null) {
      s3.close();
    }
    for (Connection duckdb : duckdbs) {
      duckdb.close();
    }
  }

  /**
   * Inserts, an update and a delete below the row limit are rows in the PostgreSQL catalog: nothing is written to
   * LocalS3, and a second DuckDB reads them from the catalog alone. Flushing writes them to LocalS3 as a data file and
   * a delete file, which both DuckDBs then read, and the snapshots from before the flush still read the same rows.
   */
  @Test
  void inlinedChangesReachLocalS3OnlyWhenFlushed() throws Exception {
    Connection writer = attach();
    Connection reader = attach();
    execute(writer, "CREATE TABLE lake.t (id BIGINT, v VARCHAR)");
    execute(writer, "INSERT INTO lake.t SELECT range, 'a' FROM range(10)");
    execute(writer, "INSERT INTO lake.t SELECT range, 'b' FROM range(10, 20)");
    execute(writer, "UPDATE lake.t SET v = 'c' WHERE id % 5 = 0");
    execute(writer, "DELETE FROM lake.t WHERE id = 19");

    List<List<Object>> expected = List.of(List.of(19L, 171L, 4L));
    assertEquals(Set.of(), keys("data/"), "Inlined changes must not write objects.");
    assertEquals(0, count("PutObject") + count("CreateMultipartUpload"));
    assertEquals(List.of(), column(writer, "SELECT data_file FROM ducklake_list_files('lake', 't')"));
    assertTrue(single(writer, "SELECT count(*) FROM __ducklake_metadata_lake.ducklake_inlined_data_tables") >= 1);
    assertEquals(expected, summary(reader, ""));
    assertEquals(List.of(List.of(20L, 190L, 0L)), summary(reader, " AT (VERSION => 3)"));

    long flushed = single(writer, "SELECT sum(rows_flushed)::BIGINT FROM ducklake_flush_inlined_data('lake')");
    assertTrue(flushed >= 19, flushed + " rows were flushed.");

    Set<String> current = currentFiles(writer);
    assertTrue(current.stream().anyMatch(key -> !key.endsWith("-delete.parquet")), "No data file: " + current);
    assertEquals(current, keys("data/"), "The flushed rows must be the files of the current snapshot on LocalS3.");
    long rows = 0;
    for (String dataFile : current) {
      if (!dataFile.endsWith("-delete.parquet")) {
        rows += single(reader, "SELECT count(*) FROM read_parquet('s3://" + BUCKET + "/" + dataFile + "')");
      }
    }
    assertEquals(flushed, rows, "The data files on LocalS3 must hold the flushed rows.");
    assertEquals(0, clientAndServerErrors("PutObject"));

    assertEquals(expected, summary(writer, ""));
    assertEquals(expected, summary(reader, ""));
    assertEquals(List.of(List.of(20L, 190L, 0L)), summary(reader, " AT (VERSION => 3)"));
  }

  /**
   * Inserts above the row limit are files of their own, and a small insert is inlined next to them. After the flush,
   * compaction leaves one file for the table; expiring the snapshots and cleaning up deletes every other file from
   * LocalS3 with {@code DeleteObjects}, and the second DuckDB still reads the whole table.
   */
  @Test
  void compactionAndCleanupLeaveOneFileOnLocalS3() throws Exception {
    Connection writer = attach();
    Connection reader = attach();
    execute(writer, "CREATE TABLE lake.t (id BIGINT, v VARCHAR)");
    for (int batch = 0; batch < 3; batch++) {
      execute(writer, "INSERT INTO lake.t SELECT range, md5(range::VARCHAR) FROM range(" + batch * 1000 + ", "
          + (batch + 1) * 1000 + ")");
    }
    execute(writer, "INSERT INTO lake.t SELECT range, md5(range::VARCHAR) FROM range(3000, 3010)");
    Set<String> inserted = keys("data/");
    assertEquals(3, inserted.size(), "Only the inserts above the row limit must be files: " + inserted);

    execute(writer, "CALL ducklake_flush_inlined_data('lake')");
    assertEquals(4, keys("data/").size(), "The flush must write the inlined insert as a file of its own.");
    long merged = single(writer, "SELECT sum(files_processed)::BIGINT FROM ducklake_merge_adjacent_files('lake')");
    assertEquals(4, merged, "The files of the inserts must have been merged.");
    assertEquals(1, currentFiles(writer).size(), currentFiles(writer).toString());

    // A delete of rows in files is inlined as well, and only becomes delete files when flushed.
    Set<String> beforeDelete = keys("data/");
    execute(writer, "DELETE FROM lake.t WHERE id % 100 = 0");
    assertEquals(beforeDelete, keys("data/"), "An inlined delete must not write objects.");
    List<List<Object>> expected = List.of(List.of(2979L, 4_482_045L));
    assertEquals(expected, table(reader));

    execute(writer, "CALL ducklake_flush_inlined_data('lake')");
    assertTrue(currentFiles(writer).stream().anyMatch(key -> key.endsWith("-delete.parquet")),
        "The flush must write the inlined delete as a delete file: " + currentFiles(writer));
    execute(writer, "CALL ducklake_rewrite_data_files('lake', delete_threshold => 0)");

    Set<String> current = currentFiles(writer);
    assertEquals(1, current.size(), "One file without deletes must hold the whole table after compaction: " + current);
    Set<String> written = keys("data/");
    assertTrue(written.containsAll(current), written + " must contain " + current);
    assertEquals(0, count("DeleteObjects") + count("DeleteObject"), "Compaction must not delete files yet.");

    execute(writer, "CALL ducklake_expire_snapshots('lake', older_than => now())");
    Set<String> scheduled = new TreeSet<>(column(writer,
        "SELECT path FROM __ducklake_metadata_lake.ducklake_files_scheduled_for_deletion").stream()
        .map(path -> "data/" + path).toList());
    execute(writer, "CALL ducklake_cleanup_old_files('lake', cleanup_all => true)");

    assertEquals(current, keys("data/"), "Only the file of the current snapshot must be left on LocalS3.");
    Set<String> deleted = new TreeSet<>(written);
    deleted.removeAll(current);
    assertEquals(scheduled, deleted);
    assertTrue(count("DeleteObjects") >= 1, "The files must be deleted with DeleteObjects.");
    assertEquals(0, clientAndServerErrors("DeleteObjects"));

    assertEquals(expected, table(writer));
    assertEquals(expected, table(reader));
  }

  /**
   * A new in-memory DuckDB with the lake attached, its catalog in the PostgreSQL database of the test.
   */
  private Connection attach() throws SQLException {
    Connection duckdb = DriverManager.getConnection("jdbc:duckdb:");
    duckdbs.add(duckdb);
    loadExtension(duckdb, "httpfs");
    loadExtension(duckdb, "ducklake");
    loadExtension(duckdb, "postgres");
    execute(duckdb, "CREATE SECRET local_s3 (TYPE s3, ENDPOINT '127.0.0.1:" + localS3.getPort() + "', URL_STYLE 'path', "
        + "USE_SSL false, KEY_ID 'any-access-key', SECRET 'any-secret-key', REGION 'us-east-1')");
    execute(duckdb, "ATTACH 'ducklake:" + catalog + "' AS lake (DATA_PATH 's3://" + BUCKET + "/data/', "
        + "DATA_INLINING_ROW_LIMIT " + INLINING_ROW_LIMIT + ")");
    return duckdb;
  }

  /**
   * The row count, the sum of the ids and the number of rows with {@code v = 'c'} of {@code lake.t}, at a snapshot if
   * {@code at} names one.
   */
  private static List<List<Object>> summary(Connection duckdb, String at) throws SQLException {
    return rows(duckdb, "SELECT count(*), sum(id)::BIGINT, count(*) FILTER (v = 'c') FROM lake.t" + at);
  }

  /**
   * The row count and the sum of the ids of {@code lake.t}, whose values must all be the MD5 of their ids.
   */
  private static List<List<Object>> table(Connection duckdb) throws SQLException {
    assertEquals(0L, single(duckdb, "SELECT count(*) FROM lake.t WHERE v <> md5(id::VARCHAR)"));
    return rows(duckdb, "SELECT count(*), sum(id)::BIGINT FROM lake.t");
  }

  /**
   * The keys of the data and delete files of the current snapshot of {@code lake.t}.
   */
  private static Set<String> currentFiles(Connection duckdb) throws SQLException {
    Set<String> keys = new TreeSet<>();
    for (List<Object> row : rows(duckdb, "SELECT data_file, delete_file FROM ducklake_list_files('lake', 't')")) {
      for (Object file : row) {
        if (file != null) {
          String prefix = "s3://" + BUCKET + "/";
          assertTrue(((String) file).startsWith(prefix), (String) file);
          keys.add(((String) file).substring(prefix.length()));
        }
      }
    }
    return keys;
  }

  private Set<String> keys(String prefix) {
    return new TreeSet<>(s3.listObjectsV2Paginator(request -> request.bucket(BUCKET).prefix(prefix)).contents().stream()
        .map(S3Object::key).toList());
  }

  private long count(String operation) {
    RequestStatistics.OperationStatistics statistics = localS3.statistics().operations().get(operation);
    return statistics == null ? 0 : statistics.count();
  }

  private long clientAndServerErrors(String operation) {
    RequestStatistics.OperationStatistics statistics = localS3.statistics().operations().get(operation);
    return statistics == null ? 0 : statistics.clientErrors() + statistics.serverErrors();
  }

}
