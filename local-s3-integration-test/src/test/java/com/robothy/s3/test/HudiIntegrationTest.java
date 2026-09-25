package com.robothy.s3.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.rest.LocalS3;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.s3a.S3AFileSystem;
import org.apache.hudi.client.HoodieJavaWriteClient;
import org.apache.hudi.client.WriteStatus;
import org.apache.hudi.client.common.HoodieJavaEngineContext;
import org.apache.hudi.common.model.DefaultHoodieRecordPayload;
import org.apache.hudi.common.model.FileSlice;
import org.apache.hudi.common.model.HoodieAvroRecord;
import org.apache.hudi.common.model.HoodieBaseFile;
import org.apache.hudi.common.model.HoodieKey;
import org.apache.hudi.common.model.HoodieRecord;
import org.apache.hudi.common.model.HoodieTableType;
import org.apache.hudi.common.table.HoodieTableMetaClient;
import org.apache.hudi.common.table.timeline.HoodieTimeline;
import org.apache.hudi.common.table.view.HoodieTableFileSystemView;
import org.apache.hudi.common.util.Option;
import org.apache.hudi.config.HoodieCompactionConfig;
import org.apache.hudi.config.HoodieIndexConfig;
import org.apache.hudi.config.HoodieWriteConfig;
import org.apache.hudi.hadoop.fs.HadoopFSUtils;
import org.apache.hudi.index.HoodieIndex;
import org.apache.hudi.storage.StorageConfiguration;
import org.apache.parquet.avro.AvroParquetReader;
import org.apache.parquet.hadoop.ParquetReader;
import org.apache.parquet.hadoop.util.HadoopInputFile;
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
 * Apache Hudi on LocalS3, without Spark: the write client of its Java engine, on a table under {@code s3a://}, which
 * Hudi reaches through Hadoop's S3A, the way Spark and Flink jobs do:
 *
 * <pre>{@code
 * spark.hadoop.fs.s3a.endpoint=http://localhost:29090
 * spark.hadoop.fs.s3a.path.style.access=true
 * spark.hadoop.fs.s3a.access.key=admin
 * spark.hadoop.fs.s3a.secret.key=admin
 * }</pre>
 *
 * <p>A table is a {@code .hoodie/} directory of the timeline, the instants of every commit, and its metadata table,
 * beside one directory per partition of file groups. A copy-on-write table rewrites the Parquet base file of a file
 * group on every update; a merge-on-read one appends the updates to log files beside it, which a compaction merges
 * into a new base file.
 */
@Tag("data-tools")
@Timeout(value = 3, unit = TimeUnit.MINUTES)
class HudiIntegrationTest {

  private static final String BUCKET = "lake";

  private static final Schema SCHEMA = new Schema.Parser().parse("""
      {"type": "record", "name": "trip", "fields": [
        {"name": "id", "type": "string"},
        {"name": "city", "type": "string"},
        {"name": "fare", "type": "double"},
        {"name": "ts", "type": "long"}
      ]}""");

  private LocalS3 localS3;

  private S3Client s3;

  private final List<HoodieJavaWriteClient<?>> clients = new ArrayList<>();

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
  }

  @AfterEach
  void tearDown() throws IOException {
    clients.forEach(HoodieJavaWriteClient::close);
    // Hudi takes its file systems from the cache of Hadoop, where s3a://lake would keep the endpoint of this test.
    FileSystem.closeAll();
    if (s3 != null) {
      s3.close();
    }
    if (localS3 != null) {
      localS3.shutdown();
    }
  }

  /**
   * A copy-on-write table: rows inserted into two partitions, then upserted and deleted by key, each change a commit
   * of its own on the timeline, and every base file rewritten.
   */
  @Test
  void insertsUpsertsAndDeletesInACopyOnWriteTable() throws Exception {
    String basePath = "s3a://" + BUCKET + "/hudi/trips_cow";
    HoodieJavaWriteClient<DefaultHoodieRecordPayload> client = client(HoodieTableType.COPY_ON_WRITE, basePath);

    write(client, false, List.of(trip("t1", "beijing", 10, 1), trip("t2", "beijing", 20, 1),
        trip("t3", "shanghai", 30, 1)));
    write(client, true, List.of(trip("t2", "beijing", 25, 2), trip("t4", "shanghai", 40, 2)));
    String deleted = client.startCommit();
    // A list of its own: Hudi clears the one it is given.
    commit(client, deleted, client.delete(new ArrayList<>(List.of(new HoodieKey("t3", "shanghai"))), deleted));

    HoodieTableMetaClient metaClient = metaClient(basePath);
    assertEquals(3, metaClient.getActiveTimeline().getCommitsTimeline().filterCompletedInstants().countInstants());
    assertEquals(Map.of("t1", 10.0, "t2", 25.0, "t4", 40.0), readBaseFiles(metaClient));
    assertTrue(keys("hudi/trips_cow/.hoodie/hoodie.properties").size() == 1, "The table config is written.");
    assertTrue(keys("hudi/trips_cow/.hoodie/metadata/").size() > 0, "The metadata table is written as well.");
  }

  /**
   * A merge-on-read table: the updates of a second commit go to log files, which the base files, the read-optimized
   * view, don't see until a compaction merges them into new base files.
   */
  @Test
  void compactsTheLogFilesOfAMergeOnReadTable() throws Exception {
    String basePath = "s3a://" + BUCKET + "/hudi/trips_mor";
    HoodieJavaWriteClient<DefaultHoodieRecordPayload> client = client(HoodieTableType.MERGE_ON_READ, basePath);

    write(client, false, List.of(trip("t1", "beijing", 10, 1), trip("t2", "beijing", 20, 1),
        trip("t3", "shanghai", 30, 1)));
    write(client, true, List.of(trip("t1", "beijing", 11, 2), trip("t3", "shanghai", 33, 2)));

    HoodieTableMetaClient metaClient = metaClient(basePath);
    assertEquals(Map.of("t1", 10.0, "t2", 20.0, "t3", 30.0), readBaseFiles(metaClient),
        "The updates are in log files, which the base files don't have yet.");
    assertTrue(logFiles(metaClient) > 0, "The updates of the second commit are log files.");

    Option<String> compaction = client.scheduleCompaction(Option.empty());
    assertTrue(compaction.isPresent(), "Two delta commits are due for a compaction.");
    client.commitCompaction(compaction.get(), client.compact(compaction.get()), Option.empty());

    metaClient = metaClient(basePath);
    HoodieTimeline compactions = metaClient.getActiveTimeline().getCommitTimeline().filterCompletedInstants();
    assertTrue(compactions.containsInstant(compaction.get()), "The compaction is complete: " + compactions);
    assertEquals(0, logFiles(metaClient), "The latest file slices have no log files left.");
    assertEquals(Map.of("t1", 11.0, "t2", 20.0, "t3", 33.0), readBaseFiles(metaClient),
        "The compaction merged the updates into the base files.");
  }

  private static void write(HoodieJavaWriteClient<DefaultHoodieRecordPayload> client, boolean upsert,
                            List<GenericRecord> trips) {
    List<HoodieRecord<DefaultHoodieRecordPayload>> records = new ArrayList<>();
    for (GenericRecord trip : trips) {
      HoodieKey key = new HoodieKey(trip.get("id").toString(), trip.get("city").toString());
      records.add(new HoodieAvroRecord<>(key, new DefaultHoodieRecordPayload(trip, (Long) trip.get("ts"))));
    }
    String instant = client.startCommit();
    commit(client, instant, upsert ? client.upsert(records, instant) : client.insert(records, instant));
  }

  /**
   * Complete the instant of a write on the timeline, which Hudi 1.x leaves to the writer: an instant it doesn't
   * complete is rolled back by the next write.
   */
  private static void commit(HoodieJavaWriteClient<DefaultHoodieRecordPayload> client, String instant,
                             List<WriteStatus> statuses) {
    assertFalse(statuses.isEmpty());
    for (WriteStatus status : statuses) {
      assertFalse(status.hasErrors(), "Written with errors: " + status.getErrors());
    }
    assertTrue(client.commit(instant, statuses), "Committed " + instant);
  }

  /**
   * The rows of the latest base file of every file group, the read-optimized view: {@code id} to {@code fare}.
   */
  private Map<String, Double> readBaseFiles(HoodieTableMetaClient metaClient) throws IOException {
    Map<String, Double> rows = new TreeMap<>();
    try (HoodieTableFileSystemView view = fileSystemView(metaClient)) {
      for (String partition : List.of("beijing", "shanghai")) {
        for (HoodieBaseFile baseFile : view.getLatestBaseFiles(partition).toList()) {
          try (ParquetReader<GenericRecord> reader = AvroParquetReader.<GenericRecord>builder(
              HadoopInputFile.fromPath(new Path(baseFile.getPath()), hadoopConfiguration())).build()) {
            for (GenericRecord row = reader.read(); row != null; row = reader.read()) {
              rows.put(row.get("id").toString(), (Double) row.get("fare"));
            }
          }
        }
      }
    }
    return rows;
  }

  private long logFiles(HoodieTableMetaClient metaClient) {
    try (HoodieTableFileSystemView view = fileSystemView(metaClient)) {
      return List.of("beijing", "shanghai").stream()
          .flatMap(view::getLatestFileSlices)
          .mapToLong(slice -> slice.getLogFiles().count())
          .sum();
    }
  }

  private HoodieTableFileSystemView fileSystemView(HoodieTableMetaClient metaClient) {
    return HoodieTableFileSystemView.fileListingBasedFileSystemView(new HoodieJavaEngineContext(storageConf()),
        metaClient, metaClient.getActiveTimeline().filterCompletedAndCompactionInstants());
  }

  private HoodieJavaWriteClient<DefaultHoodieRecordPayload> client(HoodieTableType type, String basePath)
      throws IOException {
    String name = basePath.substring(basePath.lastIndexOf('/') + 1);
    HoodieTableMetaClient.newTableBuilder()
        .setTableType(type)
        .setTableName(name)
        .setRecordKeyFields("id")
        .setPartitionFields("city")
        .setOrderingFields("ts")
        .setPayloadClass(DefaultHoodieRecordPayload.class)
        .initTable(storageConf(), basePath);
    HoodieWriteConfig config = HoodieWriteConfig.newBuilder()
        .withPath(basePath)
        .withSchema(SCHEMA.toString())
        .forTable(name)
        .withParallelism(2, 2)
        .withDeleteParallelism(2)
        .withIndexConfig(HoodieIndexConfig.newBuilder().withIndexType(HoodieIndex.IndexType.SIMPLE).build())
        .withCompactionConfig(HoodieCompactionConfig.newBuilder()
            .withInlineCompaction(false)
            .withMaxNumDeltaCommitsBeforeCompaction(2)
            .build())
        .withEmbeddedTimelineServerEnabled(false)
        .build();
    HoodieJavaWriteClient<DefaultHoodieRecordPayload> client =
        new HoodieJavaWriteClient<>(new HoodieJavaEngineContext(storageConf()), config);
    clients.add(client);
    return client;
  }

  private HoodieTableMetaClient metaClient(String basePath) {
    return HoodieTableMetaClient.builder().setConf(storageConf()).setBasePath(basePath).build();
  }

  private static GenericRecord trip(String id, String city, double fare, long ts) {
    GenericRecord trip = new GenericData.Record(SCHEMA);
    trip.put("id", id);
    trip.put("city", city);
    trip.put("fare", fare);
    trip.put("ts", ts);
    return trip;
  }

  private List<String> keys(String prefix) {
    return s3.listObjectsV2Paginator(request -> request.bucket(BUCKET).prefix(prefix)).contents().stream()
        .map(S3Object::key)
        .toList();
  }

  private StorageConfiguration<Configuration> storageConf() {
    return HadoopFSUtils.getStorageConfWithCopy(hadoopConfiguration());
  }

  private Configuration hadoopConfiguration() {
    Configuration conf = new Configuration();
    conf.set("hadoop.tmp.dir", bufferDir.toString());
    conf.set("fs.s3a.impl", S3AFileSystem.class.getName());
    conf.set("fs.s3a.endpoint", localS3.endpoint());
    conf.set("fs.s3a.endpoint.region", "us-east-1");
    conf.setBoolean("fs.s3a.path.style.access", true);
    conf.setBoolean("fs.s3a.connection.ssl.enabled", false);
    conf.set("fs.s3a.aws.credentials.provider", "org.apache.hadoop.fs.s3a.SimpleAWSCredentialsProvider");
    conf.set("fs.s3a.access.key", "admin");
    conf.set("fs.s3a.secret.key", "admin");
    return conf;
  }

}
