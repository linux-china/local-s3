package com.robothy.s3.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.rest.LocalS3;
import com.robothy.s3.rest.admin.RequestStatistics;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.iceberg.BaseTable;
import org.apache.iceberg.CatalogUtil;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.PartitionKey;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableMetadataParser;
import org.apache.iceberg.TableOperations;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.aws.s3.S3FileIO;
import org.apache.iceberg.aws.s3.S3FileIOProperties;
import org.apache.iceberg.data.GenericRecord;
import org.apache.iceberg.data.IcebergGenerics;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.data.parquet.GenericParquetWriter;
import org.apache.iceberg.exceptions.CommitFailedException;
import org.apache.iceberg.expressions.Expressions;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.io.DataWriter;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.io.LocationProvider;
import org.apache.iceberg.parquet.Parquet;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Apache Iceberg tables on LocalS3 through the Iceberg Java API and its {@link S3FileIO}, the way an engine such as
 * Spark, Flink or Trino writes them:
 *
 * <pre>{@code
 * S3FileIO io = new S3FileIO();
 * io.initialize(Map.of(
 *     "s3.endpoint", "http://localhost:29090",
 *     "s3.path-style-access", "true",
 *     "s3.access-key-id", "admin",
 *     "s3.secret-access-key", "admin",
 *     "client.region", "us-east-1"));
 * }</pre>
 *
 * <p>Data files, manifests and manifest lists are written with {@link S3FileIO}: a {@code PutObject}, or a multipart
 * upload for a large file; read with range requests; and deleted with {@code DeleteObjects} and a prefix listing.
 *
 * <p>The table metadata is committed the way the S3 commit protocols of Iceberg REST catalogs and of Delta Lake
 * commit: each version is an object of its own, {@code metadata/v<N>.metadata.json}, created with
 * {@code If-None-Match: *}. Of the writers that commit a version at once, LocalS3 lets one create it and answers the
 * others {@code 412 Precondition Failed}, which {@link S3ConditionalTableOperations} turns into the
 * {@link CommitFailedException} that makes Iceberg refresh the table and retry the commit on the newer version. Without
 * the condition, the last writer would silently replace the commit of the others.
 */
@Tag("data-tools")
@Timeout(value = 3, unit = TimeUnit.MINUTES)
class IcebergS3FileIOIntegrationTest {

  private static final String BUCKET = "warehouse";

  private static final int MIB = 1024 * 1024;

  private static final Schema SCHEMA = new Schema(
      Types.NestedField.required(1, "id", Types.LongType.get()),
      Types.NestedField.required(2, "category", Types.StringType.get()),
      Types.NestedField.optional(3, "payload", Types.StringType.get()));

  private static final PartitionSpec SPEC = PartitionSpec.builderFor(SCHEMA).identity("category").build();

  private LocalS3 localS3;

  private S3Client s3;

  private final List<S3FileIO> fileIOs = new ArrayList<>();

  private final HttpClient httpClient = HttpClient.newHttpClient();

