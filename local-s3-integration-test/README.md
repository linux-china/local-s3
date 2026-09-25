Local S3 integration test
==========================

End-to-end tests of LocalS3 with real clients: the AWS SDK for Java v2, DuckDB, DuckLake, Apache Iceberg, Delta Lake,
Apache Paimon and Apache Hudi.

The tests are grouped by JUnit tag:

| Task            | Tag          | Tests                                                                                    |
|-----------------|--------------|------------------------------------------------------------------------------------------|
| `test`          | untagged     | LocalS3 with the AWS SDK; CI runs them on Linux for every pull request                   |
| `dataToolsTest` | `data-tools` | DuckDB, Apache Iceberg, Delta Lake, Paimon, Hudi and Hadoop S3A, the slow ones; CI runs them in a job of their own, in parallel |
| `realS3Test`    | `real-s3`    | the tests of `@RealS3`, which need AWS credentials; CI doesn't run them                   |

`check` runs `test` and `dataToolsTest`.

```shell
./gradlew :local-s3-integration-test:test
./gradlew :local-s3-integration-test:dataToolsTest
./gradlew :local-s3-integration-test:dataToolsTest --tests '*DuckDbParquetIntegrationTest'
./gradlew :local-s3-integration-test:dataToolsTest --tests '*DuckLakeIntegrationTest'
./gradlew :local-s3-integration-test:dataToolsTest --tests '*DuckDbIcebergIntegrationTest'
./gradlew :local-s3-integration-test:dataToolsTest --tests '*HadoopS3AIntegrationTest'
./gradlew :local-s3-integration-test:dataToolsTest --tests '*DuckDbDeltaIntegrationTest'
./gradlew :local-s3-integration-test:dataToolsTest --tests '*IcebergS3FileIOIntegrationTest'
./gradlew :local-s3-integration-test:dataToolsTest --tests '*IcebergRestCatalog*'
./gradlew :local-s3-integration-test:dataToolsTest --tests '*DeltaLakeIntegrationTest'
./gradlew :local-s3-integration-test:dataToolsTest --tests '*PaimonIntegrationTest'
./gradlew :local-s3-integration-test:dataToolsTest --tests '*HudiIntegrationTest'
./gradlew :local-s3-integration-test:test --tests '*ConcurrentRangeReadIntegrationTest'
```

# DuckDB

`DuckDbParquetIntegrationTest` starts a LocalS3 per test and connects DuckDB to it through the DuckDB JDBC driver and
its `httpfs` extension, with the secret of the README:

```sql
CREATE
SECRET (TYPE s3, ENDPOINT 'localhost:29090', URL_STYLE 'path', USE_SSL false, KEY_ID 'admin', SECRET 'admin');
```

