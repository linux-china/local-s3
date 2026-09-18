package com.robothy.s3.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.rest.LocalS3;
import com.robothy.s3.test.delta.DeltaRows;
import com.robothy.s3.test.delta.LocalS3DeltaFileIO;
import io.delta.kernel.DataWriteContext;
import io.delta.kernel.Operation;
import io.delta.kernel.Scan;
import io.delta.kernel.Snapshot;
import io.delta.kernel.Table;
import io.delta.kernel.Transaction;
import io.delta.kernel.TransactionCommitResult;
import io.delta.kernel.data.ColumnarBatch;
import io.delta.kernel.data.FilteredColumnarBatch;
import io.delta.kernel.data.Row;
import io.delta.kernel.defaults.engine.DefaultEngine;
import io.delta.kernel.engine.Engine;
import io.delta.kernel.engine.FileReadResult;
import io.delta.kernel.exceptions.KernelException;
import io.delta.kernel.exceptions.TableNotFoundException;
import io.delta.kernel.internal.InternalScanFileUtils;
import io.delta.kernel.internal.data.ScanStateRow;
import io.delta.kernel.types.LongType;
import io.delta.kernel.types.StringType;
import io.delta.kernel.types.StructType;
import io.delta.kernel.utils.CloseableIterable;
import io.delta.kernel.utils.CloseableIterator;
import io.delta.kernel.utils.DataFileStatus;
import io.delta.kernel.utils.FileStatus;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.FileAlreadyExistsException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
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

/**
 * Delta Lake tables on LocalS3, driven by
 * <a href="https://delta.io/blog/delta-kernel/">delta-kernel-java</a> — the Delta client without Spark, and the same
 * code that an engine uses to read and write a Delta table.
 *
 * <p>The README says LocalS3 is a local S3 for a platform such as Delta Lake, and
 * {@code docs/data-tools.md} explains that the commit protocol of Delta rests on a conditional write. This test is
 * what backs that up: it creates a table, writes to it, races two writers for the same version and travels back to an
 * earlier one, all through the real Delta client.
 *
 * <p><b>What makes a Delta table work on object storage is one thing:</b> a version becomes visible by
 * <em>creating</em> {@code _delta_log/&lt;version&gt;.json}, and that create must fail if another writer got there
 * first. On S3 that is {@code PUT} with {@code If-None-Match: *}, answered {@code 412 PreconditionFailed} for the key
 * that is taken. {@link LocalS3DeltaFileIO} is where those two meet, and
 * {@link #a_second_writer_of_the_same_version_loses_the_commit()} is where it is proven: without a correct conditional
 * write, both writers would commit and one writer's rows would silently vanish.
 */
@Tag("data-tools")
class DeltaLakeIntegrationTest {

  private static final String BUCKET = "delta";

  private static final StructType SCHEMA = new StructType()
      .add("id", LongType.LONG)
      .add("name", StringType.STRING);

  private LocalS3 localS3;

  private S3Client s3;

  private Engine engine;

  private String tablePath;

  @BeforeEach
  void startLocalS3() {
    localS3 = LocalS3.builder()
        .port(-1)
        .credentials("delta-key", "delta-secret")
        .registerShutdownHook(false)
        .build();
    localS3.start();

    s3 = S3Client.builder()
        .region(Region.US_EAST_1)
        .endpointOverride(URI.create("http://127.0.0.1:" + localS3.getPort()))
        .credentialsProvider(StaticCredentialsProvider.create(
            AwsBasicCredentials.create("delta-key", "delta-secret")))
        .forcePathStyle(true)
        .build();
    s3.createBucket(request -> request.bucket(BUCKET));

    engine = DefaultEngine.create(new LocalS3DeltaFileIO(s3, BUCKET));
    tablePath = "s3://" + BUCKET + "/tables/events";
  }

  @AfterEach
  void stopLocalS3() {
    if (s3 != null) {
      s3.close();
    }
    if (localS3 != null) {
      localS3.shutdown();
    }
  }

  @Test
  @Timeout(120)
  void creating_a_table_writes_its_first_commit_to_the_delta_log() {
    Table table = Table.forPath(engine, tablePath);
    assertThrows(TableNotFoundException.class, () -> table.getLatestSnapshot(engine),
        "Nothing exists before the table is created.");

    long version = createTable();

    assertEquals(0, version, "The create is version 0 of the table.");
    Snapshot snapshot = table.getLatestSnapshot(engine);
    assertEquals(0, snapshot.getVersion());
    assertEquals(SCHEMA.toString(), snapshot.getSchema().toString());

    // The commit is an object of the bucket, named after its version, which is what a Delta log is.
    List<String> log = logFiles();
    assertEquals(List.of("tables/events/_delta_log/00000000000000000000.json"), log);
  }

