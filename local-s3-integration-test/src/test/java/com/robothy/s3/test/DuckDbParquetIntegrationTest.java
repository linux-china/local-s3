package com.robothy.s3.test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.rest.LocalS3;
import com.robothy.s3.rest.LocalS3Tls;
import com.robothy.s3.rest.admin.RequestStatistics;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.duckdb.DuckDBConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Object;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * DuckDB, through the {@code httpfs} extension of its JDBC driver, reading and writing Parquet files on LocalS3, the
 * way an IDE or a data platform uses LocalS3 as its default S3 service:
 *
 * <pre>{@code
 * CREATE SECRET (TYPE s3, ENDPOINT 'localhost:29090', URL_STYLE 'path', USE_SSL false, KEY_ID '...', SECRET '...');
 * COPY (...) TO 's3://bucket/events' (FORMAT parquet, PARTITION_BY (part));
 * SELECT * FROM read_parquet('s3://bucket/events/**}{@code /*.parquet');
 * }</pre>
 *
 * <p>Besides the results of the queries, the tests check the requests that LocalS3 answered, so that each of them
 * covers the S3 feature it is about: a large file is written with a multipart upload and read with range requests,
 * and a glob over more partitions than a listing page holds follows the continuation tokens of {@code ListObjectsV2}.
 *
 * <p>The {@code httpfs} extension is loaded from the extension directory of DuckDB, and installed from the extension
 * repository of DuckDB if it isn't there yet; without network access and without an installed extension, the tests
 * are skipped. Every test has a timeout, since a listing whose continuation tokens don't advance makes DuckDB list
 * forever rather than fail. The test runs on a thread of its own, which the timeout abandons: a query of DuckDB runs in
 * native code, which an interrupt doesn't stop. LocalS3 is shut down first, which fails such a query.
 */