| Test                                                          | DuckDB                                                                                                           | S3 features checked on the LocalS3 side                                                                                              |
|---------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------|
| `copiesACsvFileToAParquetFileAndReadsItBack`                  | `COPY (SELECT * FROM read_csv('family.csv')) TO 's3://lake/family.parquet'`                                      | `PutObject`; the object is a valid Parquet file for the AWS SDK too                                                                  |
| `writesALargeFileInPartsAndReadsItWithRangeRequests`          | `COPY` of a ~30 MiB file with 5 MiB parts, `read_parquet` with a filter                                          | `CreateMultipartUpload` / `UploadPart` / `CompleteMultipartUpload`, composite ETag, every `GetObject` answered `206 Partial Content` |
| `writesPartitionsAndReadsThemBackWithAGlobAcrossListingPages` | `COPY ... (PARTITION_BY (part))` into 1100 partitions, `read_parquet('s3://lake/events/**/*.parquet')`           | `ListObjectsV2` pagination with `encoding-type=url` over keys like `part=7/`                                                         |
| `aGlobReadsTheObjectsUnderItsPrefixOnly`                      | `sales/**`, `sales/year=2025/*/*`, `sales/*/month=1*/*`, `sales*/**`                                             | glob prefix listing; siblings `sales_archive/` and `sales.parquet` aren't read by `sales/**`                                         |
| `roundTripsTheTypesOfDuckDbWithEachCompression`               | decimal, timestamp(tz), date, list, struct, map, blob, uuid, NULLs, Unicode; uncompressed / snappy / gzip / zstd | the file reads back row for row (`EXCEPT ALL`)                                                                                       |
| `exchangesParquetFilesWithOtherS3Clients`                     | Parquet files written by DuckDB and uploaded by the AWS SDK                                                      | the bytes are intact in both directions                                                                                              |
| `overwritesThePartitionsOfADirectory`                         | `OVERWRITE_OR_IGNORE`, `OVERWRITE`                                                                               | listing and deletion of the old partitions                                                                                           |
| `signsItsRequestsForALocalS3ThatRequiresCredentials`          | `COPY` / `read_parquet` with credentials; keys with `=`, spaces, `&`, `+` and Unicode                            | SigV4 verification; a wrong secret gets `403`                                                                                        |
| `answersTheConcurrentRangeRequestsOfConcurrentQueries` | 8 connections query a file of 40 row groups at once, `SET threads = 8`, without the file caches of DuckDB | hundreds of concurrent `GetObject` range requests, all `206`, no errors, more than one in flight at once |
| `connectsWithTheS3SettingsOfDuckDbInsteadOfASecret` | `SET s3_endpoint`, `s3_url_style = 'path'`, `s3_use_ssl = false`, ... instead of `CREATE SECRET` | path-style requests, `/lake/settings.parquet` |

Notes:

+ The `httpfs` extension is loaded from `~/.duckdb/extensions`, or installed from the extension repository of DuckDB
  when it isn't there. Without network access and without an installed extension, the DuckDB tests are skipped.
+ DuckDB uploads a file with one `PutObject` if it fits in a single part, whose size is
  `s3_uploader_max_filesize / s3_uploader_max_parts_per_file` (800GB / 10000 = 80 MB by default); the multipart test
  sets `s3_uploader_max_filesize = '50GB'` for 5 MiB parts.
+ Every DuckDB test has a timeout: a listing whose continuation tokens don't advance makes DuckDB list forever, or
  read only the first page. These tests found such a bug, fixed with them: the continuation token of a URL encoded
  `ListObjectsV2` was computed from the encoded key, which sorts before the key itself (`%3D` < `=`).

The same connection with the S3 settings of `httpfs` instead of a secret:

```sql
SET s3_endpoint = 'localhost:29090';
SET s3_url_style = 'path';
SET s3_use_ssl = false;
SET s3_region = 'us-east-1';
SET s3_access_key_id = 'admin';
SET s3_secret_access_key = 'admin';
```

Manual steps with the DuckDB CLI against a running LocalS3:

```sql
CREATE
SECRET (TYPE s3, ENDPOINT 'localhost:29090', URL_STYLE 'path', USE_SSL false, KEY_ID 'admin', SECRET 'admin');
COPY
(
SELECT *
FROM 'local-s3-integration-test/src/test/resources/family.csv')
    TO 's3://demo1/family.parquet'
    (FORMAT parquet);
SELECT *
FROM read_parquet('s3://demo1/family.parquet');
```

# DuckLake

`DuckLakeIntegrationTest` attaches a DuckLake whose catalog is a DuckDB file in a temporary directory and whose data
files are on LocalS3, with `DATA_INLINING_ROW_LIMIT 0` so that every change is a Parquet object:

| Test                                                  | DuckLake                                                                                                                      | S3 features checked on the LocalS3 side                                                                                            |
|-------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------|
| `readsTheSnapshotsOfATableWithTimeTravel`             | `CREATE TABLE`, two `INSERT`s, `UPDATE`; `AT (VERSION => n)` for snapshots 1, 3 and 4                                         | old data and delete files stay readable; no deletes                                                                                |
| `cleanupDeletesTheFilesOfExpiredSnapshotsFromLocalS3` | `ducklake_rewrite_data_files`, `ducklake_merge_adjacent_files`, `ducklake_expire_snapshots`, `ducklake_cleanup_old_files`     | expiry deletes nothing; cleanup deletes exactly the scheduled files with `DeleteObjects`, and leaves the files of the current snapshot |
| `encryptedDataFilesAreNotReadableAsPlainParquet`      | `ENCRYPTED` and a plain DuckLake side by side                                                                                 | an encrypted object has the catalog's size and the `PARE` magic, and `read_parquet` rejects it; a plain one reads as Parquet        |