  @Test
  @Timeout(180)
  void rows_written_are_read_back_through_delta() throws IOException {
    createTable();

    assertEquals(1, append(rows(row(1L, "one"), row(2L, "two"))),
        "The first write is version 1.");

    Table table = Table.forPath(engine, tablePath);
    List<Object[]> rows = read(table.getLatestSnapshot(engine));
    assertEquals(2, rows.size());
    assertEquals(List.of(1L, 2L), ids(rows));
    assertEquals("one", rows.get(0)[1]);

    // A second write appends rather than replaces, and adds a version.
    assertEquals(2, append(rows(row(3L, "three"))));
    assertEquals(List.of(1L, 2L, 3L), ids(read(table.getLatestSnapshot(engine))));
    assertEquals(2, table.getLatestSnapshot(engine).getVersion());

    // The data lives beside the log, as Parquet files under the table.
    assertTrue(objects("tables/events/").stream().anyMatch(key -> key.endsWith(".parquet")),
        "The rows should have been written as Parquet data files.");
    assertEquals(3, logFiles().size(), "Each commit adds one file to the log.");
  }

  /**
   * Two writers that both read the table at the same version build the same next version, and only one of them may
   * create it. This is the whole reason Delta needs a conditional write, and the reason this test exists: if LocalS3
   * accepted both {@code PUT}s, the second commit would overwrite the first and the rows of one writer would be gone
   * from a table that reported success.
   *
   * <p>Retries are switched off here, so the loser is reported rather than quietly resolved — and the failure is
   * followed down to its cause, which must be the {@code 412} that LocalS3 answered the second {@code PUT} with.
   */
  @Test
  @Timeout(180)
  void a_second_writer_of_the_same_version_loses_the_commit() throws IOException {
    createTable();
    Table table = Table.forPath(engine, tablePath);

    // Both transactions are built before either commits, so both target version 1.
    Transaction first = table.createTransactionBuilder(engine, "writer-one", Operation.WRITE)
        .withMaxRetries(0).build(engine);
    Transaction second = table.createTransactionBuilder(engine, "writer-two", Operation.WRITE)
        .withMaxRetries(0).build(engine);
    assertEquals(first.getReadTableVersion(), second.getReadTableVersion(),
        "Both writers start from the same version of the table.");

    assertEquals(1, commit(first, rows(row(1L, "from-one"))));

    KernelException refused = assertThrows(KernelException.class,
        () -> commit(second, rows(row(2L, "from-two"))),
        "The writer that did not create _delta_log/…1.json must be told its commit was lost.");
    assertTrue(causes(refused).anyMatch(cause -> cause instanceof FileAlreadyExistsException),
        "The commit must have failed because the log file of version 1 already existed, not for another reason: "
            + refused);
    assertTrue(causes(refused).anyMatch(cause -> cause.getMessage() != null
            && cause.getMessage().contains("Concurrent write detected")),
        "Delta should have read the refused create as a concurrent write: " + refused);

    // The winner's rows are the table, and the loser changed nothing.
    List<Object[]> committed = read(table.getLatestSnapshot(engine));
    assertEquals(1, committed.size());
    assertEquals("from-one", committed.get(0)[1]);
    assertEquals(1, table.getLatestSnapshot(engine).getVersion());
    assertEquals(2, logFiles().size(), "The lost commit must not have added a version to the log.");
  }

  /**
   * The same race, with the retries that Delta does by default: the writer that lost rebases onto the version the
   * winner wrote and commits again, so both writers' rows end up in the table. This is the property that matters to
   * anyone actually writing a Delta table — a conflict costs a retry, never a row.
   */
  @Test
  @Timeout(180)
  void a_writer_that_lost_the_race_retries_and_keeps_its_rows() throws IOException {
    createTable();
    Table table = Table.forPath(engine, tablePath);

    Transaction first = table.createTransactionBuilder(engine, "writer-one", Operation.WRITE).build(engine);
    Transaction second = table.createTransactionBuilder(engine, "writer-two", Operation.WRITE).build(engine);

    assertEquals(1, commit(first, rows(row(1L, "from-one"))));
    // The second commit is refused once, retried against version 1, and lands as version 2.
    assertEquals(2, commit(second, rows(row(2L, "from-two"))));

    assertEquals(List.of(1L, 2L), ids(read(table.getLatestSnapshot(engine))),
        "A commit that lost the race must not lose the rows it was carrying.");
    assertEquals(3, logFiles().size());
  }

