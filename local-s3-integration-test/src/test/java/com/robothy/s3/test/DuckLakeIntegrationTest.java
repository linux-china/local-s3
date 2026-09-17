package com.robothy.s3.test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.rest.LocalS3;
import com.robothy.s3.rest.admin.RequestStatistics;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
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
 * DuckLake, the lakehouse format of DuckDB, with its catalog in a local DuckDB file and its data files on LocalS3:
 *
 * <pre>{@code
 * ATTACH 'ducklake:metadata.ducklake' AS lake (DATA_PATH 's3://lake/data/', ENCRYPTED);
 * CREATE TABLE lake.t AS SELECT * FROM range(100000);
 * UPDATE lake.t SET range = range + 1 WHERE range % 2 = 0;
 * CALL ducklake_merge_adjacent_files('lake');
 * CALL ducklake_expire_snapshots('lake', older_than => now());
 * CALL ducklake_cleanup_old_files('lake', cleanup_all => true);
 * }</pre>
 *
 * <p>What DuckLake needs from S3 is a superset of what {@code httpfs} needs: Parquet files written with
 * {@code PutObject}, read with range requests, and deleted with {@code DeleteObjects} when the snapshots that refer to
 * them expire. The tests check the objects on LocalS3 with the AWS SDK, not only the results of the queries.
 *
 * <p>The catalog keeps the rows of small inserts in its own tables instead of writing a file for them; the tests set
 * {@code DATA_INLINING_ROW_LIMIT 0}, so that every change is a Parquet file on LocalS3.
 *
 * <p>The {@code httpfs} and {@code ducklake} extensions are loaded from the extension directory of DuckDB, and installed
 * from the extension repository of DuckDB if they aren't there yet; without network access and without installed
 * extensions, the tests are skipped. See {@link DuckDbParquetIntegrationTest} for the timeout.
 */