The `ducklake` extension is loaded or installed like `httpfs`; without it, the tests are skipped.

# DuckDB on the built-in Iceberg REST catalog

`DuckDbIcebergIntegrationTest` attaches the catalog that LocalS3 serves at `/iceberg/v1` as a DuckDB database, with
the `iceberg` extension and **no S3 secret at all**: the endpoint, the path-style addressing and the credentials reach
DuckDB through the credential vending of the REST protocol. LocalS3 runs with a key pair, so every S3 request is
signature-verified and the vended credentials have to be the right ones.

```sql
ATTACH 'warehouse' AS ice (TYPE ICEBERG, ENDPOINT 'http://127.0.0.1:<port>/iceberg', AUTHORIZATION_TYPE 'none');
```

| Test                                                          | DuckDB                                                                  | What it proves                                                                          |
|---------------------------------------------------------------|-------------------------------------------------------------------------|-------------------------------------------------------------------------------------------|
| `a_table_created_by_duckdb_is_a_table_of_the_catalog`         | `CREATE SCHEMA`, `CREATE TABLE`, `INSERT`                               | the Iceberg Java client lists the table, reads its schema and its rows; the metadata, manifest and Parquet files are objects of the warehouse bucket |
| `the_catalog_vends_the_storage_credentials_to_duckdb`         | a write and a read without a `CREATE SECRET`                            | DuckDB's own S3 secret, with `provider = 'iceberg'`, carries the endpoint, `url_style=path`, `use_ssl=false` and the key of the service, scoped to the table |
| `rows_written_by_the_iceberg_client_are_read_by_duckdb`       | `SHOW ALL TABLES`, a count and a filtered read                          | Parquet appends of the Iceberg Java client are read by DuckDB, across two snapshots       |
| `rows_written_by_duckdb_are_read_by_the_iceberg_client`       | `CREATE TABLE ... AS SELECT` of 1000 rows                               | the commit DuckDB made is an `append` snapshot with `added-records`, and the rows read back through `IcebergGenerics` |
| `duckdb_reads_an_earlier_snapshot_of_a_table`                 | `iceberg_snapshots`, `AT (VERSION => <snapshot id>)`                    | the snapshot ids DuckDB lists are the history the Iceberg client sees, and the earlier state is addressable |
| `rows_deleted_and_updated_by_duckdb_are_seen_by_the_iceberg_client` | `DELETE`, `UPDATE`                                                 | the delete files and commits DuckDB writes are ones a second implementation reads the same way |
| `a_column_added_by_duckdb_is_a_schema_update_of_the_table`    | `ALTER TABLE ... ADD COLUMN`, then an `UPDATE` of it                     | the catalog hands out the next field id, and the column is optional, as a column added to a written table has to be |
| `iceberg_scan_reads_a_table_from_the_bucket_without_the_catalog` | `iceberg_scan` of a metadata file, with an S3 secret of its own       | a table is readable by location, without the catalog                                      |

# DuckDB on Delta Lake

`DuckDbDeltaIntegrationTest` writes a table with delta-kernel-java, as `DeltaLakeIntegrationTest` does, and reads it
back with the `delta` extension of DuckDB — a client built on delta-kernel-rs that reaches LocalS3 with DuckDB's own
HTTP client rather than with the AWS SDK. The extension is read-only, which is also the shape of the scenario: an
application writes the table, the IDE queries it.

