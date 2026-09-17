# Data tools

How to point DuckDB, DuckLake and Apache Iceberg at LocalS3, e.g. a LocalS3 started with
`docker run -p 29090:29090 luofuxiang/local-s3` or embedded in an IDE. They run as end-to-end tests in
[`local-s3-integration-test`](../local-s3-integration-test/README.md).

A local endpoint has no DNS name for each bucket, so every client uses **path-style** addressing,
`http://localhost:29090/bucket/key`, and plain HTTP.

> [!IMPORTANT]
> **LocalS3 doesn't support TLS yet, and DuckDB uses HTTPS unless you turn it off.** If a DuckDB secret has no
> `USE_SSL false`, or `s3_use_ssl` isn't set to `false`, every query fails with a connection error such as:
>
> ```text
> IO Error: SSL connect error error for HTTP HEAD to 'https://localhost:29090/demo1/family.parquet'
> ```
>
> The `https://` in the URL shows the cause. Add `USE_SSL false` to the secret, or run `SET s3_use_ssl = false;`, and
> run the query again. Because DuckDB picks the secret with the longest matching scope, check that you changed the
> secret it actually uses; `SELECT name, scope FROM duckdb_secrets();` lists them. The exact wording of the error
> depends on the version of DuckDB; the message above is from DuckDB 1.5.

## DuckDB

With a secret, DuckDB 0.10 and later:

```sql
INSTALL httpfs;
LOAD httpfs;
CREATE SECRET local_s3 (
    TYPE s3,
    ENDPOINT 'localhost:29090',
    URL_STYLE 'path',
    USE_SSL false,
    KEY_ID 'admin',
    SECRET 'admin',
    REGION 'us-east-1'
);
```

Or with the S3 settings of `httpfs`, e.g. in a script or for a version of DuckDB without secrets:

```sql
SET s3_endpoint = 'localhost:29090';
SET s3_url_style = 'path';
SET s3_use_ssl = false;
SET s3_region = 'us-east-1';
SET s3_access_key_id = 'admin';
SET s3_secret_access_key = 'admin';
```

Then:

```sql
COPY (SELECT * FROM 'family.csv') TO 's3://demo1/family.parquet' (FORMAT parquet);
COPY (SELECT * FROM events) TO 's3://demo1/events' (FORMAT parquet, PARTITION_BY (day));
SELECT * FROM read_parquet('s3://demo1/events/**/*.parquet', hive_partitioning = true);
```

