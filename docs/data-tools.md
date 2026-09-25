# Data tools

How to point DuckDB, DuckLake, Apache Iceberg, Delta Lake, Hadoop S3A, [Apache Paimon](#apache-paimon),
[Apache Hudi](#apache-hudi) and the [Python](#python) data tools at LocalS3,
e.g. a LocalS3 started with `docker run -p 29090:29090 luofuxiang/local-s3` or embedded in an IDE. They run as
end-to-end tests in [`local-s3-integration-test`](../local-s3-integration-test/README.md), and the Python ones in
[`ceph-s3-tests/data-tools`](../ceph-s3-tests/data-tools/README.md).

A local endpoint has no DNS name for each bucket, so every client uses **path-style** addressing,
`http://localhost:29090/bucket/key`. The examples below use plain HTTP, which LocalS3 serves by default; a LocalS3
started with a certificate serves [HTTPS](deployment.md#https) instead, and the clients then keep their TLS defaults.

> [!IMPORTANT]
> **DuckDB uses HTTPS unless you turn it off, and LocalS3 serves plain HTTP unless it is
> [configured with a certificate](deployment.md#https).** Against a plain HTTP LocalS3, if a DuckDB secret has no
> `USE_SSL false`, or `s3_use_ssl` isn't set to `false`, every query fails with a connection error such as:
>
> ```text
> IO Error: SSL connect error error for HTTP HEAD to 'https://localhost:29090/demo1/family.parquet'
> ```
>
> The `https://` in the URL shows the cause. There are two ways out:
>
> + **Turn TLS off in DuckDB**: add `USE_SSL false` to the secret, or run `SET s3_use_ssl = false;`, and run the query
>   again. Because DuckDB picks the secret with the longest matching scope, check that you changed the secret it
>   actually uses; `SELECT name, scope FROM duckdb_secrets();` lists them.
> + **Serve HTTPS**, and keep the DuckDB defaults: start LocalS3 with `LOCAL_S3_TLS_SELF_SIGNED=true`, which
>   [generates a certificate](deployment.md#generate-a-certificate-on-startup) for `localhost` and logs it in PEM
>   format, save that PEM block to a file, and point DuckDB at it with `SET ca_cert_file = 'local-s3.pem';`. Without
>   the CA file DuckDB refuses the generated certificate with
>   `SSL peer certificate or SSH remote key was not OK`; with a certificate of
>   [mkcert](deployment.md#create-a-certificate-with-mkcert) it needs no CA file at all.
>
> The port keeps answering plain HTTP either way, so turning TLS on for DuckDB doesn't cut off the other clients,
> scripts and tests that address the same service with an `http://` endpoint.
>
> The exact wording of the errors depends on the version of DuckDB; the messages here are from DuckDB 1.5.

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

A running LocalS3 writes this secret itself, for the host, the port, the scheme and the credentials it actually has —
and the `ATTACH` of its [Iceberg catalog](#duckdb-on-the-built-in-catalog) if it serves one:

```shell
# A DuckDB shell with the secret, and the ATTACH of the catalog, already run.
duckdb -init <(curl -s http://localhost:29090/_admin/snippets/duckdb)
# One query of an object, and exit.
curl -s "http://localhost:29090/_admin/snippets/duckdb?bucket=demo1&key=family.parquet" | duckdb

# A LocalS3 that requires credentials wants the admin endpoints signed, like every other request.
duckdb -init <(curl -s --aws-sigv4 "aws:amz:us-east-1:s3" --user "$AWS_ACCESS_KEY_ID:$AWS_SECRET_ACCESS_KEY" \
    http://localhost:29090/_admin/snippets/duckdb)
```

The [console](deployment.md#console) shows the same script under `Connect`, and `DuckDB SQL` in the preview of an
object adds a query of it. See [admin endpoints](deployment.md#admin-endpoints).

Then:

```sql
COPY (SELECT * FROM 'family.csv') TO 's3://demo1/family.parquet' (FORMAT parquet);
COPY (SELECT * FROM events) TO 's3://demo1/events' (FORMAT parquet, PARTITION_BY (day));
SELECT * FROM read_parquet('s3://demo1/events/**/*.parquet', hive_partitioning = true);
```

Temporary credentials, e.g. the ones that a catalog like Apache Polaris gets with `AssumeRole` from the
[STS endpoint](embedding.md#temporary-credentials-sts) of LocalS3 and hands out, take their session token as well:

```sql
CREATE SECRET local_s3_session (
    TYPE s3, ENDPOINT 'localhost:29090', URL_STYLE 'path', USE_SSL false, REGION 'us-east-1',
    KEY_ID 'ASIA...', SECRET '...', SESSION_TOKEN '...'
);
```

Or leave the credentials out of the SQL with `PROVIDER credential_chain`, which takes them where the AWS SDK looks for
them, session token included. Only the endpoint settings stay in the secret:

```sql
-- AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY and AWS_SESSION_TOKEN of the environment
CREATE SECRET local_s3 (TYPE s3, PROVIDER credential_chain, CHAIN 'env',
    ENDPOINT 'localhost:29090', URL_STYLE 'path', USE_SSL false, REGION 'us-east-1');

-- A named profile of ~/.aws, e.g. the one `aws configure --profile local-s3` writes
CREATE SECRET local_s3 (TYPE s3, PROVIDER credential_chain, CHAIN 'config', PROFILE 'local-s3',
    ENDPOINT 'localhost:29090', URL_STYLE 'path', USE_SSL false);
```

With `CHAIN 'config'`, DuckDB wants the profile in both files, as `aws configure` writes them: `[profile local-s3]`
in `~/.aws/config` (or `AWS_CONFIG_FILE`) and `[local-s3]` with the keys in `~/.aws/credentials` (or
`AWS_SHARED_CREDENTIALS_FILE`). A profile that is only in `~/.aws/config` fails `CREATE SECRET` with
`Secret Validation Failure`. Name the chain rather than relying on the default one: the default starts with the
environment, so an `AWS_ACCESS_KEY_ID` of a real account in the shell would win over the profile.
`DuckDbParquetIntegrationTest.readsAndWritesWithTheCredentialChainOfAProfileOfStsCredentials` writes such a profile
with the credentials of `AssumeRole`.

`COPY ... (OVERWRITE)` and `PER_THREAD_OUTPUT` replace the files of a directory by listing it and deleting them, and
the values of `PARTITION_BY` are escaped by DuckDB itself, e.g. `city=New%20York`; keys with spaces and `+` are
listed with `%20` and `%2B` under `encoding-type=url`, as Amazon S3 lists them. `DuckDbParquetIntegrationTest` covers
all three.

Without `URL_STYLE 'path'` / `s3_url_style = 'path'`, DuckDB sends virtual-hosted-style requests to
`demo1.localhost:29090`, which work only where that name resolves to LocalS3; LocalS3 accepts them for `localhost` and
the [virtual host domains](deployment.md#configuration) it is configured with.

DuckDB reads a Parquet file with range requests: the footer with a suffix range, then the row groups that a query
needs, on as many threads as `SET threads` allows. Concurrent queries on one LocalS3 therefore mean many small,
concurrent range requests, which LocalS3 answers from both storage modes; see `ConcurrentRangeReadIntegrationTest`
and `DuckDbParquetIntegrationTest.answersTheConcurrentRangeRequestsOfConcurrentQueries`.

Beyond plain files, DuckDB reaches the table formats on LocalS3 with an extension each: [DuckLake](#ducklake), the
[built-in Iceberg REST catalog](#duckdb-on-the-built-in-catalog) and [Delta tables](#reading-a-delta-table-with-duckdb).

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

With a PostgreSQL catalog, which several DuckDB processes can share, only the `ATTACH` changes:

```sql
INSTALL postgres;
ATTACH 'ducklake:postgres:host=localhost dbname=lake user=postgres' AS lake
    (DATA_PATH 's3://lake/data/', DATA_INLINING_ROW_LIMIT 100);
-- Inlined inserts, updates and deletes are rows in PostgreSQL; this writes them to LocalS3 as Parquet files.
CALL ducklake_flush_inlined_data('lake');
```

Until the flush, inlined changes, including deletes of rows that are already in files, don't reach LocalS3 at all.

`DuckLakeIntegrationTest` covers time travel, the deletion of the files of expired snapshots and encryption;
`DuckLakePostgresIntegrationTest` covers data inlining, flushing and compaction with a PostgreSQL catalog.

## The built-in Iceberg REST catalog

LocalS3 can serve an [Iceberg REST catalog](https://iceberg.apache.org/spec/#rest-catalog) of its own, beside the S3
API and on the same port, under `/iceberg/v1`. It is **off by default**; turning it on gives a lakehouse test one
process instead of two, because the catalog and the storage are then the same service.

```java
LocalS3 localS3 = LocalS3.builder().port(29090).icebergCatalog(true).build();
localS3.start();

RESTCatalog catalog = new RESTCatalog();
catalog.initialize("local", Map.of("uri", "http://localhost:29090/iceberg"));
catalog.createNamespace(Namespace.of("db"));
catalog.createTable(TableIdentifier.of("db", "orders"), schema);
```

In a JUnit 5 test, one annotation attribute is the whole setup:

```java
@LocalS3(icebergCatalog = true)
class MyTest {

  @Test
  void test(LocalS3Endpoint endpoint) {
    RESTCatalog catalog = new RESTCatalog();
    catalog.initialize("local", Map.of("uri", endpoint.icebergCatalogUri()));
    // warehouse: s3://warehouse/
  }
}
```

The URI is the whole client configuration. `GET /v1/config` and every loaded table carry the S3 endpoint, path-style
access and the credentials of the service — the *credential vending* of the REST protocol — so an engine reaches the
storage without being configured for it:

```properties
spark.sql.catalog.local      = org.apache.iceberg.spark.SparkCatalog
spark.sql.catalog.local.type = rest
spark.sql.catalog.local.uri  = http://localhost:29090/iceberg
```

Elsewhere:

| Where | How |
|---|---|
| Java | `LocalS3.builder().icebergCatalog(true)`, or `.icebergCatalog(iceberg -> iceberg.warehouse("s3://lakehouse/"))` |
| JUnit 5 | `@LocalS3(icebergCatalog = true)` |
| Spring Boot | `local-s3.iceberg-catalog.enabled=true`, `local-s3.iceberg-catalog.warehouse=s3://warehouse/` |
| Docker / jar | `LOCAL_S3_ICEBERG_CATALOG=true`, `LOCAL_S3_ICEBERG_WAREHOUSE=s3://warehouse/` |

PyIceberg reaches it the same way:

```python
from pyiceberg.catalog.rest import RestCatalog
catalog = RestCatalog("local", uri="http://localhost:29090/iceberg")
```

### DuckDB on the built-in catalog

The `iceberg` extension of DuckDB attaches a REST catalog as a database and writes to it as well as reads from it,
which is the shortest path from an IDE to a lakehouse table. Against the built-in catalog it takes one statement and
**no S3 secret**: the endpoint, the path-style addressing and the credentials are vended by the catalog, so DuckDB is
never told where the storage is — nor that it speaks plain HTTP, which is what the `USE_SSL false` of a hand-written
secret is for.

```sql
INSTALL iceberg;
LOAD iceberg;

ATTACH 'warehouse' AS ice (
    TYPE ICEBERG,
    ENDPOINT 'http://localhost:29090/iceberg',
    AUTHORIZATION_TYPE 'none'
);
```

`AUTHORIZATION_TYPE 'none'` is the one thing to remember: without it DuckDB insists on fetching an OAuth2 token before
its first request and fails with

```text
Invalid Configuration Error: AUTHORIZATION_TYPE is 'oauth2', yet no 'secret' was provided, and no
client_id+client_secret were provided.
```

The first argument of `ATTACH` is the warehouse. The built-in catalog serves a single warehouse and answers whatever
name a client sends; a [table bucket](#amazon-s3-tables) is attached by naming its ARN there instead, exactly as a
`RESTCatalog` names it:

```sql
ATTACH 'arn:aws:s3tables:us-east-1:000000000000:bucket/lakehouse' AS tb (
    TYPE ICEBERG, ENDPOINT 'http://localhost:29090/iceberg', AUTHORIZATION_TYPE 'none');
```

DuckDB's own way into Amazon S3 Tables, `ATTACH 'arn:...' (TYPE ICEBERG, ENDPOINT_TYPE s3_tables)`, **can't be pointed
at LocalS3**: as of DuckDB 1.5 it always sends its requests to `s3tables.<region>.amazonaws.com` and ignores an
`ENDPOINT`, and `AUTHORIZATION_TYPE 'sigv4'` with an `ENDPOINT` of LocalS3 fails with `Could not parse AWS service from
host`. Use the `ATTACH` above instead: the same ARN, as the warehouse of the REST catalog. The tables it creates are
tables of the S3 Tables API (`ListTables`, `GetTableMetadataLocation`), which
`DuckDbIcebergIntegrationTest.duckdb_attaches_a_table_bucket_by_its_arn` checks.

The attached catalog is then a database like any other, and the writes are Iceberg commits:

```sql
CREATE SCHEMA ice.db;
CREATE TABLE ice.db.events (id BIGINT, name VARCHAR, level VARCHAR);
INSERT INTO ice.db.events VALUES (1, 'row-1', 'info'), (2, 'row-2', 'warn');
CREATE TABLE ice.db.numbers AS SELECT range AS id, 'row-' || range AS name FROM range(1000);

SELECT count(*) FROM ice.db.events WHERE level = 'info';
UPDATE ice.db.events SET level = 'error' WHERE id = 2;
DELETE FROM ice.db.events WHERE id = 1;
ALTER TABLE ice.db.events ADD COLUMN score DOUBLE;

SHOW ALL TABLES;                                  -- the namespaces and tables of the catalog
SELECT * FROM iceberg_snapshots('ice.db.events'); -- one row per commit
SELECT * FROM ice.db.events AT (VERSION => 3949531829775254263);
```

Time travel takes a **snapshot id**, not an ordinal version; `iceberg_snapshots` is where the ids come from.

Beyond those statements, DuckDB 1.5 does the following on the built-in catalog, and the Iceberg Java client reads what
each one leaves:

```sql
-- The partition transforms of Iceberg, e.g. identity, bucket and month.
CREATE TABLE ice.db.metrics (id BIGINT, day DATE, v DOUBLE) PARTITIONED BY (bucket(4, id), month(day));

MERGE INTO ice.db.events USING updates u ON events.id = u.id
    WHEN MATCHED THEN UPDATE SET level = u.level
    WHEN NOT MATCHED THEN INSERT VALUES (u.id, u.name, u.level);
TRUNCATE ice.db.events;

-- Several statements, visible to other clients once committed and not at all once rolled back.
BEGIN; INSERT INTO ice.db.events VALUES (4, 'row-4', 'info'); DELETE FROM ice.db.events WHERE id = 1; COMMIT;

ALTER TABLE ice.db.events RENAME COLUMN name TO label;   -- keeps the field id
ALTER TABLE ice.db.events DROP COLUMN label;
ALTER TABLE ice.db.metrics ALTER COLUMN id SET DATA TYPE BIGINT;  -- the promotions Iceberg allows, e.g. INT to BIGINT
ALTER TABLE ice.db.events RENAME TO log;
DROP TABLE ice.db.log;
DROP SCHEMA ice.db;
```

Lists, structs, maps, `TIMESTAMPTZ` and decimals are written as the matching Iceberg types. What DuckDB 1.5 refuses on
an Iceberg catalog: `DROP SCHEMA ... CASCADE` (drop the tables first), `CREATE VIEW`, and `ADD COLUMN ... DEFAULT` on
a table of format version 1 or 2.

A table can also be read without the catalog, by the metadata file it currently is — and then DuckDB does need an
[S3 secret](#duckdb) of its own, because nothing vends one for a location:

```sql
SELECT * FROM iceberg_scan('s3://warehouse/db/events/metadata/00002-....metadata.json');

-- Or by location, which makes DuckDB find the current metadata file by listing; it refuses to do that unless asked,
-- because a listing can turn up a version that no commit has made current yet.
SET unsafe_enable_version_guessing = true;
SELECT * FROM iceberg_scan('s3://warehouse/db/events');
```

`DuckDbIcebergIntegrationTest` runs all of this against LocalS3 with the signature verification on, so the vended
credentials have to be right, and checks the crossings in both directions: what DuckDB writes is read back with the
Iceberg Java client, and what that client writes is read by DuckDB.

### What it stores, and where

A table is two things: a `metadata.json` in the object store, and a catalog pointer to the file the table currently is.
LocalS3 keeps the pointer beside its bucket metadata and writes the metadata files into the **warehouse bucket**
(`s3://warehouse/` by default, created on startup), through its own S3 services rather than over HTTP to itself. So
both modes work without anything extra:

+ an **`IN_MEMORY`** service keeps its tables in memory, and `reset()` — or `POST /_admin/reset` — drops them with the
  rest of the data, which is what makes the catalog usable between tests;
+ a **`PERSISTENCE`** service writes them to its data directory, and a service started again over that directory finds
  its namespaces and tables where they were;
+ an `IN_MEMORY` service started from a data directory reads the tables of that directory and never writes to it, like
  it treats the objects and vectors there.

### Commits and conflicts

A commit sends the requirements the table must still satisfy and the updates to apply. LocalS3 checks the
requirements, writes a new metadata file and then moves the pointer with a compare-and-set: of two writers that started
from the same snapshot, exactly one moves it, and the other is answered `409 CommitFailedException`. That is the answer
the Iceberg client retries on — it refreshes the table, replays its changes and commits again — so concurrent appends
converge instead of overwriting one another. `IcebergRestCatalogIntegrationTest` asserts exactly that: four writers
appending at once end with four snapshots and every writer's rows.

### What it is checked against

LocalS3 builds the table metadata itself, without the Iceberg library, which is what keeps `local-s3-core` free of a
heavy dependency and a service quick to start. The risk that comes with it is drift: the specification moves, and a
hand-written catalog doesn't move with it by itself.

So the catalog is run against **Iceberg's own test suites for a catalog implementation**, taken from the test jars of
`iceberg-core` rather than written here:

| Suite | Where | What it covers |
|---|---|---|
| `org.apache.iceberg.catalog.CatalogTests` | `IcebergRestCatalogComplianceTest` | 102 cases: namespace semantics, field-ID assignment, requirement evaluation, concurrent commits, staged creates, `replaceTransaction`, metadata-log trimming, table registration |
| `org.apache.iceberg.view.ViewCatalogTests` | `IcebergRestViewCatalogComplianceTest` | 50 cases: view versions and their history, replacing a version, dialects, views and tables sharing a namespace |

`IcebergProtocolCoverageTest` adds what those two can't cover: they only reach the updates their own operations send, so
it asks the Iceberg library itself which updates and requirements exist and checks the catalog against that list.

All three run in `./gradlew :local-s3-integration-test:dataToolsTest`. **The way to check a new Iceberg release is to bump
`iceberg` in `gradle/libs.versions.toml` and run that task**; what the suites don't accept is drift, whatever the
version says.

Currently aligned with **Iceberg 1.11.0** (spec v1, v2 and v3), and all of it that a catalog decides:

+ all 25 `MetadataUpdate` actions of the table and the view protocol, and all 9 `UpdateRequirement` types, which
  `IcebergProtocolCoverageTest` checks by reading the names out of the parsers of the Iceberg library rather than from a
  list of its own. An update, or a requirement, that LocalS3 doesn't know is **refused** rather than ignored, because
  applying a commit only in part would hand a client metadata that isn't what it asked for;
+ a table created at, or upgraded to, **v3** carries `next-row-id`, and a commit that adds a snapshot advances it past
  the rows the snapshot added, so the row IDs of two appends don't overlap;
+ the field IDs of a new table are **re-assigned from 1**, like `TableMetadata.newTableMetadata` does, and the
  `source-id` of every partition and sort field moves with them.

### Limits

+ Requests to the catalog are **not signature-verified**. The REST protocol authenticates with an OAuth2 bearer token
  rather than an AWS signature; a token is accepted without being checked, and `POST /v1/oauth/tokens` hands one out so
  that a client configured with `credential` starts. The S3 requests the engine then makes are verified as usual.
+ The catalog of the service serves **one warehouse**, and answers no `prefix`. The catalogs of the
  [table buckets](#amazon-s3-tables) of the same service are served under a `prefix` each, which `GET /v1/config`
  answers to a client that names a table bucket as its warehouse; a prefix that names no table bucket is read as if it
  weren't there.
+ A table, a view and their metadata files live **in LocalS3**. A create request whose `location`, or whose
  `write.metadata.path`, names anything but an `s3://` URI of a bucket of this service is refused: the catalog has no
  way to write a `file:` or `gs:` location.
+ The **empty namespace** is not served, and neither is it by the REST catalog of Iceberg itself: a table lives in a
  namespace of at least one level.
+ `GET /v1/config` answers the routes it serves in `endpoints`, and the ones it doesn't are therefore not called:
  **scan planning** (`/plan`, `/tasks`) — a client reads the table itself.
+ The **credentials endpoint** of a table, `GET /v1/{prefix}/namespaces/{namespace}/tables/{table}/credentials`,
  answers temporary credentials of the [STS endpoint](embedding.md#temporary-credentials-sts), valid for an hour, with
  a session token and `s3.session-token-expires-at-ms`, for the prefix `s3://`. A service that verifies signatures
  names the route in the `client.refresh-credentials-endpoint` of every loaded table, so the `S3FileIO` of Iceberg
  takes its credentials from there and takes new ones five minutes before they expire, as it does against Polaris or
  AWS; set `client.refresh-credentials-enabled=false` on the client to keep the keys of the `config` instead. A service
  that takes unsigned requests names no route, since there is nothing to refresh. Like the rest of the catalog, the
  route doesn't check who asks.
+ **Remote signing** is served for a client whose `S3FileIO` is configured with `s3.remote-signing-enabled=true`
  (e.g. a Spark or Trino set up for a catalog that doesn't vend credentials): the catalog signs each S3 request with
  the **credentials of the service itself**, at the route of the table,
  `POST /v1/{prefix}/namespaces/{namespace}/tables/{table}/sign`, which a loaded table names in `s3.signer.uri` and
  `s3.signer.endpoint`, and at `POST /v1/aws/s3/sign`, the default route of a client that was told none. Signing is
  not access control: like the rest of the catalog, the routes don't check who asks, so anyone who can reach the
  catalog can have any S3 request of this service signed. The payload is signed as `UNSIGNED-PAYLOAD`.
+ A multi-table transaction (`POST /v1/transactions/commit`) prepares every commit, then moves the pointers, rolling
  the moved ones back if one fails. The tables end up all committed or all unchanged, but a reader during those few
  compare-and-sets can see part of it.
+ Dropping a table forgets it; the files stay in the bucket unless the drop asks for `purgeRequested=true`. A purge
  deletes everything under the location of the table, and is **skipped** when another table of the catalog keeps its
  metadata under that location — which happens when a table is created under the name of a dropped or renamed one,
  because the default location of a table is derived from its name. `icebergCatalog(iceberg ->
  iceberg.uniqueTableLocation(true))` gives every table a location of its own instead, which is the
  `unique-table-location` of the Iceberg catalogs.

## Amazon S3 Tables

[Amazon S3 Tables](https://docs.aws.amazon.com/AmazonS3/latest/userguide/s3-tables.html) is AWS's managed Iceberg: a
*table bucket* holds namespaces and tables, and an engine reaches them either through the `s3tables` control plane or
through the Iceberg REST endpoint of the table bucket. LocalS3 answers both, on the S3 port, and they are **two views of
one catalog** — a table an engine creates over REST is the table `GetTable` answers, and a commit made through either is
what the other then reads. Nothing has to be turned on.

```java
S3TablesClient tables = S3TablesClient.builder()
    .endpointOverride(URI.create("http://localhost:29090"))
    .region(Region.US_EAST_1)
    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(key, secret)))
    .build();

String arn = tables.createTableBucket(r -> r.name("lakehouse")).arn();
tables.createNamespace(r -> r.tableBucketARN(arn).namespace("db"));
tables.createTable(r -> r.tableBucketARN(arn).namespace("db").name("orders")
    .format(OpenTableFormat.ICEBERG)
    .metadata(m -> m.iceberg(i -> i.schema(sc -> sc.fields(
        f -> f.name("id").type("long").required(true),
        f -> f.name("total").type("double"))))));
```

In a JUnit 5 test an `S3TablesClient` is injected, configured for the service:

```java
@LocalS3
class MyTest {

  @Test
  void test(S3TablesClient tables, S3Client s3) {
    String arn = tables.createTableBucket(r -> r.name("lakehouse")).arn();
    // The tables live in the bucket 'lakehouse--table-s3' of the same service, so s3 can look at them.
  }
}
```

### The same table bucket as an Iceberg REST catalog

Point a `RESTCatalog` at the endpoint with the **ARN of the table bucket as its warehouse**, which is how Amazon
documents its own Iceberg REST endpoint. LocalS3 answers the `prefix` of that table bucket's catalog, and the client
carries it in every request it then makes:

```java
RESTCatalog catalog = new RESTCatalog();
catalog.initialize("s3tables", Map.of(
    "uri", "http://localhost:29090/iceberg",
    "warehouse", "arn:aws:s3tables:us-east-1:000000000000:bucket/lakehouse"));
catalog.loadTable(TableIdentifier.of("db", "orders"));
```

Spark, Trino and PyIceberg take the same configuration they take against the real endpoint, with the URI pointed at
LocalS3. `rest.sigv4-enabled` and `rest.signing-name=s3tables` are accepted and verified; they can also be left out,
because LocalS3 answers the catalog either way:

```properties
spark.sql.catalog.s3tables            = org.apache.iceberg.spark.SparkCatalog
spark.sql.catalog.s3tables.type       = rest
spark.sql.catalog.s3tables.uri        = http://localhost:29090/iceberg
spark.sql.catalog.s3tables.warehouse  = arn:aws:s3tables:us-east-1:000000000000:bucket/lakehouse
```

### Reaching the API

The paths of the S3 Tables API are paths of Amazon S3 too: `PUT /buckets` is `CreateTableBucket` of the one and
`CreateBucket` of a bucket named `buckets` of the other, and `GET /tables/...` is a listing of a bucket named `tables`.
What tells them apart is the signature — every AWS SDK signs a request of this API for the **`s3tables`** service, and
that service is in the credential scope of the `Authorization` header. LocalS3 reads the scope before it parses a
bucket, and then verifies the signature for that same service, so claiming the scope buys a client nothing it couldn't
have signed for. A bucket named `buckets` or `tables` keeps working for an S3 client.

A client that signs **nothing** — an `AnonymousCredentialsProvider`, which is what a test points at a LocalS3 without
credentials — carries no scope to be told apart by, so it reaches the API under `/s3tables` instead:

```java
S3TablesClient.builder().endpointOverride(URI.create("http://localhost:29090/s3tables"))
```

`@LocalS3` and `LocalS3Container` pick the right one for you: `LocalS3Endpoint.s3TablesEndpoint(boolean signed)` and
`LocalS3Container.getS3TablesEndpoint()`. The path shadows a bucket named `s3tables`, the way `/iceberg` shadows one
named `iceberg`.

### What it stores, and where

A table bucket of Amazon S3 keeps its files out of reach of the S3 API. LocalS3 keeps them in an **ordinary bucket of the
same service**, named `<table-bucket>--table-s3` and created with the table bucket, which is what a table's
`warehouseLocation` points into. So the engine that loaded a table writes its data files there with its own `S3FileIO`,
against the same endpoint and with the same credentials, and a test can look at what was written with an `S3Client` —
which against the real service it could not. Deleting the table bucket deletes that bucket too.

The namespaces and the table pointers of each table bucket live in the store of the service, in maps of their own, so a
`PERSISTENCE` service finds its table buckets where it left them and an `IN_MEMORY` one drops them on `reset()`, like the
rest of its data. Two table buckets are two catalogs: the same namespace name in each is a different namespace.

### Commits and the version token

A commit of this API is the client writing the next `metadata.json` itself and then calling
`UpdateTableMetadataLocation` with the **version token** it read the table at. The token is opaque and drawn again on
every change, so a commit built on a stale read is answered `409 ConflictException` and the client refreshes and
retries — which is what the `s3-tables-catalog` library does.

The token follows commits made over the Iceberg REST endpoint of the same table bucket as well: when a table's pointer
moved without going through this API, the next read draws a new token, so a client still holding the one from before that
commit loses rather than silently overwriting a commit it never saw.

### Limits

+ **An unsigned client reaches the API only under `/s3tables`**, for the reason above: an unsigned request carries
  nothing that says which of the two APIs it means.
+ Every table bucket is answered with the region and the account of LocalS3 — `us-east-1` and `000000000000` — and the
  account of an ARN a client sends is **ignored** rather than refused: a test double is not an authorization boundary.
+ A namespace is **one level**, as it is in Amazon S3 Tables, whatever the shape of the `namespace` field suggests.
+ `ICEBERG` is the only `format`, as it is in Amazon S3 Tables.
+ Encryption, storage class, resource policies, maintenance, metrics, replication and record expiration are **stored and
  read back, and nothing happens**: nothing is encrypted or tiered, no policy is enforced, no compaction or expiration
  job ever runs, and `GetTableMaintenanceJobStatus` answers `Not_Yet_Run` for every job. That is what lets the code path
  under test — which usually sets a configuration on the way to doing something else — run through.
+ A table created over the Iceberg REST endpoint of a table bucket has no ID, version token or timestamps until this API
  is **first asked about it**, which is when it is given them. Its `createdAt` is therefore when this API first saw it,
  not when the engine created it.
+ **Views** are not part of the S3 Tables API. One created over the Iceberg REST endpoint of a table bucket stays
  reachable there and is not listed by `ListTables`.
+ Deleting a table **deletes its files**, as Amazon does. Every table gets a location of its own, so a table created
  under the name of a deleted one never shares files with it.

## Iceberg REST catalogs that vend credentials

If you run a catalog of your own rather than the built-in one above — Apache Polaris, Lakekeeper, Gravitino or Unity
Catalog — it gets temporary credentials for a table with STS `AssumeRole` and hands them to the engine. Point its STS endpoint at LocalS3, which answers `AssumeRole` on the same
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
  on the same condition for `_delta_log/<version>.json`, see [Delta Lake](#delta-lake).
  See [conditional requests](semantics.md#conditional-requests).

`IcebergS3FileIOIntegrationTest` covers these with a catalog of that kind, including writers that commit to the same
table at once.

## Delta Lake

A Delta table needs nothing of LocalS3 but object storage and **one guarantee**: a version of the table becomes
visible by *creating* `_delta_log/<version>.json`, and that create must fail if another writer got there first.
On S3 that is a `PUT` carrying `If-None-Match: *`, which LocalS3 answers with `412 Precondition Failed` when the key
is taken:

```text
PUT _delta_log/00000000000000000001.json   If-None-Match: *   → 200
PUT _delta_log/00000000000000000001.json   If-None-Match: *   → 412
```

That is what turns a lost race into a retry instead of a silently overwritten commit. Without it two writers would
both report success and one writer's rows would be gone.

### Pointing a Delta client at LocalS3

Delta clients reach S3 through Hadoop's `S3A`, so the settings are the Hadoop ones:

```properties
fs.s3a.endpoint=http://localhost:29090
fs.s3a.path.style.access=true
fs.s3a.access.key=admin
fs.s3a.secret.key=admin
```

With Spark and `delta-spark`, the same as `spark.hadoop.fs.s3a.*`, and the table path is `s3a://my-bucket/tables/events`.
delta-rs, the Delta Lake of Python and Polars, doesn't go through Hadoop; see [delta-rs and Polars](#delta-rs-and-polars).

`DeltaLakeIntegrationTest` drives [delta-kernel-java](https://delta.io/blog/delta-kernel/) — the Delta client without
Spark — over `LocalS3DeltaFileIO`, a small `FileIO` backed by the AWS SDK rather than by `S3A`, so the test needs
neither Hadoop's `S3A` nor the AWS SDK bundle it pulls in; S3A itself is covered by
[`HadoopS3AIntegrationTest`](#hadoop-s3a). It covers creating a table, writing and reading rows back,
two writers racing for the same version (both with retries off, where the loser is refused, and with the retries Delta
does by default, where the loser rebases and keeps its rows), time travel to an earlier version, and a reader that
shares nothing with the writer but the bucket.

### Reading a Delta table with DuckDB

The `delta` extension reads a Delta table from LocalS3 with the [S3 secret](#duckdb) of the DuckDB section — a Delta
table is addressed by its location, so nothing vends credentials for it:

```sql
INSTALL delta;
LOAD delta;
-- The secret local_s3 of the DuckDB section, for localhost:29090.
SELECT * FROM delta_scan('s3://delta/tables/events');
SELECT count(*) FROM delta_scan('s3://delta/tables/events') WHERE id >= 750;
```

`delta_scan` replays the `_delta_log` and reads the Parquet files it names with range requests, so a filter or an
aggregate reads the row groups it needs rather than the table. The extension is **read-only**; writing a Delta table
takes a writer such as `delta-spark` or delta-kernel.

`DuckDbDeltaIntegrationTest` writes a table with delta-kernel-java and reads it back with `delta_scan` — two clients
that share nothing but the bucket, one built on delta-kernel-rs and reaching LocalS3 with DuckDB's own HTTP client —
including a join of the table with a plain Parquet file beside it.

## Hadoop S3A

Spark, Delta Lake, Hudi and Paimon read and write `s3a://` paths through Hadoop's S3A connector, `hadoop-aws`, which
emulates a file system on the keys of a bucket. It needs nothing but path-style addressing and the endpoint:

```properties
fs.s3a.endpoint=http://localhost:29090
fs.s3a.endpoint.region=us-east-1
fs.s3a.path.style.access=true
fs.s3a.connection.ssl.enabled=false
fs.s3a.access.key=admin
fs.s3a.secret.key=admin
```

With Spark, the same as `spark.hadoop.fs.s3a.*`. The magic committer takes
`spark.hadoop.fs.s3a.committer.name=magic`, and nothing of LocalS3.

`HadoopS3AIntegrationTest` runs `S3AFileSystem` of `hadoop-aws` on the shaded Hadoop client, like Spark does:

+ **Directories**: `mkdirs` puts a marker object `dir/`, `listStatus`, `listFiles` and `getContentSummary` list with a
  delimiter, `rename` of a file and of a directory copies every object and deletes the sources, and `delete` refuses a
  directory that isn't empty unless it is recursive. S3A 3.4 and later keep the markers of directories that hold files.
+ **Large files**: a file larger than `fs.s3a.multipart.size` is a multipart upload, read back with ranged reads and a
  seek, and renamed with a multipart copy.
+ **The magic committer**: the files of a task are written as multipart uploads that no reader sees, an aborted task
  aborts its uploads, and the job commit completes the uploads of the
  committed tasks, writes `_SUCCESS` and removes its `__magic_job-*` directory.
+ **Conditional create**: `createFile(path).must("fs.option.create.conditional.overwrite", true)` is a `PUT` with
  `If-None-Match: *`, which the second writer loses, and `fs.option.create.conditional.overwrite.etag` an overwrite with
  `If-Match` that fails once another writer replaced the version that was read.

`hadoop-aws` declares the whole AWS SDK as `software.amazon.awssdk:bundle`, over 500 MB; the test excludes it for the
modules S3A uses, `s3` and `s3-transfer-manager` (and `sts` for its assumed-role credentials), which an
application may do as well.

## Apache Paimon

Paimon reaches `s3a://` through Hadoop's S3A when `hadoop-aws` is on the classpath, as it is in most Flink and Spark
deployments. A catalog passes its options that start with `hadoop.` to Hadoop, without the prefix, so it takes the
[S3A settings](#hadoop-s3a) as `hadoop.fs.s3a.*`:

```sql
-- Flink SQL
CREATE CATALOG paimon WITH (
  'type' = 'paimon',
  'warehouse' = 's3a://lake/paimon',
  'hadoop.fs.s3a.endpoint' = 'http://localhost:29090',
  'hadoop.fs.s3a.endpoint.region' = 'us-east-1',
  'hadoop.fs.s3a.path.style.access' = 'true',
  'hadoop.fs.s3a.connection.ssl.enabled' = 'false',
  'hadoop.fs.s3a.access.key' = 'admin',
  'hadoop.fs.s3a.secret.key' = 'admin'
);
```

With Spark, the same options go under `spark.sql.catalog.paimon.*`, or the S3A ones under `spark.hadoop.fs.s3a.*`.
Paimon's own `paimon-s3` jar, for `s3://`, shades S3A inside and takes `s3.endpoint`, `s3.access-key`,
`s3.secret-key` and `s3.path.style.access = true` instead.

`PaimonIntegrationTest` runs the filesystem catalog of `paimon-bundle`, without Flink or Spark, with these options: a
partitioned primary-key table is created, written in two commits, the second of them updating and deleting rows by
key, read merged and as of its first snapshot, compacted with `compact(partition, bucket, true)` — what
`CALL sys.compact` runs — into one file per bucket, its old snapshots expired, which deletes the files the compaction
replaced, and dropped.

A filesystem catalog commits a snapshot by writing a temporary file and renaming it to `snapshot-<n>`, and a rename
on S3A is a copy and a delete, not atomic. One writer per table is safe; concurrent writers of a table need a catalog
with a lock, e.g. the Hive or JDBC catalog with `lock.enabled = true`, as they do on Amazon S3.

## Apache Hudi

Hudi reaches `s3a://` through Hadoop's S3A as well, so a Spark job takes the [S3A settings](#hadoop-s3a) as
`spark.hadoop.fs.s3a.*`, and the table path is an `s3a://` URI:

```python
df.write.format("hudi") \
    .option("hoodie.table.name", "trips") \
    .option("hoodie.datasource.write.recordkey.field", "id") \
    .option("hoodie.datasource.write.partitionpath.field", "city") \
    .option("hoodie.datasource.write.table.type", "MERGE_ON_READ") \
    .mode("append").save("s3a://lake/hudi/trips")
```

`HudiIntegrationTest` runs the write client of Hudi's Java engine, `hudi-java-client`, without Spark. A copy-on-write
table takes an insert, an upsert and a delete by key, each a completed commit on the timeline under `.hoodie/`, with
the metadata table under `.hoodie/metadata/`. On a merge-on-read table the updates of an upsert go to log files that
the base files don't see, until `scheduleCompaction` and `compact` merge them into new base files and leave no log
file in the latest file slices.

Like on Amazon S3, concurrent writers of a Hudi table need optimistic concurrency control and a lock provider, which
the test doesn't cover.

## Python

The Python clients reach S3 each with an HTTP stack of its own: PyIceberg with PyArrow's S3 file system (the AWS SDK
for C++), delta-rs, Polars, Lance and LanceDB with Rust's `object_store`, s3fs and pandas with aiobotocore on
aiohttp. None of them
is the AWS SDK for Java of the other tests, so [`ceph-s3-tests/data-tools`](../ceph-s3-tests/data-tools/README.md)
runs them against the executable jar with signatures verified:

```shell
./gradlew :local-s3-standalone:jar
ceph-s3-tests/data-tools/run.sh
```

Every one of them also reads the `AWS_*` variables of the environment, so an `AWS_PROFILE` or `AWS_ENDPOINT_URL` of a
real account in the shell can win over, or add to, what the code below gives it.

### PyIceberg

The URI of the [built-in catalog](#the-built-in-iceberg-rest-catalog) is the whole configuration: the endpoint, the
path-style addressing and the credentials of the storage are vended by the catalog.

```python
import pyarrow as pa
from pyiceberg.catalog import load_catalog

catalog = load_catalog("local", type="rest", uri="http://localhost:29090/iceberg")
catalog.create_namespace("db")
table = catalog.create_table("db.events", schema=pa.schema([("id", pa.int64()), ("name", pa.string())]))
table.append(pa.table({"id": [1, 2], "name": ["a", "b"]}))
table.scan(row_filter="id > 1").to_arrow()
```

`test_pyiceberg.py` covers appends, scans with a filter, time travel, `delete` and `overwrite`, partition pruning,
schema evolution, renaming and dropping.

### delta-rs and Polars

`write_deltalake` of `deltalake` and `write_delta` of Polars take the options of `object_store`:

```python
from deltalake import DeltaTable, write_deltalake

storage_options = {
    "AWS_ENDPOINT_URL": "http://localhost:29090",
    "AWS_ACCESS_KEY_ID": "admin",
    "AWS_SECRET_ACCESS_KEY": "admin",
    "AWS_REGION": "us-east-1",
    "AWS_ALLOW_HTTP": "true",      # LocalS3 serves plain HTTP unless given a certificate
    "conditional_put": "etag",     # commit with If-None-Match: *, no lock service needed
}
write_deltalake("s3://delta/tables/events", df, storage_options=storage_options)
write_deltalake("s3://delta/tables/events", more, mode="append", storage_options=storage_options)
DeltaTable("s3://delta/tables/events", storage_options=storage_options).to_pandas()

import polars as pl
pl.read_delta("s3://delta/tables/events", storage_options=storage_options)
df.write_delta("s3://delta/tables/events", mode="append", storage_options=storage_options)
```

**The commit is a conditional `PUT`.** A Delta commit creates `_delta_log/<version>.json`, which must fail if another
writer created it first. `conditional_put = etag` makes that create a `PUT` with `If-None-Match: *`, which LocalS3,
like Amazon S3, answers with `412 Precondition Failed` when the key is taken; delta-rs then rebases a write that
doesn't conflict, e.g. an append, on the new version and commits again. delta-rs 1.x (the `object_store` 0.12 and
later under it) does this on S3 by default, so the option only makes it explicit, and nothing else is needed: no
DynamoDB lock table, which delta-rs no longer supports, and no `AWS_S3_ALLOW_UNSAFE_RENAME=true`, which lets two
concurrent writers overwrite each other's commit. The 0.x versions refuse to write to S3 without one of those
options, so keep `conditional_put` for them. `object_store` addresses buckets path-style unless
`AWS_VIRTUAL_HOSTED_STYLE_REQUEST` is `true`.

`test_delta.py` covers writes, appends, `delete`, `merge`, overwrites and time travel with both libraries, and races
four writers for one version, with `conditional_put = etag` and without it: all of them keep their rows, and LocalS3
refused the losers with `412`.

### Lance and LanceDB

Lance (`pylance`) and LanceDB take the options of `object_store`, as delta-rs does:

```python
import lance
import lancedb

storage_options = {
    "aws_endpoint": "http://localhost:29090",
    "aws_access_key_id": "admin",
    "aws_secret_access_key": "admin",
    "aws_region": "us-east-1",
    "allow_http": "true",          # LocalS3 serves plain HTTP unless given a certificate
}
lance.write_dataset(table, "s3://vectors/events.lance", storage_options=storage_options)
dataset = lance.dataset("s3://vectors/events.lance", storage_options=storage_options)
dataset.create_index("vector", index_type="IVF_PQ", num_partitions=4, num_sub_vectors=4)
dataset.to_table(nearest={"column": "vector", "q": query, "k": 5})

db = lancedb.connect("s3://vectors/lancedb", storage_options=storage_options)
db.create_table("docs", table).search(query).limit(5).to_list()
```

**The commit is a conditional `PUT`, as in delta-rs.** A version of a Lance dataset is the manifest under
`_versions/`, which Lance creates on S3 with `If-None-Match: *`; the writer that loses the race gets `412` from LocalS3
and, when its write doesn't conflict, commits again at the next version. No DynamoDB commit store is needed.

`test_lance.py` covers writes, appends, `update`, `delete`, overwrites and time travel; the compaction of five
fragments into one and the cleanup of old versions, which deletes the replaced files; an IVF_PQ index and a
nearest-neighbour search; four writers racing for one version, which all keep their rows while LocalS3 refused the
losers with `412`; and a LanceDB table created, appended to, searched, optimized and dropped.

### s3fs and pandas

pandas opens `s3://` paths with fsspec's s3fs, which takes its settings as `storage_options`:

```python
import pandas as pd

storage_options = {
    "key": "admin",
    "secret": "admin",
    "endpoint_url": "http://localhost:29090",
    "client_kwargs": {"region_name": "us-east-1"},
}
df.to_parquet("s3://demo1/events.parquet", storage_options=storage_options)
pd.read_parquet("s3://demo1/events.parquet", storage_options=storage_options)
pd.read_parquet("s3://demo1/dataset", filters=[("part", "==", "p1")], storage_options=storage_options)
```

botocore addresses buckets path-style against an `endpoint_url`, so nothing more is needed. `test_s3fs_pandas.py` covers Parquet and CSV, partitioned datasets with a filter on the partitions,
multipart uploads, range reads of row groups, `copy`, `mv`, `find`, `glob` and recursive deletes (`DeleteObjects`),
with the checksums that botocore adds to uploads by default.