| Test                                                          | DuckDB                                                       | What it proves                                                                     |
|---------------------------------------------------------------|---------------------------------------------------------------|--------------------------------------------------------------------------------------|
| `delta_scan_reads_a_table_written_by_delta_kernel`            | `delta_scan`, `DESCRIBE`                                      | the schema, the column types and the rows of every version; a later append is picked up by a log replay |
| `delta_scan_answers_filters_and_aggregates_over_the_files_of_the_table` | a count, a sum and filters over 1000 rows in 4 commits | the log is listed and read, and the data files are fetched from LocalS3               |
| `a_delta_table_joins_a_parquet_file_of_the_same_bucket`       | a join of `delta_scan` with `read_parquet`                    | a lakehouse table and a plain object of the same bucket in one query                  |

The `iceberg` and `delta` extensions are loaded or installed like `httpfs`; without them, the tests are skipped.

# Concurrent range reads

`ConcurrentRangeReadIntegrationTest` reads an 8 MiB object uploaded at once and a 16 MiB object uploaded in 5 MiB
parts with 32 threads, 8000 range requests in all, in both `IN_MEMORY` and `PERSISTENCE` mode, and compares every
response with the bytes of its range. The ranges are the ones of a Parquet reader: suffix ranges (`bytes=-N`) for the
footer, small ranges anywhere, ranges across the boundaries of the parts, and open-ended ranges (`bytes=N-`). It needs
no network access.

# Apache Iceberg

