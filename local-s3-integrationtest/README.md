Local S3 integration test
==========================

End-to-end tests of LocalS3 with real clients: the AWS SDK for Java v2, and DuckDB.

```shell
./gradlew :local-s3-integrationtest:test
./gradlew :local-s3-integrationtest:test --tests '*DuckDbParquetIntegrationTest'
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

Notes:

+ The `httpfs` extension is loaded from `~/.duckdb/extensions`, or installed from the extension repository of DuckDB
  when it isn't there. Without network access and without an installed extension, the DuckDB tests are skipped.
+ DuckDB uploads a file with one `PutObject` if it fits in a single part, whose size is
  `s3_uploader_max_filesize / s3_uploader_max_parts_per_file` (800GB / 10000 = 80 MB by default); the multipart test
  sets `s3_uploader_max_filesize = '50GB'` for 5 MiB parts.
+ Every DuckDB test has a timeout: a listing whose continuation tokens don't advance makes DuckDB list forever, or
  read only the first page. These tests found such a bug, fixed with them: the continuation token of a URL encoded
  `ListObjectsV2` was computed from the encoded key, which sorts before the key itself (`%3D` < `=`).

Manual steps with the DuckDB CLI against a running LocalS3:

```sql
CREATE
SECRET (TYPE s3, ENDPOINT 'localhost:29090', URL_STYLE 'path', USE_SSL false, KEY_ID 'admin', SECRET 'admin');
COPY
(
SELECT *
FROM 'local-s3-integrationtest/src/test/resources/family.csv')
    TO 's3://demo1/family.parquet'
    (FORMAT parquet);
SELECT *
FROM read_parquet('s3://demo1/family.parquet');
```
