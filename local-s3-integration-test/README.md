Local S3 integration test
==========================

End-to-end tests of LocalS3 with real clients: the AWS SDK for Java v2, DuckDB and Apache Iceberg.

The tests are grouped by JUnit tag:

| Task            | Tag          | Tests                                                                                    |
|-----------------|--------------|------------------------------------------------------------------------------------------|
| `test`          | untagged     | LocalS3 with the AWS SDK; CI runs them on Linux for every pull request                   |
| `dataToolsTest` | `data-tools` | DuckDB and Apache Iceberg, the slow ones; CI runs them in a job of their own, in parallel |
| `realS3Test`    | `real-s3`    | the tests of `@RealS3`, which need AWS credentials; CI doesn't run them                   |

`check` runs `test` and `dataToolsTest`.

```shell
./gradlew :local-s3-integration-test:test
./gradlew :local-s3-integration-test:dataToolsTest
./gradlew :local-s3-integration-test:dataToolsTest --tests '*DuckDbParquetIntegrationTest'
./gradlew :local-s3-integration-test:dataToolsTest --tests '*IcebergS3FileIOIntegrationTest'
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