`IcebergS3FileIOIntegrationTest` writes Iceberg tables with the Iceberg Java API and `S3FileIO` of `iceberg-aws`, with
the catalog properties of [Data tools](../docs/data-tools.md#apache-iceberg). The table metadata is committed by a
catalog of the test, `S3ConditionalTableOperations`, that creates each version, `metadata/v<N>.metadata.json`, with
`If-None-Match: *`, and turns a `412` into the `CommitFailedException` that makes Iceberg retry.

| Test                                                            | Iceberg                                                                        | S3 features checked on the LocalS3 side                                                         |
|-----------------------------------------------------------------|--------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------|
| `appendsReadsDeletesAndTimeTravelsAPartitionedTable`            | Parquet data files appended to a partitioned table, filtered reads, `newDelete`, `useSnapshot` | objects under `data/category=click/` and `metadata/`, a table reloaded from S3        |
| `writesALargeFileInPartsAndReadsItsRowGroupsWithRangeRequests`  | a ~13 MiB data file with 1 MiB row groups, with a 5 MiB part size              | `CreateMultipartUpload` / `UploadPart`, every read of the data file answered `206`              |
| `concurrentWritersCommitEachVersionOnceAndRetryOnConflicts`     | 8 writers append 5 times each to the same table at once                        | `If-None-Match: *` answers `412` to the losers, every version `v1` to `v41` exists, no row lost |
| `aStaleWriterDoesNotOverwriteTheCommitOfAnotherWriter`          | a commit on a stale version of the table                                       | `412`; the committed version is intact, and an append retries on the newer version              |
| `expiresSnapshotsAndDropsTheTableData`                          | `newOverwrite`, `expireSnapshots`, `CatalogUtil.dropTableData`, `deletePrefix` | `DeleteObjects`, and no object left under the table                                             |

Notes:

+ `iceberg-aws` doesn't bring the AWS SDK: besides `s3` and `apache-client`, `S3FileIO` loads the model classes of
  `kms` and `sts`.
+ The Parquet writers and readers of Iceberg take a Hadoop `Configuration`, from the shaded `hadoop-client-api` and
  `hadoop-client-runtime`; reading a table with `iceberg-data` also loads `iceberg-orc`.

# The built-in Iceberg REST catalog

`IcebergRestCatalogIntegrationTest` drives the catalog that LocalS3 serves at `/iceberg/v1` with the real
`RESTCatalog` of Apache Iceberg — the client that Spark, Trino, Flink and PyIceberg speak to a catalog with. LocalS3
builds the table metadata itself, without the Iceberg library, so only a real client reading it back proves that what
it writes is a table.

| Test                                                       | Iceberg                                                      | What it proves about the catalog                                                     |
|------------------------------------------------------------|--------------------------------------------------------------|---------------------------------------------------------------------------------------|
| `namespaces_are_created_listed_and_dropped`                | namespace CRUD and properties                                | nested namespaces are listed under their parent only                                   |
| `a_created_table_round_trips_through_the_catalog`          | `createTable`, `loadTable`, `listTables`                     | the schema and the UUID survive the metadata file LocalS3 wrote                        |
| `rows_written_to_a_table_are_read_back_through_the_catalog`| Parquet appends, filtered reads                              | a snapshot the client can scan, and a history that keeps both appends                  |
| `an_earlier_snapshot_is_still_readable_after_a_later_append` | `useSnapshot`                                              | the snapshot log makes the earlier state addressable                                   |
| `concurrent_appends_never_lose_a_writer_s_rows`            | 4 writers appending at once, with retries                    | the compare-and-set on the table pointer: 4 snapshots, every writer's rows             |
| `a_transaction_creates_the_table_only_once_its_data_is_written` | `createTransaction`, i.e. CTAS                           | a staged create writes nothing until the transaction commits                           |
| `a_schema_change_is_committed_and_read_back`               | `updateSchema`, `updateProperties`                           | `last-column-id` is tracked, so the added column gets the next id                      |
| `a_partitioned_table_keeps_its_spec`                       | an identity partition spec                                   | the spec and its field ids round trip                                                  |
| `tables_are_renamed_and_dropped`                           | `renameTable`, `dropTable`                                   | a rename moves the pointer and keeps the table                                         |

`IcebergRestCatalogPersistenceIntegrationTest` covers the two modes: a table written by a `PERSISTENCE` service is
there after a restart and can be committed to again, and an `IN_MEMORY` service started from the same directory reads
its tables without writing to it. `IcebergCatalogJupiterTest` covers `@LocalS3(icebergCatalog = true)`.

The HTTP surface of the catalog — statuses, the `error.type` a client maps to an exception, and that the catalog stays
off unless asked for — is covered by `IcebergCatalogEndpointTest` in `local-s3-rest`, which runs on every pull request.

# Delta Lake

`DeltaLakeIntegrationTest` drives [delta-kernel-java](https://delta.io/blog/delta-kernel/), the Delta client without
Spark. Delta reaches storage through `delta-kernel-defaults`, whose engine is built on one pluggable `FileIO`
interface, so the test plugs in `LocalS3DeltaFileIO` — a small `FileIO` backed by the AWS SDK that these tests already
use. That keeps Hadoop's `S3A`, and the AWS SDK bundle it drags in, out of the build.

**Everything here rests on one guarantee.** A version of a Delta table becomes visible by *creating*
`_delta_log/<version>.json`, so that create must fail when another writer got there first: a `PUT` with
`If-None-Match: *`, answered `412` for a key that is taken. `LocalS3DeltaFileIO` turns that `412` into the
`FileAlreadyExistsException` that Delta reads as a lost commit.

| Test                                                    | Delta                                                     | S3 features checked on the LocalS3 side                                     |
|---------------------------------------------------------|-----------------------------------------------------------|-------------------------------------------------------------------------------|
| `creating_a_table_writes_its_first_commit_to_the_delta_log` | `CREATE_TABLE`, `getLatestSnapshot`                    | version 0 is one object, `_delta_log/00000000000000000000.json`                |
| `rows_written_are_read_back_through_delta`              | two appends of Parquet data files, then a full read       | data files beside the log, one log file per commit                            |
| `a_second_writer_of_the_same_version_loses_the_commit`   | two writers on the same version, retries off              | `412` reaches Delta as a lost commit; the loser changed nothing               |
| `a_writer_that_lost_the_race_retries_and_keeps_its_rows` | the same race with Delta's default retries                | the loser rebases and commits, so a conflict costs a retry and never a row     |
| `the_commit_of_a_version_is_a_conditional_create`        | —                                                         | `If-None-Match: *` on an existing version answers `412`, on a free one `200`   |
| `an_earlier_version_is_still_readable_by_time_travel`    | `getSnapshotAsOfVersion`                                  | the log is append-only and the data files are never rewritten                 |
| `a_table_is_read_by_a_client_that_did_not_write_it`      | a second engine over the same bucket                      | a table is the objects, not the client that wrote them                        |

Notes:

+ `delta-kernel-defaults` brings no Spark: `delta-storage`, `parquet-hadoop`, Jackson 2 and the shaded Hadoop client,
  which the Iceberg tests already use. Jackson 2 lives beside the Jackson 3 of LocalS3 — different packages.
+ `hadoop-client-api` is on the compile classpath, not just at runtime: `DefaultEngine.create()` is overloaded on
  Hadoop's `Configuration`, so `javac` needs the class to resolve the `FileIO` overload the tests use.
+ `LocalS3DeltaFileIO` is written for clarity: an object being read is fetched whole, and one being written is
  buffered until it is closed. The tables of these tests are a few kilobytes.

# Apache Paimon

`PaimonIntegrationTest` runs the filesystem catalog of Paimon, without Flink or Spark, with its warehouse on
`s3a://lake/paimon`. `paimon-bundle` has no `FileIO` of its own for `s3a://`, so Paimon falls back to Hadoop's, and
reaches LocalS3 through the S3A connector of `HadoopS3AIntegrationTest`, the way a Flink or Spark job with
`hadoop-aws` on its classpath does.

| Step        | Paimon                                                                   | Checked                                                                     |
|-------------|--------------------------------------------------------------------------|-----------------------------------------------------------------------------|
| Create      | `createDatabase`, `createTable` of a partitioned primary-key table        | `listDatabases`, `listTables`; the table is on `HadoopFileIO`              |
| Write       | two batch commits: inserts, then an update and a `DELETE` row by key      | two snapshots, and one data file per commit in each bucket                  |
| Read        | a scan of the latest snapshot, and of `scan.snapshot-id = 1`             | the rows merged by key; time travel to the rows before the update           |
| Compaction  | `BatchTableWrite.compact(partition, bucket, true)`, what `sys.compact` runs | a `COMPACT` snapshot, one file per bucket, and the same rows               |
| Expiry      | `newExpireSnapshots()` down to one snapshot                              | the files the compaction replaced are deleted from the bucket              |
| Drop        | `dropTable`                                                              | the directory of the table is gone                                         |

# Apache Hudi

`HudiIntegrationTest` runs the write client of the Java engine of Hudi, `hudi-java-client`, without Spark, on tables
under `s3a://lake/hudi`, reached through S3A as well. The base files are read back with the Avro reader of Parquet,
from the latest file slices of Hudi's file system view: the read-optimized view.

| Test                                           | Hudi                                                                  | Checked                                                                  |
|------------------------------------------------|-----------------------------------------------------------------------|--------------------------------------------------------------------------|
| `insertsUpsertsAndDeletesInACopyOnWriteTable`  | a copy-on-write table: `insert`, `upsert` and `delete` by key, each a commit | three completed commits on the timeline, the rows of the latest base files, and the metadata table under `.hoodie/metadata/` |
| `compactsTheLogFilesOfAMergeOnReadTable`       | a merge-on-read table: an `insert`, an `upsert` into log files, then `scheduleCompaction` and `compact` | the base files don't see the update until the compaction, which leaves no log file in the latest slices |

Notes:

+ Hudi 1.x doesn't complete the instant of a write by itself: the test calls `commit(instant, statuses)`, and an
  instant left inflight is rolled back by the next write.
+ Hudi leaves Kryo to the bundles of Spark and Flink, so the build adds `kryo-shaded` 4.0.2, the version Hudi is built
  against. Paimon takes `lz4-java` from `at.yawk.lz4`, the maintained fork with the same packages, and Hudi from
  `org.lz4`; the build picks the higher version of the two.
+ Hudi takes its file systems from the cache of Hadoop, keyed by `s3a://lake`, so the test closes them after each
  test, whose LocalS3 has a port of its own.
