# LocalS3

[![Build](https://github.com/Robothy/local-s3/actions/workflows/build.yml/badge.svg)](https://github.com/Robothy/local-s3/actions/workflows/build.yml)
[![Apache 2.0 License](https://img.shields.io/badge/license-Apache%202.0-green.svg)](https://github.com/robothy/local-s3/blob/main/LICENSE)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.robothy/local-s3-rest.svg)](https://search.maven.org/artifact/io.github.robothy/local-s3-rest/)
[![Docker](https://img.shields.io/badge/docker-%230db7ed.svg?logo=docker&logoColor=white)](https://hub.docker.com/r/luofuxiang/local-s3)
[![codecov](https://codecov.io/gh/Robothy/local-s3/branch/main/graph/badge.svg?token=9YLOKDU03D)](https://codecov.io/gh/Robothy/local-s3)

LocalS3 is an Amazon S3 mock service for testing and local development. It is based on Netty and has no heavy
dependencies, so it starts quickly and handles requests efficiently. It also implements the Amazon S3 Vectors API.

Use it to:

+ test code that uses S3 with JUnit 5, Testcontainers or Spring Boot, without an AWS account;
+ embed an S3 server in a Java application or an IDE, e.g. as the default S3 for DuckDB;
+ give a big data platform such as Iceberg or Delta Lake a fast, local S3 to test against.

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

+ **54 S3 operations**: objects, multipart uploads, versioning, tagging, ACLs, CORS, policies, and paginated listings.
  [Supported and unsupported APIs](docs/apis.md).
+ **Browser form uploads** (`POST Object`) with policy documents and their signatures: expiration, conditions and
  `content-length-range` are checked like Amazon S3 checks them, so a frontend upload flow can be debugged locally.
  [Details](docs/semantics.md#browser-form-uploads-post-object).
+ **Lifecycle configurations are saved but not applied**: they can be put, read back and deleted, so frameworks that
  configure one on startup work, but no object ever expires or transitions. [Details](docs/semantics.md#lifecycle-configuration).
+ **S3 Vectors**: vector buckets, indexes, and similarity search.
+ **Faithful semantics**: conditional reads, writes and deletes that are atomic per key, Amazon S3 entity tags for
  multipart uploads, and the validation of Amazon S3 for bucket names and part sizes. [Semantics](docs/semantics.md).
+ **In-memory and persistence modes**, and in-memory services that start from the data of a directory, e.g. a fixture
  shared by many tests. Data directories of hundreds of thousands of objects open without loading all their metadata.
+ **AWS Signature Version 4** verification, path-style and virtual-hosted-style requests, CORS.
+ **Change listeners** that are told of every committed change, e.g. to assert that an upload happened.
+ **Health check and admin endpoints** for statistics, recent requests, and resetting a service between tests.
+ **Runs anywhere**: embedded in Java 21, JUnit 5, Spring Boot 4, Testcontainers, a Docker image (JVM or native), or an
  executable jar.

## Documentation

| Document | Contents |
|---|---|
| [Supported APIs](docs/apis.md) | The S3 and S3 Vectors operations LocalS3 implements, and the ones it answers `501 NotImplemented`. |
| [Semantics](docs/semantics.md) | Request validation, conditional requests, versioning, entity tags, browser form uploads, lifecycle configurations, and change events. |
| [Embedding](docs/embedding.md) | The Java API, Spring Boot, JUnit 5 and Testcontainers. |
| [Deployment](docs/deployment.md) | Docker, the executable jar, Kubernetes, configuration variables, persistence, health check and admin endpoints. |
| [Architecture](docs/architecture.md) | Modules, the path of a request, the `BucketGuard` concurrency model, storage layers and the data directory layout. |
| [Changelog](CHANGELOG.md) | Changes per release, and how to upgrade, e.g. the new data directory format of 2.5. |
| [Contributing](CONTRIBUTING.md) | Building, testing, and conventions for changes. |

## References

* [H2 MVStore](https://h2database.com/html/mvstore.html): a persistent, log structured key-value store
* S3 compatibility tests: https://github.com/ceph/s3-tests