  /**
   * The commit of a version is a create, not a write: the object of a version that exists may not be created again.
   * This asserts the conditional write of LocalS3 itself, under the Delta client that relies on it.
   */
  @Test
  @Timeout(120)
  void the_commit_of_a_version_is_a_conditional_create() {
    createTable();
    String version0 = "tables/events/_delta_log/00000000000000000000.json";

    S3Exception refused = assertThrows(S3Exception.class, () -> s3.putObject(
        request -> request.bucket(BUCKET).key(version0).ifNoneMatch("*"),
        RequestBody.fromString("{\"commitInfo\":{}}")));
    assertEquals(412, refused.statusCode(),
        "Creating the log file of a version that exists must fail the way Delta's commit relies on.");

    // The version that comes next is free, so creating it is accepted.
    String version1 = "tables/events/_delta_log/00000000000000000001.json";
    assertNotNull(s3.putObject(request -> request.bucket(BUCKET).key(version1).ifNoneMatch("*"),
        RequestBody.fromString("{\"commitInfo\":{}}")).eTag());
    assertEquals(412, assertThrows(S3Exception.class, () -> s3.putObject(
        request -> request.bucket(BUCKET).key(version1).ifNoneMatch("*"),
        RequestBody.fromString("{\"commitInfo\":{}}"))).statusCode(),
        "And only once: the second writer of the same version is refused.");
  }

  @Test
  @Timeout(180)
  void an_earlier_version_is_still_readable_by_time_travel() throws IOException {
    createTable();
    append(rows(row(1L, "first")));
    append(rows(row(2L, "second")));

    Table table = Table.forPath(engine, tablePath);
    assertEquals(2, table.getLatestSnapshot(engine).getVersion());
    assertEquals(List.of(1L, 2L), ids(read(table.getLatestSnapshot(engine))));

    // Every version of the table stays readable: the log is append-only and the data files are never rewritten.
    assertEquals(List.of(1L), ids(read(table.getSnapshotAsOfVersion(engine, 1))));
    assertEquals(List.of(), ids(read(table.getSnapshotAsOfVersion(engine, 0))),
        "Version 0 created the table and holds no rows.");

    assertThrows(RuntimeException.class, () -> table.getSnapshotAsOfVersion(engine, 3),
        "A version the table never had cannot be read.");
  }

  @Test
  @Timeout(180)
  void a_table_is_read_by_a_client_that_did_not_write_it() throws IOException {
    createTable();
    append(rows(row(7L, "seven")));

    // A second engine over the same bucket, i.e. a reader that shares nothing with the writer but the object store.
    Engine reader = DefaultEngine.create(new LocalS3DeltaFileIO(s3, BUCKET));
    Snapshot snapshot = Table.forPath(reader, tablePath).getLatestSnapshot(reader);

    assertEquals(1, snapshot.getVersion());
    List<Object[]> rows = read(snapshot, reader);
    assertEquals(1, rows.size());
    assertEquals(7L, rows.get(0)[0]);
    assertEquals("seven", rows.get(0)[1]);
    assertTrue(snapshot.getPartitionColumnNames().isEmpty());
  }

  /*
   * Driving Delta: create, append and read, the way an engine does it.
   */

  private long createTable() {
    Transaction transaction = Table.forPath(engine, tablePath)
        .createTransactionBuilder(engine, "local-s3-test", Operation.CREATE_TABLE)
        .withSchema(engine, SCHEMA)
        .build(engine);
    return transaction.commit(engine, CloseableIterable.emptyIterable()).getVersion();
  }

  private long append(List<Object[]> rows) {
    Transaction transaction = Table.forPath(engine, tablePath)
        .createTransactionBuilder(engine, "local-s3-test", Operation.WRITE)
        .build(engine);
    return commit(transaction, rows);
  }