  @BeforeEach
  void setUp() {
    localS3 = LocalS3.builder().port(-1).buckets(BUCKET).build();
    localS3.start();
    s3 = S3Client.builder()
        .endpointOverride(URI.create(endpoint()))
        .region(Region.US_EAST_1)
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("any", "any")))
        .forcePathStyle(true)
        .build();
  }

  @AfterEach
  void tearDown() {
    fileIOs.forEach(S3FileIO::close);
    if (s3 != null) {
      s3.close();
    }
    if (localS3 != null) {
      localS3.shutdown();
    }
  }

  /**
   * A partitioned table: Parquet data files appended, read back with and without a filter, rows deleted, and an older
   * snapshot read again.
   */
  @Test
  void appendsReadsDeletesAndTimeTravelsAPartitionedTable() throws Exception {
    Table table = createTable("db/events", Map.of());

    List<DataFile> files = new ArrayList<>();
    for (String category : List.of("click", "view", "purchase")) {
      files.add(writeDataFile(table, category, 0, 100, 0));
    }
    var append = table.newAppend();
    files.forEach(append::appendFile);
    append.commit();
    long firstSnapshot = table.currentSnapshot().snapshotId();

    assertEquals(300, count(IcebergGenerics.read(table).build()));
    assertEquals(100, count(IcebergGenerics.read(table).where(Expressions.equal("category", "view")).build()));

    List<String> keys = keys("db/events/");
    assertTrue(keys.stream().anyMatch(key -> key.startsWith("db/events/data/category=click/")
        && key.endsWith(".parquet")), keys.toString());
    assertTrue(keys.stream().anyMatch(key -> key.startsWith("db/events/metadata/") && key.endsWith(".avro")),
        "Manifests and manifest lists are Avro files: " + keys);
    assertTrue(keys.containsAll(List.of("db/events/metadata/v1.metadata.json", "db/events/metadata/v2.metadata.json")),
        keys.toString());

    table.newDelete().deleteFromRowFilter(Expressions.equal("category", "click")).commit();

    assertEquals(200, count(IcebergGenerics.read(table).build()));
    assertEquals(300, count(IcebergGenerics.read(table).useSnapshot(firstSnapshot).build()));

    Table reloaded = loadTable("db/events");
    assertEquals(table.currentSnapshot().snapshotId(), reloaded.currentSnapshot().snapshotId());
    assertEquals(200, count(IcebergGenerics.read(reloaded).build()));
  }

  /**
   * A data file larger than the part size of {@link S3FileIO} is uploaded in parts, and a query that reads a few of
   * its row groups reads them with range requests rather than the whole file.
   */
  @Test
  void writesALargeFileInPartsAndReadsItsRowGroupsWithRangeRequests() throws Exception {
    Table table = createTable("db/large", Map.of(
        TableProperties.PARQUET_ROW_GROUP_SIZE_BYTES, String.valueOf(MIB),
        TableProperties.PARQUET_COMPRESSION, "uncompressed"));
    long before = count("CreateMultipartUpload");

    int rows = 200_000;
    DataFile file = writeDataFile(table, "bulk", 0, rows, 64);
    table.newAppend().appendFile(file).commit();

    assertTrue(file.fileSizeInBytes() > 12L * MIB, "The file has " + file.fileSizeInBytes() + " bytes.");
    assertEquals(1, count("CreateMultipartUpload") - before);
    assertEquals(1, count("CompleteMultipartUpload"));
    assertTrue(count("UploadPart") >= 2, "The file must be uploaded in several parts: " + operations());
    assertEquals(file.fileSizeInBytes(),
        s3.headObject(request -> request.bucket(BUCKET).key(key(file.location()))).contentLength());

    assertEquals(rows, count(IcebergGenerics.read(table).select("id").build()));
    assertEquals(11, count(IcebergGenerics.read(table)
        .where(Expressions.and(Expressions.greaterThanOrEqual("id", 150_000L), Expressions.lessThanOrEqual("id", 150_010L)))
        .build()));

    List<JsonNode> reads = recentRequests().stream()
        .filter(request -> "GetObject".equals(request.get("operation").asText()))
        // The = of the partition directory is URL encoded in the recorded URI.
        .filter(request -> URI.create(request.get("uri").asText()).getPath().equals("/" + BUCKET + "/" + key(file.location())))
        .toList();
    assertTrue(reads.size() >= 2, "The data file must be read in ranges: " + reads);
    assertTrue(reads.stream().allMatch(request -> request.get("status").asInt() == 206),
        "Every read of the data file must be a range request answered with 206 Partial Content: " + reads);
  }

  /**
   * Writers that append to the same table at once, each with a table of its own like separate processes, all commit:
   * every version of the metadata is created once, and the writers that lose a race retry on the newer version, so
   * that no commit is lost.
   */
  @Test
  void concurrentWritersCommitEachVersionOnceAndRetryOnConflicts() throws Exception {
    createTable("db/concurrent", Map.of(
        TableProperties.COMMIT_NUM_RETRIES, "100",
        TableProperties.COMMIT_MIN_RETRY_WAIT_MS, "5",
        TableProperties.COMMIT_MAX_RETRY_WAIT_MS, "100"));
    int writers = 8;
    int commitsPerWriter = 5;

    CyclicBarrier start = new CyclicBarrier(writers);
    ExecutorService executor = Executors.newFixedThreadPool(writers);
    List<S3ConditionalTableOperations> operations = new ArrayList<>();
    try {
      List<Future<?>> futures = new ArrayList<>();
      for (int writer = 0; writer < writers; writer++) {
        S3ConditionalTableOperations ops = new S3ConditionalTableOperations(s3, fileIO(Map.of()), "db/concurrent");
        operations.add(ops);
        Table table = new BaseTable(ops, "concurrent");
        String category = "writer-" + writer;
        futures.add(executor.submit(() -> {
          start.await();
          for (int commit = 0; commit < commitsPerWriter; commit++) {
            table.newAppend().appendFile(writeDataFile(table, category, commit * 10L, 10, 0)).commit();
          }
          return null;
        }));
      }
      for (Future<?> future : futures) {
        future.get();
      }
    } finally {
      executor.shutdownNow();
    }

    Table table = loadTable("db/concurrent");
    int commits = writers * commitsPerWriter;
    assertEquals(commits * 10, count(IcebergGenerics.read(table).build()));
    List<Snapshot> snapshots = new ArrayList<>();
    table.snapshots().forEach(snapshots::add);
    assertEquals(commits, snapshots.size());

    List<String> versions = keys("db/concurrent/metadata/v");
    assertEquals(commits + 1, versions.size(), versions.toString());
    for (int version = 1; version <= commits + 1; version++) {
      assertTrue(versions.contains("db/concurrent/metadata/v" + version + ".metadata.json"),
          "Version " + version + " is missing: " + versions);
    }
    // The writers start at once, on the same version, so all but one of them lose the race for the first commit.
    int conflicts = operations.stream().mapToInt(S3ConditionalTableOperations::conflicts).sum();
    assertTrue(conflicts > 0, "The writers must have raced for a version, and retried.");
  }

  /**
   * A writer whose view of the table is stale doesn't overwrite the version that another writer committed: LocalS3
   * refuses to create the metadata object again, and an append of the stale writer succeeds by retrying on the newer
   * version, with the rows of both writers in the table.
   */
  @Test
  void aStaleWriterDoesNotOverwriteTheCommitOfAnotherWriter() throws Exception {
    createTable("db/stale", Map.of());
    S3ConditionalTableOperations staleOps = new S3ConditionalTableOperations(s3, fileIO(Map.of()), "db/stale");
    Table stale = new BaseTable(staleOps, "stale");
    TableMetadata base = staleOps.current();
    Table fresh = loadTable("db/stale");

    fresh.newAppend().appendFile(writeDataFile(fresh, "fresh", 0, 10, 0)).commit();

    TableMetadata update = TableMetadata.buildFrom(base).setProperties(Map.of("owner", "stale")).build();
    CommitFailedException conflict = assertThrows(CommitFailedException.class, () -> staleOps.commit(base, update));
    assertTrue(conflict.getCause() instanceof S3Exception e && e.statusCode() == 412, String.valueOf(conflict.getCause()));
    assertEquals(1, staleOps.conflicts());
    assertFalse(loadTable("db/stale").properties().containsKey("owner"));

    // An append refreshes the table before it commits, and retries on a conflict.
    stale.newAppend().appendFile(writeDataFile(stale, "stale", 100, 5, 0)).commit();

    Table table = loadTable("db/stale");
    assertEquals(15, count(IcebergGenerics.read(table).build()));
    assertEquals(10, count(IcebergGenerics.read(table).where(Expressions.equal("category", "fresh")).build()));
  }

  /**
   * An overwrite followed by the expiration of the older snapshot deletes the files that only it referenced, in bulk,
   * and dropping the table deletes every object under its location, which {@link S3FileIO} finds with a listing.
   */
  @Test
  void expiresSnapshotsAndDropsTheTableData() throws Exception {
    Table table = createTable("db/expiring", Map.of());
    DataFile original = writeDataFile(table, "a", 0, 10, 0);
    table.newAppend().appendFile(original).commit();
    long originalSnapshot = table.currentSnapshot().snapshotId();
    DataFile replacement = writeDataFile(table, "a", 0, 20, 0);
    table.newOverwrite().deleteFile(original).addFile(replacement).commit();
    assertTrue(keys("db/expiring/").contains(key(original.location())));

    table.expireSnapshots().expireSnapshotId(originalSnapshot).commit();

    List<String> keys = keys("db/expiring/");
    assertFalse(keys.contains(key(original.location())), "The data file of the expired snapshot remains: " + keys);
    assertTrue(keys.contains(key(replacement.location())), keys.toString());
    assertTrue(count("DeleteObjects") >= 1, operations().keySet().toString());
    assertEquals(20, count(IcebergGenerics.read(loadTable("db/expiring")).build()));

    S3FileIO io = (S3FileIO) table.io();
    CatalogUtil.dropTableData(io, ((BaseTable) table).operations().current());
    io.deletePrefix(table.location());
    assertEquals(List.of(), keys("db/expiring/"));
  }

  private Table createTable(String path, Map<String, String> properties) {
    S3ConditionalTableOperations ops = new S3ConditionalTableOperations(s3, fileIO(Map.of()), path);
    ops.commit(null, TableMetadata.newTableMetadata(SCHEMA, SPEC, "s3://" + BUCKET + "/" + path, properties));
    return new BaseTable(ops, path);
  }

  private Table loadTable(String path) {
    return new BaseTable(new S3ConditionalTableOperations(s3, fileIO(Map.of()), path), path);
  }

  /**
   * An {@link S3FileIO} for LocalS3, configured with the catalog properties that an engine passes to it.
   */
  private S3FileIO fileIO(Map<String, String> overrides) {
    Map<String, String> properties = new HashMap<>(Map.of(
        S3FileIOProperties.ENDPOINT, endpoint(),
        S3FileIOProperties.PATH_STYLE_ACCESS, "true",
        S3FileIOProperties.ACCESS_KEY_ID, "any",
        S3FileIOProperties.SECRET_ACCESS_KEY, "any",
        "client.region", "us-east-1",
        // 5 MiB, the minimum part size of Amazon S3, instead of 32 MiB, so that a file of a test takes several parts.
        S3FileIOProperties.MULTIPART_SIZE, String.valueOf(5 * MIB)));
    properties.putAll(overrides);
    S3FileIO io = new S3FileIO();
    io.initialize(properties);
    fileIOs.add(io);
    return io;
  }

  /**
   * A Parquet data file of the partition {@code category}, with the ids {@code firstId} to
   * {@code firstId + rows - 1}, and payloads of {@code payloadLength} random characters.
   */
  private static DataFile writeDataFile(Table table, String category, long firstId, int rows, int payloadLength) {
    GenericRecord record = GenericRecord.create(table.schema());
    PartitionKey partition = new PartitionKey(table.spec(), table.schema());
    partition.partition(record.copy(Map.of("id", firstId, "category", category)));
    LocationProvider locations = table.locationProvider();
    String location = locations.newDataLocation(table.spec(), partition, UUID.randomUUID() + ".parquet");
    try {
      DataWriter<Record> writer = Parquet.writeData(table.io().newOutputFile(location))
          .forTable(table)
          .withSpec(table.spec())
          .withPartition(partition)
          .createWriterFunc(GenericParquetWriter::create)
          .overwrite()
          .build();
      try (writer) {
        StringBuilder payload = new StringBuilder();
        for (long id = firstId; id < firstId + rows; id++) {
          payload.setLength(0);
          while (payload.length() < payloadLength) {
            payload.append(Long.toHexString(Double.doubleToLongBits(Math.random())));
          }
          payload.setLength(payloadLength);
          writer.write(record.copy(Map.of("id", id, "category", category, "payload", payload.toString())));
        }
      }
      assertEquals(FileFormat.PARQUET, writer.toDataFile().format());
      return writer.toDataFile();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static long count(CloseableIterable<Record> records) throws IOException {
    try (records) {
      long count = 0;
      for (Record ignored : records) {
        count++;
      }
      return count;
    }
  }

  private List<String> keys(String prefix) {
    return s3.listObjectsV2Paginator(request -> request.bucket(BUCKET).prefix(prefix)).contents().stream()
        .map(S3Object::key).toList();
  }

  private Map<String, RequestStatistics.OperationStatistics> operations() {
    return localS3.statistics().operations();
  }

  private long count(String operation) {
    RequestStatistics.OperationStatistics statistics = operations().get(operation);
    return statistics == null ? 0 : statistics.count();
  }

  /**
   * The last requests that LocalS3 answered, the most recent first, with their status.
   */
  private List<JsonNode> recentRequests() throws Exception {
    HttpResponse<String> response = httpClient.send(
        HttpRequest.newBuilder(URI.create(endpoint() + "/_admin/requests?limit=100")).build(),
        HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode(), response.body());
    List<JsonNode> requests = new ArrayList<>();
    new ObjectMapper().readTree(response.body()).get("requests").forEach(requests::add);
    return requests;
  }

  private String endpoint() {
    return "http://127.0.0.1:" + localS3.getPort();
  }

  private static String key(String location) {
    return URI.create(location).getPath().substring(1);
  }

  /**
   * The table operations of a catalog that keeps the versions of the metadata of a table on S3 itself:
   * {@code <table>/metadata/v<N>.metadata.json}, the highest of which is the current version. A commit creates the
   * next version with {@code If-None-Match: *}, which fails if another writer created it first.
   */
  static final class S3ConditionalTableOperations implements TableOperations {

    private static final Pattern VERSION = Pattern.compile(".*/v(\\d+)\\.metadata\\.json");

    private final S3Client s3;

    private final FileIO io;

    private final String path;

    private final AtomicInteger conflicts = new AtomicInteger();

    private TableMetadata current;

    private int version;

    private boolean loaded;

    S3ConditionalTableOperations(S3Client s3, FileIO io, String path) {
      this.s3 = s3;
      this.io = io;
      this.path = path;
    }

    @Override
    public synchronized TableMetadata current() {
      return loaded ? current : refresh();
    }

    @Override
    public synchronized TableMetadata refresh() {
      int latest = s3.listObjectsV2Paginator(request -> request.bucket(BUCKET).prefix(path + "/metadata/v"))
          .contents().stream()
          .map(object -> VERSION.matcher(object.key()))
          .filter(Matcher::matches)
          .mapToInt(matcher -> Integer.parseInt(matcher.group(1)))
          .max()
          .orElse(0);
      if (latest != version || !loaded) {
        current = latest == 0 ? null : TableMetadataParser.read(io, metadataLocation(latest));
        version = latest;
      }
      loaded = true;
      return current;
    }

    @Override
    public synchronized void commit(TableMetadata base, TableMetadata metadata) {
      if (base != current()) {
        throw new CommitFailedException("Cannot commit: the table metadata that the change is based on is stale");
      }
      if (base == metadata) {
        return;
      }
      int next = version + 1;
      String json = TableMetadataParser.toJson(metadata);
      try {
        s3.putObject(request -> request.bucket(BUCKET).key(metadataKey(next)).ifNoneMatch("*").contentType("application/json"),
            RequestBody.fromString(json));
      } catch (S3Exception e) {
        if (e.statusCode() == 412 || e.statusCode() == 409) {
          conflicts.incrementAndGet();
          throw new CommitFailedException(e, "Version %d of %s was committed by another writer", next, path);
        }
        throw e;
      }
      refresh();
    }

    @Override
    public FileIO io() {
      return io;
    }

    @Override
    public String metadataFileLocation(String fileName) {
      return "s3://" + BUCKET + "/" + path + "/metadata/" + fileName;
    }

    @Override
    public LocationProvider locationProvider() {
      return org.apache.iceberg.LocationProviders.locationsFor("s3://" + BUCKET + "/" + path, current().properties());
    }

    int conflicts() {
      return conflicts.get();
    }

    private String metadataKey(int version) {
      return path + "/metadata/v" + version + ".metadata.json";
    }

    private String metadataLocation(int version) {
      return "s3://" + BUCKET + "/" + metadataKey(version);
    }
  }

}