@Tag("data-tools")
@Timeout(value = 3, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class DuckLakeIntegrationTest {

  private static final String BUCKET = "lake";

  /**
   * The Parquet magic of a file with a plain footer, at its start and its end.
   */
  private static final byte[] PLAIN_MAGIC = "PAR1".getBytes();

  /**
   * The Parquet magic of a file with an encrypted footer, at its start and its end.
   */
  private static final byte[] ENCRYPTED_MAGIC = "PARE".getBytes();

  @TempDir
  Path directory;

  private LocalS3 localS3;

  private S3Client s3;

  private Connection duckdb;

  @BeforeEach
  void setUp() throws SQLException {
    localS3 = LocalS3.builder().port(-1).buckets(BUCKET).build();
    localS3.start();
    s3 = S3Client.builder()
        .endpointOverride(URI.create("http://127.0.0.1:" + localS3.getPort()))
        .region(Region.US_EAST_1)
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("any-access-key", "any-secret-key")))
        .forcePathStyle(true)
        .build();
    duckdb = DriverManager.getConnection("jdbc:duckdb:");
    loadExtension(duckdb, "httpfs");
    loadExtension(duckdb, "ducklake");
    execute(duckdb, "CREATE SECRET local_s3 (TYPE s3, ENDPOINT '127.0.0.1:" + localS3.getPort() + "', URL_STYLE 'path', "
        + "USE_SSL false, KEY_ID 'any-access-key', SECRET 'any-secret-key', REGION 'us-east-1')");
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
    if (duckdb != null) {
      duckdb.close();
    }
  }

  /**
   * Each snapshot of a table reads the files that it refers to, which stay on LocalS3 after later changes: the snapshot
   * of the {@code CREATE TABLE}, the one of the inserts, and the one of the {@code UPDATE}, which writes the changed
   * rows to a new file and the positions of the old rows to delete files.
   */
  @Test
  void readsTheSnapshotsOfATableWithTimeTravel() throws Exception {
    attach("lake", "s3://lake/data/", true);
    writeHistory();

    assertEquals(List.of(0L, 1L, 2L, 3L, 4L), column(duckdb, "SELECT snapshot_id FROM lake.snapshots() ORDER BY 1"));
    assertEquals(List.of(List.of(100_000L, 4_999_950_000L, 50_000L)), versionOfT(1));
    assertEquals(List.of(List.of(100_020L, 5_001_950_190L, 50_010L)), versionOfT(3));
    // Every even value is now odd, and the sum grew by one for each of them.
    assertEquals(List.of(List.of(100_020L, 5_002_000_200L, 0L)), versionOfT(4));
    assertEquals(versionOfT(4), rows(duckdb, "SELECT count(*), sum(range)::BIGINT, count(*) FILTER (range % 2 = 0) FROM lake.t"));

    assertEquals(0, count("DeleteObjects") + count("DeleteObject"), "No snapshot was expired, so no file is deleted.");
  }

  /**
   * The files that compaction replaces stay on LocalS3 while a snapshot refers to them. Expiring the snapshots only
   * schedules them for deletion in the catalog; {@code ducklake_cleanup_old_files} deletes them from LocalS3, and leaves
   * exactly the files of the current snapshot, which still reads the table.
   */
  @Test
  void cleanupDeletesTheFilesOfExpiredSnapshotsFromLocalS3() throws Exception {
    attach("lake", "s3://lake/data/", true);
    writeHistory();

    // Merging skips files with deletes, so the deletes of the UPDATE are applied first.
    execute(duckdb, "CALL ducklake_rewrite_data_files('lake', delete_threshold => 0)");
    long merged = single(duckdb, "SELECT sum(files_processed)::BIGINT FROM ducklake_merge_adjacent_files('lake')");
    assertTrue(merged >= 2, "The small files of the inserts must have been merged, but " + merged + " were.");

    Set<String> written = keys("data/");
    Set<String> current = currentFiles("lake", "t");
    assertEquals(1, current.size(), "One file must hold the whole table after merging: " + current);
    assertTrue(written.containsAll(current), written + " must contain " + current);

    execute(duckdb, "CALL ducklake_expire_snapshots('lake', older_than => now())");
    Set<String> scheduled = new TreeSet<>(column(duckdb,
        "SELECT path FROM __ducklake_metadata_lake.ducklake_files_scheduled_for_deletion").stream()
        .map(path -> "data/" + path).toList());
    assertTrue(scheduled.size() >= 4, "The replaced data and delete files must be scheduled for deletion: " + scheduled);
    assertEquals(written, keys("data/"), "Expiring snapshots must not delete files yet.");
    assertEquals(0, count("DeleteObjects") + count("DeleteObject"));

    execute(duckdb, "CALL ducklake_cleanup_old_files('lake', cleanup_all => true)");

    assertEquals(current, keys("data/"), "Only the files of the current snapshot must be left on LocalS3.");
    Set<String> deleted = new TreeSet<>(written);
    deleted.removeAll(current);
    assertEquals(scheduled, deleted);
    RequestStatistics.OperationStatistics deletes = localS3.statistics().operations().get("DeleteObjects");
    assertTrue(deletes != null && deletes.count() >= 1, "The files must be deleted with DeleteObjects: " + deletes);
    assertEquals(0, deletes.clientErrors() + deletes.serverErrors(), deletes.toString());
    assertEquals(0L, single(duckdb, "SELECT count(*) FROM __ducklake_metadata_lake.ducklake_files_scheduled_for_deletion"));

    assertEquals(List.of(List.of(100_020L, 5_002_000_200L, 0L)),
        rows(duckdb, "SELECT count(*), sum(range)::BIGINT, count(*) FILTER (range % 2 = 0) FROM lake.t"));
    SQLException expired = assertThrows(SQLException.class, () -> versionOfT(1));
    assertTrue(expired.getMessage().contains("No snapshot found at version 1"), expired.getMessage());
  }

  /**
   * With {@code ENCRYPTED}, DuckLake encrypts every Parquet file with a key of its own, which only the catalog holds:
   * an object downloaded from LocalS3 has the magic of an encrypted footer and isn't readable as a Parquet file, while
   * the same table without {@code ENCRYPTED} is. LocalS3 stores the encrypted bytes as they are.
   */
  @Test
  void encryptedDataFilesAreNotReadableAsPlainParquet() throws Exception {
    attach("lake", "s3://lake/encrypted/", true);
    attach("plain_lake", "s3://lake/plain/", false);
    for (String lake : List.of("lake", "plain_lake")) {
      execute(duckdb, "CREATE TABLE " + lake + ".t AS SELECT range AS id, md5(range::VARCHAR) AS payload FROM range(10000)");
    }

    List<List<Object>> encryptedFiles = rows(duckdb,
        "SELECT data_file, data_file_size_bytes::BIGINT FROM ducklake_list_files('lake', 't')");
    assertFalse(encryptedFiles.isEmpty());
    for (List<Object> file : encryptedFiles) {
      Path downloaded = download((String) file.get(0));
      byte[] bytes = Files.readAllBytes(downloaded);
      assertEquals(((Number) file.get(1)).longValue(), bytes.length, "The object must have the size in the catalog.");
      assertArrayEquals(ENCRYPTED_MAGIC, Arrays.copyOfRange(bytes, 0, 4));
      assertArrayEquals(ENCRYPTED_MAGIC, Arrays.copyOfRange(bytes, bytes.length - 4, bytes.length));
      SQLException unreadable = assertThrows(SQLException.class,
          () -> single(duckdb, "SELECT count(*) FROM read_parquet('" + sqlPath(downloaded) + "')"));
      assertTrue(unreadable.getMessage().contains("encrypted"), unreadable.getMessage());
    }
    // Through the catalog, which holds the keys, the table reads back from LocalS3.
    assertEquals(List.of(List.of(10_000L, 49_995_000L)), rows(duckdb, "SELECT count(*), sum(id)::BIGINT FROM lake.t"));

    for (Object file : column(duckdb, "SELECT data_file FROM ducklake_list_files('plain_lake', 't')")) {
      Path downloaded = download((String) file);
      byte[] bytes = Files.readAllBytes(downloaded);
      assertArrayEquals(PLAIN_MAGIC, Arrays.copyOfRange(bytes, bytes.length - 4, bytes.length));
      assertEquals(List.of(List.of(10_000L, 49_995_000L)),
          rows(duckdb, "SELECT count(*), sum(id)::BIGINT FROM read_parquet('" + sqlPath(downloaded) + "')"));
    }
  }

  /**
   * Attaches a DuckLake whose catalog is a DuckDB file in the temporary directory and whose data files are on LocalS3.
   */
  private void attach(String name, String dataPath, boolean encrypted) throws SQLException {
    execute(duckdb, "ATTACH 'ducklake:" + sqlPath(directory.resolve(name + ".ducklake")) + "' AS " + name
        + " (DATA_PATH '" + dataPath + "', DATA_INLINING_ROW_LIMIT 0" + (encrypted ? ", ENCRYPTED" : "") + ")");
  }

  /**
   * Snapshot 1 creates {@code lake.t} with 100000 rows, snapshots 2 and 3 insert 10 rows each, in files of their own,
   * and snapshot 4 updates the even rows. Snapshot 0 created the schema {@code main}.
   */
  private void writeHistory() throws SQLException {
    execute(duckdb, "CREATE TABLE lake.t AS SELECT * FROM range(100000)");
    execute(duckdb, "INSERT INTO lake.t SELECT * FROM range(100000, 100010)");
    execute(duckdb, "INSERT INTO lake.t SELECT * FROM range(100010, 100020)");
    execute(duckdb, "UPDATE lake.t SET range = range + 1 WHERE range % 2 = 0");
  }

  /**
   * The row count, the sum and the number of even values of {@code lake.t} at a snapshot.
   */
  private List<List<Object>> versionOfT(int version) throws SQLException {
    return rows(duckdb, "SELECT count(*), sum(range)::BIGINT, count(*) FILTER (range % 2 = 0) "
        + "FROM lake.t AT (VERSION => " + version + ")");
  }

  /**
   * The keys of the data and delete files of the current snapshot of a table.
   */
  private Set<String> currentFiles(String lake, String table) throws SQLException {
    Set<String> keys = new TreeSet<>();
    for (List<Object> row : rows(duckdb, "SELECT data_file, delete_file FROM ducklake_list_files('" + lake + "', '"
        + table + "')")) {
      for (Object file : row) {
        if (file != null) {
          keys.add(key((String) file));
        }
      }
    }
    return keys;
  }

  private Set<String> keys(String prefix) {
    return new TreeSet<>(s3.listObjectsV2Paginator(request -> request.bucket(BUCKET).prefix(prefix)).contents().stream()
        .map(S3Object::key).toList());
  }

  private Path download(String url) {
    String key = key(url);
    Path file = directory.resolve(key.replace('/', '_'));
    s3.getObject(request -> request.bucket(BUCKET).key(key), file);
    return file;
  }

  /**
   * The key of an {@code s3://lake/...} URL.
   */
  private static String key(String url) {
    String prefix = "s3://" + BUCKET + "/";
    assertTrue(url.startsWith(prefix), url);
    return url.substring(prefix.length());
  }

  private long count(String operation) {
    RequestStatistics.OperationStatistics statistics = localS3.statistics().operations().get(operation);
    return statistics == null ? 0 : statistics.count();
  }

  private static void loadExtension(Connection connection, String extension) throws SQLException {
    try {
      execute(connection, "LOAD " + extension);
    } catch (SQLException notInstalled) {
      try {
        execute(connection, "INSTALL " + extension);
        execute(connection, "LOAD " + extension);
      } catch (SQLException e) {
        connection.close();
        Assumptions.abort("The " + extension + " extension of DuckDB is neither installed nor downloadable: "
            + e.getMessage());
      }
    }
  }

  private static void execute(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private static List<List<Object>> rows(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql)) {
      int columns = resultSet.getMetaData().getColumnCount();
      List<List<Object>> rows = new ArrayList<>();
      while (resultSet.next()) {
        List<Object> row = new ArrayList<>(columns);
        for (int column = 1; column <= columns; column++) {
          row.add(resultSet.getObject(column));
        }
        rows.add(row);
      }
      return rows;
    }
  }

  private static <T> List<T> column(Connection connection, String sql) throws SQLException {
    List<T> values = new ArrayList<>();
    for (List<Object> row : rows(connection, sql)) {
      @SuppressWarnings("unchecked")
      T value = (T) row.get(0);
      values.add(value);
    }
    return values;
  }

  private static long single(Connection connection, String sql) throws SQLException {
    List<List<Object>> rows = rows(connection, sql);
    assertEquals(1, rows.size(), sql);
    return ((Number) rows.get(0).get(0)).longValue();
  }

  /**
   * A local path as a string literal of DuckDB, with forward slashes, which DuckDB accepts on Windows as well.
   */
  private static String sqlPath(Path path) {
    return path.toAbsolutePath().toString().replace('\\', '/').replace("'", "''");
  }

}