Without `URL_STYLE 'path'` / `s3_url_style = 'path'`, DuckDB sends virtual-hosted-style requests to
`demo1.localhost:29090`, which work only where that name resolves to LocalS3; LocalS3 accepts them for `localhost` and
the [virtual host domains](deployment.md#configuration) it is configured with.

DuckDB reads a Parquet file with range requests: the footer with a suffix range, then the row groups that a query
needs, on as many threads as `SET threads` allows. Concurrent queries on one LocalS3 therefore mean many small,
concurrent range requests, which LocalS3 answers from both storage modes; see `ConcurrentRangeReadIntegrationTest`
and `DuckDbParquetIntegrationTest.answersTheConcurrentRangeRequestsOfConcurrentQueries`.

## DuckLake

A DuckLake keeps its catalog in a SQL database, a DuckDB file, SQLite or PostgreSQL, and its data files as Parquet
files on S3. It connects to LocalS3 with the S3 secret of [DuckDB](#duckdb), including `USE_SSL false`:

```sql
INSTALL ducklake;
LOAD ducklake;
-- The secret local_s3 of the DuckDB section, for localhost:29090.
ATTACH 'ducklake:metadata.ducklake' AS lake (DATA_PATH 's3://lake/data/', ENCRYPTED);

CREATE TABLE lake.t AS SELECT * FROM range(100000);
UPDATE lake.t SET range = range + 1 WHERE range % 2 = 0;
SELECT count(*) FROM lake.t AT (VERSION => 1);

-- Maintenance: compaction, then deletion of the files that no snapshot refers to any more.
CALL ducklake_rewrite_data_files('lake');
CALL ducklake_merge_adjacent_files('lake');
CALL ducklake_expire_snapshots('lake', older_than => now() - INTERVAL 7 DAYS);
CALL ducklake_cleanup_old_files('lake', older_than => now() - INTERVAL 1 DAY);
```

The bucket must exist before the first write, e.g. with `buckets` of LocalS3 or `aws s3 mb`. `ENCRYPTED` is optional.

What LocalS3 answers for DuckLake:

+ data and delete files: `PutObject`, with the upload settings of `httpfs`, as for `COPY`;
+ reads, including time travel with `AT (VERSION => n)` or `AT (TIMESTAMP => ...)`: range requests;
+ `ducklake_cleanup_old_files`: `DeleteObjects`. `ducklake_expire_snapshots`
  and compaction only schedule files for deletion in the catalog; they don't delete objects;
+ `ENCRYPTED`: DuckLake encrypts each Parquet file with a key that only the catalog holds. LocalS3 stores the bytes as
  they are, so an object downloaded from LocalS3 starts and ends with `PARE` and isn't readable as a Parquet file
  without the catalog.

Small inserts are kept in the catalog instead of a Parquet file; set `DATA_INLINING_ROW_LIMIT 0` in the `ATTACH` options
to see every change as an object on LocalS3. Merging skips files that have deletes, so run
`ducklake_rewrite_data_files` before `ducklake_merge_adjacent_files` to compact a table after an `UPDATE` or a `DELETE`.

`DuckLakeIntegrationTest` covers time travel, the deletion of the files of expired snapshots and encryption.

## Iceberg REST catalogs that vend credentials

A catalog such as Apache Polaris, Lakekeeper, Gravitino or Unity Catalog gets temporary credentials for a table with
STS `AssumeRole` and hands them to the engine. Point its STS endpoint at LocalS3, which answers `AssumeRole` on the same
port; see [temporary credentials](embedding.md#temporary-credentials-sts). The catalog signs `AssumeRole` with the key
pair of LocalS3, and any role ARN works, e.g. `arn:aws:iam::000000000000:role/catalog`.

The names of the settings depend on the catalog and its version; check its documentation. For example, the S3 storage
configuration of a Polaris catalog sets `endpoint` and `stsEndpoint` to `http://localhost:29090`, `pathStyleAccess` to
`true` and `roleArn`, and a Lakekeeper storage profile of flavor `s3-compat` sets `endpoint`, `path-style-access`,
`sts-enabled: true` and `sts-role-arn`. These catalog settings aren't covered by the tests of LocalS3; the STS protocol
they use is, with the STS client of the AWS SDK.

## Apache Iceberg

`S3FileIO` of `iceberg-aws`, configured with catalog properties:

```properties
io-impl=org.apache.iceberg.aws.s3.S3FileIO
s3.endpoint=http://localhost:29090
s3.path-style-access=true
s3.access-key-id=admin
s3.secret-access-key=admin
client.region=us-east-1
```

The same with Spark, for a catalog named `local`:

```properties
spark.sql.catalog.local.io-impl=org.apache.iceberg.aws.s3.S3FileIO
spark.sql.catalog.local.warehouse=s3://warehouse/
spark.sql.catalog.local.s3.endpoint=http://localhost:29090
spark.sql.catalog.local.s3.path-style-access=true
spark.sql.catalog.local.s3.access-key-id=admin
spark.sql.catalog.local.s3.secret-access-key=admin
spark.sql.catalog.local.client.region=us-east-1
```

Or in Java:

```java
S3FileIO io = new S3FileIO();
io.initialize(Map.of(
    "s3.endpoint", "http://localhost:29090",
    "s3.path-style-access", "true",
    "s3.access-key-id", "admin",
    "s3.secret-access-key", "admin",
    "client.region", "us-east-1"));
```

`iceberg-aws` doesn't bring the AWS SDK: an application adds `s3`, and `kms` and `sts`, whose model classes
`S3FileIO` loads, and an HTTP client, `apache-client` by default. Reading and writing Parquet with the Iceberg Java API
also takes a Hadoop `Configuration`, e.g. from `hadoop-client-api` and `hadoop-client-runtime`.

What LocalS3 answers for Iceberg:

+ data files, manifests and manifest lists: `PutObject`, or a multipart upload in parts of
  `s3.multipart.part-size-bytes` (32 MiB by default) for a larger file;
+ reads: range requests, so that a scan reads the row groups that its filter needs;
+ `expireSnapshots`, `dropTableData` and `deletePrefix`: `DeleteObjects` and `ListObjectsV2`;
+ commits of catalogs that keep the table metadata on S3: `If-None-Match: *` creates a metadata version only if no
  other writer created it first, and answers `412 Precondition Failed` otherwise, which a catalog turns into the
  `CommitFailedException` that makes Iceberg refresh the table and retry. The commit protocol of Delta Lake relies
  on the same condition for `_delta_log/<version>.json`. See [conditional requests](semantics.md#conditional-requests).

`IcebergS3FileIOIntegrationTest` covers these with a catalog of that kind, including writers that commit to the same
table at once.
