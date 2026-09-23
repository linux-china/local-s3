package com.robothy.s3.test;

import static com.robothy.s3.test.DuckDb.column;
import static com.robothy.s3.test.DuckDb.execute;
import static com.robothy.s3.test.DuckDb.rows;
import static com.robothy.s3.test.DuckDb.single;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.rest.LocalS3;
import com.robothy.s3.test.delta.DeltaTables;
import com.robothy.s3.test.delta.LocalS3DeltaFileIO;
import io.delta.kernel.defaults.engine.DefaultEngine;
import io.delta.kernel.engine.Engine;
import io.delta.kernel.types.LongType;
import io.delta.kernel.types.StringType;
import io.delta.kernel.types.StructType;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Delta Lake tables on LocalS3, read with the {@code delta} extension of DuckDB:
 *
 * <pre>{@code
 * SELECT * FROM delta_scan('s3://delta/tables/events');
 * }</pre>
 *
 * <p>The table is written by {@code delta-kernel-java}, which {@link DeltaLakeIntegrationTest} covers, and read here
 * by a client that shares nothing with it — {@code delta_scan} is built on {@code delta-kernel-rs} and reaches LocalS3
 * with the HTTP client of DuckDB rather than with the AWS SDK. So what these tests prove is that the log and the data
 * files a Delta writer left in LocalS3 are a table for anyone, and that the requests of a second, quite different
 * client are ones LocalS3 answers: the {@code _delta_log} is listed, its commits are read whole, and the Parquet files
 * are read with range requests.
 *
 * <p>The {@code delta} extension is read-only, so the writing is left to Delta's Java client; that is also the shape of
 * the scenario, where an application writes a table and the IDE queries it.
 *
 * <p>The {@code httpfs} and {@code delta} extensions are loaded from the extension directory of DuckDB, and installed
 * from its extension repository if they aren't there; without either, the tests are skipped. See {@link DuckDb}.
 */
