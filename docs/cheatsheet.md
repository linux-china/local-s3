# Cheat sheet

The least configuration that gets each kind of client talking to LocalS3, with one snippet you can run. Each section
links to the reference page with every option and every caveat.

The snippets assume a LocalS3 on `localhost:29090`, plain HTTP, and the key pair `admin` / `admin`. A LocalS3 started
without credentials accepts any key pair, so the snippets work against it unchanged.

- [Start LocalS3](#start-locals3)
- [AWS CLI and s5cmd](#aws-cli-and-s5cmd)
- [DuckDB](#duckdb)
- [JUnit 5](#junit-5)
- [Spring Boot](#spring-boot)
- [Testcontainers](#testcontainers)
- [Iceberg with Spark](#iceberg-with-spark)
- [PyIceberg](#pyiceberg)
- [delta-rs and Polars](#delta-rs-and-polars)
- [Common errors](#common-errors)

## Start LocalS3

```shell
docker run -d -p 29090:29090 -e LOCAL_S3_MODE=IN_MEMORY -e AWS_BUCKETS=demo linuxchina/local-s3
```

| Add | For |
|---|---|
| `-e LOCAL_S3_ICEBERG_CATALOG=true` | the [Iceberg REST catalog](data-tools.md#the-built-in-iceberg-rest-catalog) at `/iceberg` |
| `-e LOCAL_S3_TLS_SELF_SIGNED=true` | [HTTPS](deployment.md#https) on the same port, for clients that insist on TLS |
| `-e LOCAL_S3_ACCESS_KEY_ID=admin -e LOCAL_S3_SECRET_ACCESS_KEY=admin` | requiring signed requests |
| `-e LOCAL_S3_MODE=PERSISTENCE -v $PWD/data:/data` | data that survives a restart; this is the default of the image |

Without Docker: `java -jar s3.jar --port 29090 --buckets demo`. See [Deployment](deployment.md#configuration) for every
variable.

The console at `http://localhost:29090/_admin/ui` shows what is stored. Under `Connect` it shows the snippets below,
filled in for this service.

## AWS CLI and s5cmd

```shell
eval "$(curl -s http://localhost:29090/_admin/snippets/env)"
aws s3 cp README.md s3://demo/
s5cmd ls 's3://demo/*'
```

The snippet exports `AWS_ENDPOINT_URL`, the region and the credentials of the service. To set them by hand:
`aws --endpoint-url http://localhost:29090 ...` and `s5cmd --endpoint-url http://localhost:29090 ...`, with any
`AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` and `AWS_REGION`.

## DuckDB

One line, with the secret written by the service itself:

```shell
duckdb -init <(curl -s http://localhost:29090/_admin/snippets/duckdb)
```

Or by hand. `USE_SSL false` is the line people forget, because DuckDB uses HTTPS by default:

```sql
CREATE SECRET local_s3 (TYPE s3, ENDPOINT 'localhost:29090', URL_STYLE 'path', USE_SSL false,
    KEY_ID 'admin', SECRET 'admin', REGION 'us-east-1');

COPY (SELECT * FROM range(1000)) TO 's3://demo/numbers.parquet';
SELECT count(*) FROM 's3://demo/*.parquet';
```

Reference: [DuckDB](data-tools.md#duckdb), [DuckLake](data-tools.md#ducklake),
[DuckDB on the built-in Iceberg catalog](data-tools.md#duckdb-on-the-built-in-catalog).

## JUnit 5

```xml
<dependency>
    <groupId>org.mvnsearch</groupId>
    <artifactId>local-s3-jupiter</artifactId>
    <version>last_version</version>
    <scope>test</scope>
</dependency>
```

```java
@LocalS3
class AppTest {

  @Test
  void test(S3Client s3) {
    s3.createBucket(b -> b.bucket("demo"));
  }
}
```

No Docker is needed: the service runs in the test JVM, one per annotated class or method. `@LocalS3(icebergCatalog = true)` also
serves the Iceberg catalog, and `LocalS3Endpoint` can be injected for its URIs. Reference: [JUnit 5](embedding.md#junit-5).

## Spring Boot

Only the dependency, for tests and local runs, so that the production jar leaves it out:

```groovy
dependencies {
    testAndDevelopmentOnly 'org.mvnsearch:local-s3-spring-boot-starter:last_version'
    implementation 'software.amazon.awssdk:s3'   // for the S3Client bean
}
```

```yaml
local-s3:
  buckets: [uploads]
```

```java
@Service
class Uploads {

  private final S3Client s3;   // points at the embedded LocalS3

  Uploads(S3Client s3) {
    this.s3 = s3;
  }

  @EventListener
  void onChange(S3Change change) {   // every committed change, e.g. an upload by another process
    // ...
  }
}
```

In tests, `@SpringBootTest @AutoConfigureLocalS3` uses a random port and resets the data after each test method.
Reference: [the starter's README](../local-s3-spring-boot-starter/README.md),
[Spring Boot](embedding.md#spring-boot).

## Testcontainers

```java
@Container
static LocalS3Container localS3 = new LocalS3Container("latest").withBuckets("demo");

S3Client s3 = S3Client.builder()
    .endpointOverride(localS3.getEndpointUri())
    .forcePathStyle(true)
    .region(Region.US_EAST_1)
    .credentialsProvider(StaticCredentialsProvider.create(
        AwsBasicCredentials.create(localS3.getAccessKey(), localS3.getSecretKey())))
    .build();
```

With Spring Boot and Spring Cloud AWS, `@Bean @ServiceConnection LocalS3Container` is the whole configuration.
Reference: [Testcontainers](embedding.md#testcontainers),
[`@ServiceConnection`](embedding.md#serviceconnection-with-spring-cloud-aws).

## Iceberg with Spark

Start LocalS3 with `LOCAL_S3_ICEBERG_CATALOG=true`. The catalog URI is then the whole configuration: the catalog
vends the S3 endpoint, path-style access and the credentials.

```shell
spark-sql \
  --packages org.apache.iceberg:iceberg-spark-runtime-3.5_2.12:1.10.0,org.apache.iceberg:iceberg-aws-bundle:1.10.0 \
  --conf spark.sql.extensions=org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions \
  --conf spark.sql.catalog.local=org.apache.iceberg.spark.SparkCatalog \
  --conf spark.sql.catalog.local.type=rest \
  --conf spark.sql.catalog.local.uri=http://localhost:29090/iceberg \
  --conf spark.sql.defaultCatalog=local
```

```sql
CREATE NAMESPACE db;
CREATE TABLE db.events (id BIGINT, name STRING) USING iceberg;
INSERT INTO db.events VALUES (1, 'a'), (2, 'b');
SELECT * FROM db.events;
```

The Iceberg catalog is new in 2.5.0. Until that is released, build the image from this repository, as
[`examples/lakehouse`](../examples/lakehouse/README.md) does; that example runs Spark and Trino on the same tables with
`docker compose up`. Reference: [the built-in Iceberg REST catalog](data-tools.md#the-built-in-iceberg-rest-catalog),
[Apache Iceberg](data-tools.md#apache-iceberg) for a catalog of your own.

## PyIceberg

With the Iceberg catalog on, as above, and `pip install "pyiceberg[pyarrow]"`:

```python
import pyarrow as pa
from pyiceberg.catalog import load_catalog

catalog = load_catalog("local", type="rest", uri="http://localhost:29090/iceberg")
catalog.create_namespace("db")
table = catalog.create_table("db.events", schema=pa.schema([("id", pa.int64()), ("name", pa.string())]))
table.append(pa.table({"id": [1, 2], "name": ["a", "b"]}))
print(table.scan(row_filter="id > 1").to_arrow())
```

Reference: [PyIceberg](data-tools.md#pyiceberg).

## delta-rs and Polars

With `pip install deltalake polars`, and the bucket `delta` created first, e.g. with `AWS_BUCKETS=delta`:

```python
import polars as pl
from deltalake import DeltaTable, write_deltalake

storage_options = {
    "AWS_ENDPOINT_URL": "http://localhost:29090",
    "AWS_ACCESS_KEY_ID": "admin",
    "AWS_SECRET_ACCESS_KEY": "admin",
    "AWS_REGION": "us-east-1",
    "AWS_ALLOW_HTTP": "true",
    "conditional_put": "etag",
}
df = pl.DataFrame({"id": [1, 2], "name": ["a", "b"]})
write_deltalake("s3://delta/events", df.to_arrow(), storage_options=storage_options)
print(DeltaTable("s3://delta/events", storage_options=storage_options).to_pandas())
print(pl.read_delta("s3://delta/events", storage_options=storage_options))
```

`conditional_put = etag` makes each commit a `PUT` with `If-None-Match: *`, so no lock table is needed. Reference:
[delta-rs and Polars](data-tools.md#delta-rs-and-polars), [Delta Lake](data-tools.md#delta-lake) for Spark and
delta-kernel.

## Common errors

| Error | Cause | Fix |
|---|---|---|
| DuckDB `SSL connect error ... https://localhost:29090/...` | DuckDB uses HTTPS by default | `USE_SSL false` in the secret, or serve [HTTPS](deployment.md#https) |
| `NoSuchBucket` on the first write, e.g. of DuckLake or delta-rs | The bucket doesn't exist | `AWS_BUCKETS=...`, `local-s3.buckets` or `aws s3 mb` |
| `InvalidBucketName` | Bucket names have 3 to 63 characters, like on Amazon S3 | Use a longer name |
| The host `demo.<endpoint>` can't be resolved or connected to | The client sends virtual-hosted-style requests, which only work for `localhost` and the configured virtual host domains | Path-style: `URL_STYLE 'path'`, `forcePathStyle(true)`, `s3.path-style-access=true`; or `LOCAL_S3_VIRTUAL_HOST_DOMAINS` |
| `403 RequestTimeTooSkewed` | The clock of the client is more than 15 minutes off | `LOCAL_S3_ALLOWED_CLOCK_SKEW=1h`, or `0` to turn the check off |
| `507 InsufficientStorage` | `IN_MEMORY` mode holds at most a quarter of the heap by default | `LOCAL_S3_IN_MEMORY_MAX_BYTES`, or `PERSISTENCE` mode |
| `501 NotImplemented` | The operation isn't implemented | See [Supported APIs](apis.md); `GET /_admin/stats` counts them under `notImplemented` |