  /**
   * Write the rows as Parquet data files and commit the actions that add them, which is the write path of Delta: the
   * data is written first and becomes part of the table only when the commit succeeds.
   */
  private long commit(Transaction transaction, List<Object[]> rows) {
    Row transactionState = transaction.getTransactionState(engine);
    CloseableIterator<FilteredColumnarBatch> data = singleton(
        new FilteredColumnarBatch(DeltaRows.batch(SCHEMA, rows), Optional.empty()));
    CloseableIterator<FilteredColumnarBatch> physicalData =
        Transaction.transformLogicalData(engine, transactionState, data, Map.of());
    DataWriteContext writeContext = Transaction.getWriteContext(engine, transactionState, Map.of());

    try {
      CloseableIterator<DataFileStatus> dataFiles = engine.getParquetHandler().writeParquetFiles(
          writeContext.getTargetDirectory(), physicalData, writeContext.getStatisticsColumns());
      CloseableIterator<Row> actions =
          Transaction.generateAppendActions(engine, transactionState, dataFiles, writeContext);
      try (CloseableIterable<Row> committed = CloseableIterable.inMemoryIterable(actions)) {
        TransactionCommitResult result = transaction.commit(engine, committed);
        return result.getVersion();
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private List<Object[]> read(Snapshot snapshot) throws IOException {
    return read(snapshot, engine);
  }

  /**
   * Read every row of a snapshot: the scan names the data files that belong to that version, and each of them is read
   * and then transformed back into the logical schema of the table.
   */
  private List<Object[]> read(Snapshot snapshot, Engine readEngine) throws IOException {
    Scan scan = snapshot.getScanBuilder().build();
    Row scanState = scan.getScanState(readEngine);
    StructType physicalReadSchema = ScanStateRow.getPhysicalDataReadSchema(scanState);
    List<Object[]> rows = new ArrayList<>();

    try (CloseableIterator<FilteredColumnarBatch> scanFiles = scan.getScanFiles(readEngine)) {
      while (scanFiles.hasNext()) {
        try (CloseableIterator<Row> scanFileRows = scanFiles.next().getRows()) {
          while (scanFileRows.hasNext()) {
            Row scanFileRow = scanFileRows.next();
            FileStatus dataFile = InternalScanFileUtils.getAddFileStatus(scanFileRow);
            CloseableIterator<ColumnarBatch> physical = readEngine.getParquetHandler()
                .readParquetFiles(singleton(dataFile), physicalReadSchema, Optional.empty())
                .map(FileReadResult::getData);
            try (CloseableIterator<FilteredColumnarBatch> logical =
                     Scan.transformPhysicalData(readEngine, scanState, scanFileRow, physical)) {
              while (logical.hasNext()) {
                try (CloseableIterator<Row> dataRows = logical.next().getRows()) {
                  while (dataRows.hasNext()) {
                    Row row = dataRows.next();
                    rows.add(new Object[] {row.getLong(0), row.isNullAt(1) ? null : row.getString(1)});
                  }
                }
              }
            }
          }
        }
      }
    }
    rows.sort(Comparator.comparingLong(row -> (Long) row[0]));
    return rows;
  }

  /**
   * One row of the table. The values are boxed into an array rather than a type of their own: what a test asserts
   * about is the round trip through Delta, not the shape of a row.
   */
  private static Object[] row(long id, String name) {
    return new Object[] {id, name};
  }

  /**
   * The rows of a write. Declared as {@code Object[]...} rather than built with {@code List.of}, which would read a
   * single row as its own varargs array and infer a {@code List<Object>}.
   */
  private static List<Object[]> rows(Object[]... values) {
    return List.of(values);
  }

  /**
   * A failure and everything that caused it, so that a test can assert what a commit actually failed on rather than
   * on the wrapper Delta reports it as.
   */
  private static java.util.stream.Stream<Throwable> causes(Throwable failure) {
    List<Throwable> chain = new ArrayList<>();
    for (Throwable cause = failure; cause != null && !chain.contains(cause); cause = cause.getCause()) {
      chain.add(cause);
    }
    return chain.stream();
  }

  private static List<Long> ids(List<Object[]> rows) {
    return rows.stream().map(row -> (Long) row[0]).sorted().toList();
  }

  /*
   * The bucket, read directly, to assert what Delta actually left in it.
   */

  private List<String> logFiles() {
    return objects("tables/events/_delta_log/").stream().filter(key -> key.endsWith(".json")).sorted().toList();
  }

  private List<String> objects(String prefix) {
    return s3.listObjectsV2(request -> request.bucket(BUCKET).prefix(prefix))
        .contents().stream().map(S3Object::key).toList();
  }

  private static <T> CloseableIterator<T> singleton(T value) {
    return new CloseableIterator<>() {

      private boolean consumed;

      @Override
      public boolean hasNext() {
        return !consumed;
      }

      @Override
      public T next() {
        if (consumed) {
          throw new NoSuchElementException();
        }
        consumed = true;
        return value;
      }

      @Override
      public void close() {
      }
    };
  }

}