@Tag("data-tools")
@Timeout(value = 5, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class DuckDbDeltaIntegrationTest {

  private static final String BUCKET = "delta";

  private static final String ACCESS_KEY = "delta-key";

  private static final String SECRET_KEY = "delta-secret";

  private static final StructType SCHEMA = new StructType()
      .add("id", LongType.LONG)
      .add("name", StringType.STRING);

  private LocalS3 localS3;

  private S3Client s3;

  private Engine engine;

  private Connection duckdb;

  private String tablePath;

  @BeforeEach
  void setUp() throws SQLException {
    localS3 = LocalS3.builder()
        .port(-1)
        .credentials(ACCESS_KEY, SECRET_KEY)
        .netty(netty -> netty.registerShutdownHook(false))
        .build();
    localS3.start();

    s3 = S3Client.builder()
        .endpointOverride(URI.create("http://127.0.0.1:" + localS3.getPort()))
        .region(Region.US_EAST_1)
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
        .forcePathStyle(true)
        .build();
    s3.createBucket(request -> request.bucket(BUCKET));
    engine = DefaultEngine.create(new LocalS3DeltaFileIO(s3, BUCKET));
    tablePath = "s3://" + BUCKET + "/tables/events";

    duckdb = DriverManager.getConnection("jdbc:duckdb:");
    DuckDb.loadExtension(duckdb, "httpfs");
    DuckDb.loadExtension(duckdb, "delta");
    // A Delta table is addressed by its location, and nothing vends the credentials of the storage for it, so DuckDB
    // is configured with them itself — the same secret as for a plain Parquet file on LocalS3.
    execute(duckdb, "CREATE SECRET local_s3 (TYPE s3, ENDPOINT '127.0.0.1:" + localS3.getPort() + "', "
        + "URL_STYLE 'path', USE_SSL false, KEY_ID '" + ACCESS_KEY + "', SECRET '" + SECRET_KEY + "', "
        + "REGION 'us-east-1')");
  }

  @AfterEach
  void tearDown() throws SQLException {
    // LocalS3 first, so that a DuckDB thread still reading from it fails fast rather than hanging on a closed pool.
    if (localS3 != null) {
      localS3.shutdown();
    }
    if (s3 != null) {
      s3.close();
    }
    if (duckdb != null) {
      duckdb.close();
    }
  }

  /**
   * The rows of a table that Delta wrote, read back by {@code delta_scan}: the schema of the table, its column types
   * and every row of every version of its log.
   */
  @Test
  void delta_scan_reads_a_table_written_by_delta_kernel() throws Exception {
    DeltaTables.create(engine, tablePath, SCHEMA);
    DeltaTables.append(engine, tablePath, SCHEMA, List.of(new Object[] {1L, "one"}, new Object[] {2L, "two"}));

    assertEquals(List.<List<Object>>of(List.of(1L, "one"), List.of(2L, "two")),
        rows(duckdb, "SELECT id, name FROM delta_scan('" + tablePath + "') ORDER BY id"));
    assertEquals(List.<List<Object>>of(List.of("id", "BIGINT"), List.of("name", "VARCHAR")),
        rows(duckdb, "SELECT column_name, column_type FROM (DESCRIBE SELECT * FROM delta_scan('" + tablePath + "'))"));

    // A second version of the table: the scan replays the log, so it reads the files of both commits.
    DeltaTables.append(engine, tablePath, SCHEMA,
        // A List.of with a single array reads it as the varargs of the call, so the element type has to be named.
        List.<Object[]>of(new Object[] {3L, "three"}));
    assertEquals(List.of(1L, 2L, 3L),
        column(duckdb, "SELECT id FROM delta_scan('" + tablePath + "') ORDER BY id"));
    assertEquals(3L, single(duckdb, "SELECT count(*) FROM delta_scan('" + tablePath + "')"));
  }

  /**
   * A scan is a scan, not a download of the table: a filter and an aggregate are answered from the files the log names,
   * and DuckDB reads those Parquet files from LocalS3 with range requests, as it does a plain Parquet file.
   */
  @Test
  void delta_scan_answers_filters_and_aggregates_over_the_files_of_the_table() throws Exception {
    DeltaTables.create(engine, tablePath, SCHEMA);
    for (long batch = 0; batch < 4; batch++) {
      List<Object[]> rows = new java.util.ArrayList<>();
      for (long id = batch * 250; id < (batch + 1) * 250; id++) {
        rows.add(new Object[] {id, "row-" + id});
      }
      DeltaTables.append(engine, tablePath, SCHEMA, rows);
    }

    assertEquals(1000L, single(duckdb, "SELECT count(*) FROM delta_scan('" + tablePath + "')"));
    assertEquals(499_500L, single(duckdb, "SELECT sum(id)::BIGINT FROM delta_scan('" + tablePath + "')"));
    assertEquals(List.<List<Object>>of(List.of(750L, "row-750")),
        rows(duckdb, "SELECT id, name FROM delta_scan('" + tablePath + "') WHERE id = 750"));
    assertEquals(250L, single(duckdb, "SELECT count(*) FROM delta_scan('" + tablePath + "') WHERE id >= 750"));

    // The reads reached LocalS3, and there are far more of them than the table has objects: the extension reads the
    // commits of the log and the parts of a data file that a query needs, rather than downloading each file once.
    long reads = localS3.statistics().operations().get("GetObject").count();
    long objects = s3.listObjectsV2(request -> request.bucket(BUCKET).prefix("tables/events/")).contents().size();
    assertTrue(reads > objects, "The queries made " + reads + " reads of the " + objects + " objects of the table.");
  }

  /**
   * A Delta table and a plain Parquet file in one query: the point of reading a lakehouse table from the IDE is being
   * able to join it with everything else that is in the bucket.
   */
  @Test
  void a_delta_table_joins_a_parquet_file_of_the_same_bucket() throws Exception {
    DeltaTables.create(engine, tablePath, SCHEMA);
    DeltaTables.append(engine, tablePath, SCHEMA,
        List.of(new Object[] {1L, "one"}, new Object[] {2L, "two"}, new Object[] {3L, "three"}));

    execute(duckdb, "COPY (SELECT 1 AS id, 'info' AS level UNION ALL SELECT 3, 'warn') "
        + "TO 's3://" + BUCKET + "/levels.parquet' (FORMAT parquet)");

    assertEquals(List.<List<Object>>of(List.of(1L, "one", "info"), List.of(3L, "three", "warn")),
        rows(duckdb, "SELECT t.id, t.name, l.level FROM delta_scan('" + tablePath + "') t "
            + "JOIN read_parquet('s3://" + BUCKET + "/levels.parquet') l ON l.id = t.id ORDER BY t.id"));
  }

}