@Tag("data-tools")
@Timeout(value = 3, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class DuckDbParquetIntegrationTest {

  private static final String BUCKET = "lake";

  private static final String ACCESS_KEY = "duckdb-access-key";

  private static final String SECRET_KEY = "duckdb-secret-key";

  private final ObjectMapper objectMapper = new ObjectMapper();

  private final HttpClient httpClient = HttpClient.newHttpClient();

  private LocalS3 localS3;

  private S3Client s3;

  private Connection duckdb;

  @BeforeEach
  void setUp() throws SQLException {
    localS3 = start(LocalS3.builder().port(-1).buckets(BUCKET));
    s3 = s3Client(localS3.getPort(), "any-access-key", "any-secret-key");
    duckdb = duckDb(localS3.getPort(), "any-access-key", "any-secret-key");
  }

  @AfterEach
  void tearDown() throws SQLException {
    // LocalS3 first: a query of DuckDB that is still running, e.g. one that a timeout abandoned, then fails instead of
    // keeping the connection from closing.
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
   * The example of the README: a CSV file converted to a Parquet file on LocalS3, which DuckDB reads back, and which
   * any other S3 client downloads as a valid Parquet file.
   */
  @Test
  void copiesACsvFileToAParquetFileAndReadsItBack() throws Exception {
    execute(duckdb, "COPY (SELECT * FROM read_csv('" + sqlPath(resource("family.csv")) + "')) "
        + "TO 's3://lake/family.parquet' (FORMAT parquet)");

    assertEquals(List.of(
            List.of(1L, "Jackie", 48L),
            List.of(2L, "Love", 48L),
            List.of(3L, "Lucas", 10L),
            List.of(4L, "Laura", 8L)),
        rows(duckdb, "SELECT id, name, age FROM read_parquet('s3://lake/family.parquet') ORDER BY id"));

    byte[] parquet = s3.getObjectAsBytes(request -> request.bucket(BUCKET).key("family.parquet")).asByteArray();
    assertArrayEquals("PAR1".getBytes(), Arrays.copyOfRange(parquet, 0, 4), "A Parquet file starts with PAR1.");
    assertArrayEquals("PAR1".getBytes(), Arrays.copyOfRange(parquet, parquet.length - 4, parquet.length),
        "A Parquet file ends with PAR1.");
  }

  /**
   * A file larger than the part size of DuckDB is written with a multipart upload, and read with range requests: the
   * footer first, then the row groups that the query needs.
   */
  @Test
  void writesALargeFileInPartsAndReadsItWithRangeRequests() throws Exception {
    // The part size of DuckDB is the max file size divided by the max number of parts; 50GB / 10000 parts is the
    // 5 MiB minimum part size of Amazon S3, so the ~30 MiB file below takes several parts.
    execute(duckdb, "SET s3_uploader_max_filesize = '50GB'");
    execute(duckdb, "COPY (SELECT i AS id, md5(i::VARCHAR) || md5((i * 7)::VARCHAR) AS payload FROM range(0, 400000) t(i)) "
        + "TO 's3://lake/big.parquet' (FORMAT parquet, COMPRESSION uncompressed, ROW_GROUP_SIZE 50000)");

    Map<String, RequestStatistics.OperationStatistics> written = operations();
    assertEquals(1, count(written, "CreateMultipartUpload"));
    assertEquals(1, count(written, "CompleteMultipartUpload"));
    long parts = count(written, "UploadPart");
    assertTrue(parts >= 2, "The file must be uploaded in several parts, but was uploaded in " + parts);
    assertEquals(0, count(written, "PutObject"));

    HeadObjectResponse head = s3.headObject(request -> request.bucket(BUCKET).key("big.parquet"));
    assertTrue(head.contentLength() > 10L * 1024 * 1024, "The file has " + head.contentLength() + " bytes.");
    assertTrue(Pattern.matches("\"[0-9a-f]{32}-" + parts + "\"", head.eTag()),
        "An object uploaded in " + parts + " parts has a composite entity tag, but has " + head.eTag());

    assertEquals(List.of(List.of(400000L, 79999800000L)),
        rows(duckdb, "SELECT count(*), sum(id)::BIGINT FROM read_parquet('s3://lake/big.parquet')"));
    assertEquals(15150L,
        single(duckdb, "SELECT sum(id)::BIGINT FROM read_parquet('s3://lake/big.parquet') WHERE id BETWEEN 100 AND 200"));

    List<JsonNode> reads = recentRequests().stream()
        .filter(request -> "GetObject".equals(request.get("operation").asText()))
        .filter(request -> request.get("uri").asText().equals("/lake/big.parquet"))
        .toList();
    assertTrue(reads.size() >= 2, "The file must be read in ranges, but was read with " + reads.size() + " requests.");
    assertTrue(reads.stream().allMatch(request -> request.get("status").asInt() == 206),
        "Every read must be a range request answered with 206 Partial Content: " + reads);
  }

  /**
   * DuckDB reads a Parquet file by reading its footer first and then the column chunks that the query needs, and
   * with {@code enable_http_metadata_cache} it keeps the {@code Content-Length} and the {@code ETag} of the first
   * request and reuses them for the later ranges. A {@code HEAD} and a {@code GET} of the same object must therefore
   * answer the same entity tag and the same length on every path: an object that a single {@code PutObject} stored
   * and one that a multipart upload composed, read whole, read as a range, and read as a part.
   */
  @Test
  void answersTheSameEtagAndLengthFromHeadAndGet() throws Exception {
    execute(duckdb, "COPY (SELECT i AS id FROM range(0, 1000) t(i)) "
        + "TO 's3://lake/cached/single.parquet' (FORMAT parquet)");
    // A file larger than the part size, i.e. 50GB / 10000 parts, which DuckDB uploads in parts.
    execute(duckdb, "SET s3_uploader_max_filesize = '50GB'");
    execute(duckdb, "COPY (SELECT i AS id, md5(i::VARCHAR) || md5((i * 7)::VARCHAR) AS payload FROM range(0, 120000) t(i)) "
        + "TO 's3://lake/cached/multipart.parquet' (FORMAT parquet, COMPRESSION uncompressed, ROW_GROUP_SIZE 20000)");

    String single = "cached/single.parquet";
    String multipart = "cached/multipart.parquet";
    assertTrue(Pattern.matches("\"[0-9a-f]{32}\"", s3.headObject(request -> request.bucket(BUCKET).key(single)).eTag()),
        "The small file must be stored by a single PutObject, which gives it the entity tag of its content.");
    String compositeEtag = s3.headObject(request -> request.bucket(BUCKET).key(multipart)).eTag();
    assertTrue(Pattern.matches("\"[0-9a-f]{32}-[0-9]+\"", compositeEtag),
        "The large file must be composed by a multipart upload, but has the entity tag " + compositeEtag);

    for (String key : List.of(single, multipart)) {
      Metadata head = metadata("HEAD", key, "", null);
      Metadata get = metadata("GET", key, "", null);
      assertEquals(200, head.status(), key);
      assertEquals(200, get.status(), key);
      assertTrue(head.etag().startsWith("\"") && head.etag().endsWith("\""),
          "An entity tag is quoted, but " + key + " answered " + head.etag());
      assertEquals(head.etag(), get.etag(), "HEAD and GET must answer the same entity tag for " + key);
      assertEquals(head.contentLength(), get.contentLength(), "HEAD and GET must answer the same length for " + key);
      assertEquals(get.contentLength(), get.bodyLength(), "The body of " + key + " must have the announced length.");

      // The suffix range of the footer, which is how DuckDB starts reading a Parquet file.
      long size = head.contentLength();
      Metadata footerHead = metadata("HEAD", key, "", "bytes=-64");
      Metadata footerGet = metadata("GET", key, "", "bytes=-64");
      assertEquals(206, footerHead.status(), key);
      assertEquals(206, footerGet.status(), key);
      assertEquals(head.etag(), footerGet.etag(), "A range of " + key + " must carry the entity tag of the object.");
      assertEquals(footerHead.etag(), footerGet.etag(), key);
      assertEquals(64, footerGet.contentLength(), key);
      assertEquals(footerHead.contentLength(), footerGet.contentLength(), key);
      assertEquals(64, footerGet.bodyLength(), key);
      assertEquals("bytes " + (size - 64) + "-" + (size - 1) + "/" + size, footerGet.contentRange(), key);
      assertEquals(footerHead.contentRange(), footerGet.contentRange(), key);

      // A range of the front, which the column chunks of a query are read with.
      Metadata chunkHead = metadata("HEAD", key, "", "bytes=0-1023");
      Metadata chunkGet = metadata("GET", key, "", "bytes=0-1023");
      assertEquals(head.etag(), chunkGet.etag(), key);
      assertEquals(chunkHead.etag(), chunkGet.etag(), key);
      assertEquals(1024, chunkGet.contentLength(), key);
      assertEquals(chunkHead.contentLength(), chunkGet.contentLength(), key);
      assertEquals(1024, chunkGet.bodyLength(), key);
      assertEquals("bytes 0-1023/" + size, chunkGet.contentRange(), key);
      assertEquals(chunkHead.contentRange(), chunkGet.contentRange(), key);

      // A read of a part, which answers the entity tag of the whole object and the length of that part.
      Metadata partHead = metadata("HEAD", key, "?partNumber=1", null);
      Metadata partGet = metadata("GET", key, "?partNumber=1", null);
      assertEquals(206, partHead.status(), key);
      assertEquals(206, partGet.status(), key);
      assertEquals(head.etag(), partGet.etag(), key);
      assertEquals(partHead.etag(), partGet.etag(), key);
      assertEquals(partHead.contentLength(), partGet.contentLength(), key);
      assertEquals(partGet.contentLength(), partGet.bodyLength(), key);
      assertEquals(partHead.contentRange(), partGet.contentRange(), key);
      assertEquals(partHead.partsCount(), partGet.partsCount(), key);
    }

    // The metadata cache of DuckDB on: the length and the entity tag that the first request of a file answered are
    // what its later ranges are read and validated against.
    execute(duckdb, "SET enable_external_file_cache = false");
    execute(duckdb, "SET enable_http_metadata_cache = true");
    long metadataReadsBefore = count(operations(), "HeadObject");
    assertEquals(499500L, single(duckdb, "SELECT sum(id)::BIGINT FROM read_parquet('s3://lake/cached/single.parquet')"));
    assertEquals(List.of(List.of(120000L, 7199940000L)), rows(duckdb,
        "SELECT count(*), sum(id)::BIGINT FROM read_parquet('s3://lake/cached/multipart.parquet')"));
    // Read again, which the cached metadata of both files is reused for.
    assertEquals(15150L, single(duckdb, "SELECT sum(id)::BIGINT "
        + "FROM read_parquet('s3://lake/cached/multipart.parquet') WHERE id BETWEEN 100 AND 200"));
    assertEquals(120000L, single(duckdb, "SELECT count(DISTINCT payload) "
        + "FROM read_parquet('s3://lake/cached/multipart.parquet')"));

    assertTrue(count(operations(), "HeadObject") > metadataReadsBefore,
        "DuckDB must have asked for the metadata of the files it read.");
    for (String operation : List.of("GetObject", "HeadObject")) {
      RequestStatistics.OperationStatistics statistics = operations().get(operation);
      if (statistics != null) {
        assertEquals(0, statistics.clientErrors(), operation + ": " + statistics);
        assertEquals(0, statistics.serverErrors(), operation + ": " + statistics);
      }
    }
  }

  /**
   * Queries that run at once on a file of many row groups, each of which DuckDB reads on threads of its own: LocalS3
   * answers many small range requests at once, and every query gets the rows of its filter.
   */
  @Test
  void answersTheConcurrentRangeRequestsOfConcurrentQueries() throws Exception {
    execute(duckdb, "COPY (SELECT i AS id, md5(i::VARCHAR) AS payload FROM range(0, 400000) t(i)) "
        + "TO 's3://lake/row-groups.parquet' (FORMAT parquet, ROW_GROUP_SIZE 10000)");
    execute(duckdb, "SET threads = 8");
    // Without the caches of DuckDB, which would answer the later rounds from memory, each round reads the file again.
    execute(duckdb, "SET enable_external_file_cache = false");
    execute(duckdb, "SET enable_http_metadata_cache = false");

    int queries = 8;
    int rounds = 5;
    ExecutorService executor = Executors.newFixedThreadPool(queries + 1);
    AtomicBoolean querying = new AtomicBoolean(true);
    List<Connection> connections = new ArrayList<>();
    try {
      // The most requests that LocalS3 was handling at once.
      Future<Integer> maxInFlight = executor.submit(() -> {
        int max = 0;
        while (querying.get()) {
          max = Math.max(max, localS3.statistics().inFlightRequests());
          Thread.onSpinWait();
        }
        return max;
      });
      CyclicBarrier start = new CyclicBarrier(queries);
      List<Future<?>> results = new ArrayList<>();
      for (int query = 0; query < queries; query++) {
        // A connection of the same database, with its secret and settings.
        Connection connection = ((DuckDBConnection) duckdb).duplicate();
        connections.add(connection);
        long from = query * 50_000L;
        long to = from + 49_999;
        results.add(executor.submit(() -> {
          start.await();
          for (int round = 0; round < rounds; round++) {
            assertEquals(List.of(List.of(50_000L, (from + to) * 50_000 / 2, 50_000L)), rows(connection,
                "SELECT count(*), sum(id)::BIGINT, count(DISTINCT payload) FROM read_parquet('s3://lake/row-groups.parquet') "
                    + "WHERE id BETWEEN " + from + " AND " + to));
          }
          return null;
        }));
      }
      for (Future<?> result : results) {
        result.get();
      }
      querying.set(false);
      assertTrue(maxInFlight.get() >= 2, "LocalS3 must have answered range requests at once, but answered at most "
          + maxInFlight.get());
    } finally {
      querying.set(false);
      executor.shutdownNow();
      for (Connection connection : connections) {
        connection.close();
      }
    }

    RequestStatistics.OperationStatistics gets = operations().get("GetObject");
    // At least the footer and a row group for each query of each round.
    assertTrue(gets.count() >= 2L * queries * rounds, gets.toString());
    assertEquals(0, gets.clientErrors(), gets.toString());
    assertEquals(0, gets.serverErrors(), gets.toString());
    List<JsonNode> reads = recentRequests().stream()
        .filter(request -> "GetObject".equals(request.get("operation").asText()))
        .toList();
    assertFalse(reads.isEmpty());
    assertTrue(reads.stream().allMatch(request -> request.get("status").asInt() == 206),
        "Every read must be a range request answered with 206 Partial Content: " + reads);
  }

  /**
   * The S3 settings of DuckDB instead of a secret, e.g. for a version of DuckDB without secrets or a script that sets
   * them: {@code s3_url_style = 'path'} addresses the bucket in the path, {@code http://127.0.0.1:port/lake/key},
   * since a local endpoint has no DNS name for each bucket.
   */
  @Test
  void connectsWithTheS3SettingsOfDuckDbInsteadOfASecret() throws Exception {
    try (Connection connection = DriverManager.getConnection("jdbc:duckdb:")) {
      loadHttpfs(connection);
      execute(connection, "SET s3_endpoint = '127.0.0.1:" + localS3.getPort() + "'");
      execute(connection, "SET s3_url_style = 'path'");
      execute(connection, "SET s3_use_ssl = false");
      execute(connection, "SET s3_region = 'us-east-1'");
      execute(connection, "SET s3_access_key_id = 'any-access-key'");
      execute(connection, "SET s3_secret_access_key = 'any-secret-key'");

      execute(connection, "COPY (SELECT i AS id FROM range(0, 10) t(i)) TO 's3://lake/settings.parquet' (FORMAT parquet)");

      assertEquals(45L, single(connection, "SELECT sum(id)::BIGINT FROM read_parquet('s3://lake/settings.parquet')"));
      assertTrue(recentRequests().stream()
              .filter(request -> "PutObject".equals(request.get("operation").asText()))
              .anyMatch(request -> request.get("uri").asText().startsWith("/lake/settings.parquet")),
          "The bucket must be in the path of the requests.");
    }
  }

  /**
   * {@code COPY ... PARTITION_BY} writes a file per partition into Hive style directories, e.g.
   * {@code events/part=7/data_0.parquet}, and a glob over more of them than a listing page holds lists them with the
   * continuation tokens of {@code ListObjectsV2}. The {@code =} of the keys is URL encoded in the listing that DuckDB
   * requests, which is what made the tokens of LocalS3 point back to the start of the listing.
   */
  @Test
  void writesPartitionsAndReadsThemBackWithAGlobAcrossListingPages() throws Exception {
    int partitions = 1100;
    execute(duckdb, "COPY (SELECT i AS id, i % " + partitions + " AS part FROM range(0, 5500) t(i)) "
        + "TO 's3://lake/events' (FORMAT parquet, PARTITION_BY (part))");

    List<String> keys = s3.listObjectsV2Paginator(request -> request.bucket(BUCKET).prefix("events/")).contents()
        .stream().map(S3Object::key).toList();
    assertEquals(partitions, keys.size());
    assertTrue(keys.contains("events/part=7/data_0.parquet"), keys.subList(0, 3).toString());

    long listingsBefore = count(operations(), "ListObjectsV2");
    assertEquals(List.of(List.of(5500L, (long) partitions, 15122250L)),
        rows(duckdb, "SELECT count(*), count(DISTINCT part), sum(id)::BIGINT "
            + "FROM read_parquet('s3://lake/events/**/*.parquet', hive_partitioning = true)"));
    long listings = count(operations(), "ListObjectsV2") - listingsBefore;
    assertTrue(listings >= 2, "The glob must list more than one page, but listed " + listings);

    assertEquals(List.of(List.of(5L)), rows(duckdb,
        "SELECT count(*) FROM read_parquet('s3://lake/events/**/*.parquet', hive_partitioning = true) WHERE part = 7"));
  }

  /**
   * A glob only reads the objects under its prefix, and a wildcard within a path segment only the matching
   * directories, e.g. {@code sales/**} doesn't read the sibling directory {@code sales_archive} or the file
   * {@code sales.parquet}, which share its prefix.
   */
  @Test
  void aGlobReadsTheObjectsUnderItsPrefixOnly() throws Exception {
    execute(duckdb, "COPY (SELECT y AS year, m AS month, r AS amount FROM range(2024, 2026) a(y), range(1, 13) b(m), "
        + "range(0, 2) c(r)) TO 's3://lake/sales' (FORMAT parquet, PARTITION_BY (year, month))");
    execute(duckdb, "COPY (SELECT 2025 AS year, 1 AS month, 99 AS amount FROM range(0, 10)) "
        + "TO 's3://lake/sales_archive' (FORMAT parquet, PARTITION_BY (year, month))");
    execute(duckdb, "COPY (SELECT 1 AS amount) TO 's3://lake/sales.parquet' (FORMAT parquet)");

    assertEquals(48L, single(duckdb, "SELECT count(*) FROM read_parquet('s3://lake/sales/**/*.parquet')"));
    assertEquals(24L, single(duckdb, "SELECT count(*) FROM read_parquet('s3://lake/sales/year=2025/*/*.parquet')"));
    // month=1, month=10, month=11 and month=12 of both years.
    assertEquals(16L, single(duckdb, "SELECT count(*) FROM read_parquet('s3://lake/sales/*/month=1*/*.parquet')"));
    assertEquals(58L, single(duckdb, "SELECT count(*) FROM read_parquet('s3://lake/sales*/**/*.parquet')"));
    assertEquals(2L, single(duckdb, "SELECT count(*) FROM read_parquet('s3://lake/sales/**/*.parquet', "
        + "hive_partitioning = true) WHERE year = 2024 AND month = 2"));
  }

  /**
   * The types that DuckDB writes to Parquet, including nested ones, read back unchanged with each compression codec.
   */
  @ParameterizedTest
  @ValueSource(strings = {"uncompressed", "snappy", "gzip", "zstd"})
  void roundTripsTheTypesOfDuckDbWithEachCompression(String compression) throws Exception {
    createTypesTable(duckdb);
    String url = "s3://lake/types-" + compression + ".parquet";
    execute(duckdb, "COPY types TO '" + url + "' (FORMAT parquet, COMPRESSION " + compression + ")");

    assertEquals(0L, single(duckdb, "SELECT count(*) FROM (SELECT * FROM types EXCEPT ALL "
        + "SELECT * FROM read_parquet('" + url + "'))"), "Rows of the table are missing from the file.");
    assertEquals(0L, single(duckdb, "SELECT count(*) FROM (SELECT * FROM read_parquet('" + url + "') EXCEPT ALL "
        + "SELECT * FROM types)"), "The file has rows that the table doesn't.");
  }

  /**
   * A Parquet file that DuckDB wrote to LocalS3 is downloaded intact by another S3 client, and one that another
   * client uploaded is read by DuckDB.
   */
  @Test
  void exchangesParquetFilesWithOtherS3Clients(@TempDir Path directory) throws Exception {
    createTypesTable(duckdb);
    execute(duckdb, "COPY types TO 's3://lake/by-duckdb.parquet' (FORMAT parquet)");
    Path downloaded = directory.resolve("downloaded.parquet");
    s3.getObject(request -> request.bucket(BUCKET).key("by-duckdb.parquet"), downloaded);
    assertEquals(0L, single(duckdb, "SELECT count(*) FROM (SELECT * FROM types EXCEPT ALL "
        + "SELECT * FROM read_parquet('" + sqlPath(downloaded) + "'))"));

    Path local = directory.resolve("local.parquet");
    execute(duckdb, "COPY types TO '" + sqlPath(local) + "' (FORMAT parquet)");
    s3.putObject(request -> request.bucket(BUCKET).key("by-sdk/local.parquet"), RequestBody.fromFile(local));
    assertEquals(0L, single(duckdb, "SELECT count(*) FROM (SELECT * FROM types EXCEPT ALL "
        + "SELECT * FROM read_parquet('s3://lake/by-sdk/*.parquet'))"));
  }

  /**
   * {@code OVERWRITE_OR_IGNORE} adds partitions to the ones that exist, and {@code OVERWRITE} deletes the files of the
   * directory before it writes its own, which DuckDB finds with a listing.
   */
  @Test
  void overwritesThePartitionsOfADirectory() throws Exception {
    execute(duckdb, "COPY (SELECT i AS id, i % 3 AS part FROM range(0, 30) t(i)) "
        + "TO 's3://lake/daily' (FORMAT parquet, PARTITION_BY (part))");
    execute(duckdb, "COPY (SELECT i AS id, 9 AS part FROM range(0, 2) t(i)) "
        + "TO 's3://lake/daily' (FORMAT parquet, PARTITION_BY (part), OVERWRITE_OR_IGNORE)");
    assertEquals(32L, single(duckdb, "SELECT count(*) FROM read_parquet('s3://lake/daily/**/*.parquet')"));

    execute(duckdb, "COPY (SELECT 1 AS id, 5 AS part) TO 's3://lake/daily' (FORMAT parquet, PARTITION_BY (part), OVERWRITE)");

    assertEquals(List.of("daily/part=5/data_0.parquet"),
        s3.listObjectsV2Paginator(request -> request.bucket(BUCKET).prefix("daily/")).contents().stream()
            .map(S3Object::key).toList());
    assertEquals(List.of(List.of(5L, 1L)), rows(duckdb, "SELECT part, count(*) "
        + "FROM read_parquet('s3://lake/daily/**/*.parquet', hive_partitioning = true) GROUP BY part"));
  }

  /**
   * The requests of DuckDB are signed with AWS Signature Version 4, which a LocalS3 that requires credentials
   * verifies, also for keys that are URL encoded in the canonical request, e.g. the {@code =} of a partition, spaces
   * and non-ASCII characters.
   */
  @Test
  void signsItsRequestsForALocalS3ThatRequiresCredentials() throws Exception {
    LocalS3 signed = start(LocalS3.builder().port(-1).buckets(BUCKET).credentials(ACCESS_KEY, SECRET_KEY));
    try (Connection authorized = duckDb(signed.getPort(), ACCESS_KEY, SECRET_KEY);
         Connection unauthorized = duckDb(signed.getPort(), ACCESS_KEY, "wrong-secret-key")) {
      execute(authorized, "COPY (SELECT i AS id, i % 3 AS part, 'naïve 数据 ' || i AS label FROM range(0, 30) t(i)) "
          + "TO 's3://lake/signed' (FORMAT parquet, PARTITION_BY (part))");
      execute(authorized, "COPY (SELECT 42 AS answer) TO 's3://lake/key with spaces & plus+ 数据.parquet' (FORMAT parquet)");

      assertEquals(30L, single(authorized, "SELECT count(*) FROM read_parquet('s3://lake/signed/**/*.parquet')"));
      assertEquals(42L, single(authorized,
          "SELECT answer::BIGINT FROM read_parquet('s3://lake/key with spaces & plus+ 数据.parquet')"));

      execute(unauthorized, "SET http_retries = 0");
      SQLException denied = assertThrows(SQLException.class,
          () -> single(unauthorized, "SELECT count(*) FROM read_parquet('s3://lake/signed/**/*.parquet')"));
      assertTrue(denied.getMessage().contains("403"), denied.getMessage());
    } finally {
      signed.shutdown();
    }
  }

  /**
   * DuckDB with its TLS defaults, i.e. {@code USE_SSL true}, against a LocalS3 that generated a certificate for itself:
   * the first connection of a client that expects HTTPS, which fails with an {@code SSL connect error} against a plain
   * HTTP service, and which needs no certificate of the machine here. DuckDB is given the generated certificate as its
   * CA, since nothing trusts a certificate that signed itself.
   */
  @Test
  void readsAndWritesOverHttpsWithAGeneratedCertificate(@TempDir Path directory) throws Exception {
    LocalS3Tls tls = LocalS3Tls.selfSigned();
    LocalS3 secured = start(LocalS3.builder().port(-1).buckets(BUCKET).tls(tls));
    // What the startup log of the service prints, saved the way a user saves it.
    Path caCertFile = Files.writeString(directory.resolve("local-s3.pem"), tls.certificateChainPem());
    try (Connection connection = DriverManager.getConnection("jdbc:duckdb:")) {
      loadHttpfs(connection);
      execute(connection, "SET ca_cert_file = '" + sqlPath(caCertFile) + "'");
      // No USE_SSL false, unlike every other secret of these tests: the defaults of DuckDB expect HTTPS.
      execute(connection, "CREATE SECRET local_s3_tls (TYPE s3, ENDPOINT 'localhost:" + secured.getPort() + "', "
          + "URL_STYLE 'path', KEY_ID 'any-access-key', SECRET 'any-secret-key', REGION 'us-east-1')");

      execute(connection, "COPY (SELECT i AS id, md5(i::VARCHAR) AS payload FROM range(0, 20000) t(i)) "
          + "TO 's3://lake/over-https.parquet' (FORMAT parquet, ROW_GROUP_SIZE 5000)");
      assertEquals(List.of(List.of(20000L, 199990000L)),
          rows(connection, "SELECT count(*), sum(id)::BIGINT FROM read_parquet('s3://lake/over-https.parquet')"));
      assertEquals(List.of(List.of(15150L)),
          rows(connection, "SELECT sum(id)::BIGINT FROM read_parquet('s3://lake/over-https.parquet')"
              + " WHERE id BETWEEN 100 AND 200"),
          "A range request over TLS, where a response is streamed rather than sent as a zero-copy file region.");

      assertTrue(secured.isTlsEnabled());
      assertEquals(List.of(), rows(connection, "SELECT 1 WHERE false"), "The connection still works.");
    } finally {
      secured.shutdown();
    }
  }

  private static LocalS3 start(com.robothy.s3.rest.LocalS3Builder builder) {
    LocalS3 localS3 = builder.build();
    localS3.start();
    return localS3;
  }

  private static S3Client s3Client(int port, String accessKey, String secretKey) {
    return S3Client.builder()
        .endpointOverride(URI.create("http://127.0.0.1:" + port))
        .region(Region.US_EAST_1)
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
        .forcePathStyle(true)
        .build();
  }

  /**
   * An in-memory DuckDB with the {@code httpfs} extension and an S3 secret for LocalS3, like the README describes.
   */
  private static Connection duckDb(int port, String accessKey, String secretKey) throws SQLException {
    Connection connection = DriverManager.getConnection("jdbc:duckdb:");
    try {
      loadHttpfs(connection);
      execute(connection, "CREATE SECRET local_s3 (TYPE s3, ENDPOINT '127.0.0.1:" + port + "', URL_STYLE 'path', "
          + "USE_SSL false, KEY_ID '" + accessKey + "', SECRET '" + secretKey + "', REGION 'us-east-1')");
      return connection;
    } catch (SQLException | RuntimeException e) {
      connection.close();
      throw e;
    }
  }

  private static void loadHttpfs(Connection connection) throws SQLException {
    try {
      execute(connection, "LOAD httpfs");
    } catch (SQLException notInstalled) {
      try {
        execute(connection, "INSTALL httpfs");
        execute(connection, "LOAD httpfs");
      } catch (SQLException e) {
        Assumptions.abort("The httpfs extension of DuckDB is neither installed nor downloadable: " + e.getMessage());
      }
    }
  }

  private static void createTypesTable(Connection connection) throws SQLException {
    execute(connection, """
        CREATE TABLE types AS SELECT
          i AS id,
          (i * 1.5)::DECIMAL(18, 3) AS amount,
          TIMESTAMP '2026-01-01 00:00:00' + to_seconds(i) AS created_at,
          TIMESTAMPTZ '2026-01-01 00:00:00+00' + to_seconds(i) AS created_at_tz,
          DATE '2026-01-01' + i::INTEGER AS day,
          [i, i + 1, NULL] AS numbers,
          {'k': i, 'v': 'v' || i} AS pair,
          MAP {'a': i} AS attributes,
          CASE WHEN i % 5 = 0 THEN NULL ELSE '数据-' || i END AS label,
          '\\xDE\\xAD'::BLOB AS bytes,
          uuid() AS uid,
          i % 2 = 0 AS even,
          i::DOUBLE / 7 AS ratio
        FROM range(0, 1000) r(i)""");
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

  private static long single(Connection connection, String sql) throws SQLException {
    List<List<Object>> rows = rows(connection, sql);
    assertEquals(1, rows.size(), sql);
    assertFalse(rows.get(0).isEmpty(), sql);
    return ((Number) rows.get(0).get(0)).longValue();
  }

  private Map<String, RequestStatistics.OperationStatistics> operations() {
    return localS3.statistics().operations();
  }

  private static long count(Map<String, RequestStatistics.OperationStatistics> operations, String operation) {
    RequestStatistics.OperationStatistics statistics = operations.get(operation);
    return statistics == null ? 0 : statistics.count();
  }

  /**
   * The headers of a response that describe its content: the ones that a client which caches metadata, e.g. DuckDB
   * with {@code enable_http_metadata_cache}, keeps and validates its later reads against.
   *
   * @param bodyLength the number of bytes that the response carried, which a {@code HEAD} answers none of.
   */
  private record Metadata(int status, String etag, long contentLength, String contentRange, String partsCount,
                          int bodyLength) {
  }

  /**
   * Read an object without an S3 client, so that the headers of the response are seen as LocalS3 sent them.
   *
   * @param method {@code HEAD} or {@code GET}.
   * @param key the key of the object.
   * @param query the query of the request, e.g. {@code "?partNumber=1"}; empty for none.
   * @param range the value of the {@code Range} header; {@code null} for none.
   */
  private Metadata metadata(String method, String key, String query, String range) throws Exception {
    HttpRequest.Builder builder = HttpRequest
        .newBuilder(URI.create("http://127.0.0.1:" + localS3.getPort() + "/" + BUCKET + "/" + key + query))
        .method(method, HttpRequest.BodyPublishers.noBody());
    if (range != null) {
      builder.header("Range", range);
    }
    HttpResponse<byte[]> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    assertTrue(response.statusCode() < 400, method + " " + key + query + " answered " + response.statusCode());
    return new Metadata(response.statusCode(),
        response.headers().firstValue("ETag").orElse(null),
        response.headers().firstValueAsLong("Content-Length").orElse(-1),
        response.headers().firstValue("Content-Range").orElse(null),
        response.headers().firstValue("x-amz-mp-parts-count").orElse(null),
        response.body().length);
  }

  /**
   * The last requests that LocalS3 answered, the most recent first, with their status.
   */
  private List<JsonNode> recentRequests() throws Exception {
    HttpResponse<String> response = httpClient.send(HttpRequest.newBuilder(
            URI.create("http://127.0.0.1:" + localS3.getPort() + "/_admin/requests?limit=100")).build(),
        HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode(), response.body());
    List<JsonNode> requests = new ArrayList<>();
    objectMapper.readTree(response.body()).get("requests").forEach(requests::add);
    return requests;
  }

  private static Path resource(String name) throws URISyntaxException {
    return Path.of(DuckDbParquetIntegrationTest.class.getResource("/" + name).toURI());
  }

  /**
   * A local path as a string literal of DuckDB, with forward slashes, which DuckDB accepts on Windows as well.
   */
  private static String sqlPath(Path path) {
    assertTrue(Files.exists(path.getParent()), path.toString());
    return path.toAbsolutePath().toString().replace('\\', '/').replace("'", "''");
  }

}
