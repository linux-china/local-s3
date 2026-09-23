# LocalS3

[![Build](https://github.com/Robothy/local-s3/actions/workflows/build.yml/badge.svg)](https://github.com/Robothy/local-s3/actions/workflows/build.yml)
[![Apache 2.0 License](https://img.shields.io/badge/license-Apache%202.0-green.svg)](https://github.com/robothy/local-s3/blob/main/LICENSE)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.robothy/local-s3-rest.svg)](https://search.maven.org/artifact/io.github.robothy/local-s3-rest/)
[![Docker](https://img.shields.io/badge/docker-%230db7ed.svg?logo=docker&logoColor=white)](https://hub.docker.com/r/luofuxiang/local-s3)
[![codecov](https://codecov.io/gh/Robothy/local-s3/branch/main/graph/badge.svg?token=9YLOKDU03D)](https://codecov.io/gh/Robothy/local-s3)

LocalS3 is an Amazon S3 mock service for testing and local development. It is based on Netty and has no heavy
dependencies, so it starts quickly and handles requests efficiently. It also implements the Amazon S3 Vectors and
Amazon S3 Tables APIs.

Use it to:

+ test code that uses S3 with JUnit 5, Testcontainers or Spring Boot, without an AWS account;
+ embed an S3 server in a Java application or an IDE, e.g. as the default S3 for DuckDB;
+ give a big data platform such as Iceberg or Delta Lake a fast, local S3 to test against — with an
  [Iceberg REST catalog](docs/data-tools.md#the-built-in-iceberg-rest-catalog) and
  [S3 Tables](docs/data-tools.md#amazon-s3-tables) built in, so no separate catalog is needed.

Weighing it against Adobe S3Mock, s3proxy, MinIO or LocalStack? See
[Choosing an S3 mock](docs/comparison.md).

## Quick start

**Docker**

```shell
docker run -d -p 29090:29090 -e LOCAL_S3_MODE=IN_MEMORY -e AWS_BUCKETS=my-bucket luofuxiang/local-s3
AWS_ACCESS_KEY_ID=any AWS_SECRET_ACCESS_KEY=any AWS_REGION=us-east-1 \
    aws --endpoint-url http://localhost:29090 s3 cp README.md s3://my-bucket/
```

**JUnit 5**

```xml
<dependency>
    <groupId>io.github.robothy</groupId>
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
    s3.createBucket(b -> b.bucket("my-bucket"));
  }
}
```

**Java**

```java
try (LocalS3 localS3 = LocalS3.builder().port(29090).build()) {
  localS3.start();
  // point an S3 client at http://localhost:29090, with path-style access
}
```

## Features

+ **70+ S3 operations**: objects, multipart uploads, versioning, tagging, ACLs, CORS, policies, and paginated listings.
  [Supported and unsupported APIs](docs/apis.md).
+ **Browser form uploads** (`POST Object`) with policy documents and their signatures: expiration, conditions and
  `content-length-range` are checked like Amazon S3 checks them, so a frontend upload flow can be debugged locally.
  [Details](docs/semantics.md#browser-form-uploads-post-object).
+ **Lifecycle configurations are saved but not applied**: they can be put, read back and deleted, so frameworks that
  configure one on startup work, but no object ever expires or transitions. [Details](docs/semantics.md#lifecycle-configuration).
+ **A built-in Iceberg REST catalog**, off by default, served under `/iceberg/v1` on the same port: a lakehouse test
  needs one process rather than a catalog beside the object store. The tables are stored in LocalS3 itself, and the
  catalog vends the endpoint and credentials to reach it, so a client configured with the catalog URI alone works. It is
  verified against `CatalogTests` and `ViewCatalogTests`, the suites Apache Iceberg checks a catalog implementation with.
  [Details](docs/data-tools.md#the-built-in-iceberg-rest-catalog).
+ **Delta Lake**: the conditional write that the Delta commit protocol rests on, covered end to end with
  `delta-kernel-java` — create, write, read, concurrent commits and time travel.
  [Details](docs/data-tools.md#delta-lake).
+ **Static website hosting** on the same port: a public bucket is served to a browser as a site, with index and error
  documents, directory redirects and routing rules, while the signed requests of an S3 client keep their S3 semantics.
  A file stored without a content type gets the one of its extension, so a directory copied into a bucket just works.
  [Details](docs/semantics.md#static-website-hosting).
+ **Amazon S3 Tables**, always on: table buckets, their namespaces and tables, and the commits that move a table from
  one metadata file to the next — the API that AWS's managed Iceberg is reached through, and that the
  `s3-tables-catalog` library of Iceberg speaks. Every table bucket is also served as an Iceberg REST catalog of its
  own, so Spark, Trino or PyIceberg reaches the same tables by naming the table bucket's ARN as its warehouse, exactly
  as it would against AWS. [Details](docs/data-tools.md#amazon-s3-tables).
+ **S3 Vectors**: vector buckets, indexes, and similarity search.
+ **Faithful semantics**: conditional reads, writes and deletes that are atomic per key, Amazon S3 entity tags for
  multipart uploads, and the validation of Amazon S3 for bucket names and part sizes. [Semantics](docs/semantics.md).
+ **In-memory and persistence modes**, and in-memory services that start from the data of a directory, e.g. a fixture
  shared by many tests. Data directories of hundreds of thousands of objects open without loading all their metadata.
+ **AWS Signature Version 4** verification, path-style and virtual-hosted-style requests, CORS.
+ **HTTPS** with a self-signed certificate that LocalS3 generates on startup, or one of
  [mkcert](https://github.com/FiloSottile/mkcert), for clients that require TLS, e.g. DuckDB, Hadoop S3A and Snowflake.
  The same port keeps answering plain HTTP, so TLS clients and plain ones share one endpoint.
  [Details](docs/deployment.md#https).
+ **A stateless STS endpoint** (`AssumeRole`, `GetSessionToken`, `GetCallerIdentity`), so Iceberg REST catalogs that
  vend temporary credentials, and the engines that use them, work with LocalS3.
  [Details](docs/embedding.md#temporary-credentials-sts).
+ **A stateless KMS endpoint** (`GenerateDataKey`, `Encrypt`, `Decrypt`, `DescribeKey`), so clients that wrap data keys
  with KMS, e.g. the Amazon S3 Encryption Client, run against LocalS3. Nothing is really encrypted.
  [Details](docs/embedding.md#envelope-encryption-kms).
+ **Change listeners** that are told of every committed change, e.g. to assert that an upload happened.
+ **A built-in console** at `/_admin/ui`: one self-contained HTML page, no build step and no new dependency, that
  lists the buckets and creates one, walks a bucket by its prefixes, previews or downloads an object, and uploads
  files and folders by dropping them on the page or deletes one — so what an embedded service or an AI agent put in
  there can be looked at and changed, rather than listed with `aws s3 ls`. Guarded with HTTP Basic authentication when
  credentials are configured. [Details](docs/deployment.md#console).
+ **Health check and admin endpoints** for statistics, recent requests, and resetting a service between tests.
+ **Runs anywhere**: embedded in Java 21, JUnit 5, Spring Boot 3 and 4, Testcontainers, a Docker image (JVM or native), or an
  executable jar.

## Documentation

| Document | Contents |
|---|---|
| [Choosing an S3 mock](docs/comparison.md) | Where LocalS3 fits next to Adobe S3Mock, s3proxy, MinIO and LocalStack, and when one of them is the better choice. |
| [Supported APIs](docs/apis.md) | The S3, S3 Vectors and S3 Tables operations LocalS3 implements, and the ones it answers `501 NotImplemented`. |
| [Semantics](docs/semantics.md) | Request validation, conditional requests, versioning, entity tags, browser form uploads, lifecycle configurations, and change events. |
| [Embedding](docs/embedding.md) | The Java API, Spring Boot, JUnit 5 and Testcontainers. |
| [Data tools](docs/data-tools.md) | DuckDB, DuckLake, Apache Iceberg and Delta Lake on LocalS3, the built-in Iceberg REST catalog, and Amazon S3 Tables. |
| [Deployment](docs/deployment.md) | Docker, the executable jar, Kubernetes, configuration variables, persistence, health check, the console and admin endpoints. |
| [Architecture](docs/architecture.md) | Modules, the path of a request, the `BucketGuard` concurrency model, storage layers and the data directory layout. |
| [Changelog](CHANGELOG.md) | Changes per release, and how to upgrade, e.g. the new data directory format of 2.5. |

## References

* [H2 MVStore](https://h2database.com/html/mvstore.html): a persistent, log structured key-value store
* S3 compatibility tests: https://github.com/ceph/s3-tests
* [warp](https://github.com/minio/warp): S3 benchmarking tool
* [s5cmd](https://github.com/peak/s5cmd): Parallel S3 and local filesystem execution tool.
