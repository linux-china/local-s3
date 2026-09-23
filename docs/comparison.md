# Choosing an S3 mock

Several projects answer the S3 API without AWS, and they are built for different jobs. This page says what LocalS3 is
good at, and — just as usefully — when one of the others is the better choice, so that the decision doesn't need a
weekend of trials.

The facts about the other projects are the stable ones: what the project is for, what it runs on, how it is licensed
and how it is deployed. Feature-by-feature claims about someone else's software go stale, so check the current
documentation of whichever you shortlist; the links are in the table. This page was last checked on **2026-09-19**.

- [The short answer](#the-short-answer)
- [What the projects are](#what-the-projects-are)
- [Where LocalS3 differs](#where-locals3-differs)
- [Where LocalS3 is not the answer](#where-locals3-is-not-the-answer)

## The short answer

| If you… | Pick |
|---|---|
| test JVM code against S3, or embed an S3 server in a Java application or an IDE | **LocalS3** |
| test a lakehouse — Iceberg, Delta Lake, DuckLake, DuckDB — and don't want a catalog process beside the object store | **LocalS3**, which serves an [Iceberg REST catalog](data-tools.md#the-built-in-iceberg-rest-catalog) on the same port |
| test code that uses the S3 Vectors API | **LocalS3**, which implements it |
| need durable storage for something real: production, staging, a shared dev environment | **MinIO**, or Amazon S3 itself |
| need more of AWS than S3 — SQS, DynamoDB, Lambda, CloudFormation | **LocalStack** |
| want to put an S3 API in front of a store you already have (a filesystem, Azure Blob, GCS) | **s3proxy** |
| test from Python, Node or Go and only want a container to run | any of them; LocalS3's [Docker image](deployment.md#docker) works, but its integrations are JVM-side |

## What the projects are

| Project | What it is | Runtime | License | How it runs |
|---|---|---|---|---|
| [LocalS3](https://github.com/Robothy/local-s3) | An S3 mock for testing and local development, made to be embedded in a JVM | Java 21, Netty | Apache 2.0 | Java API, JUnit 5, Spring Boot 3 and 4, Testcontainers, Docker (JVM or GraalVM native), executable jar |
| [Adobe S3Mock](https://github.com/adobe/S3Mock) | An S3 mock for testing | Java, Spring Boot | Apache 2.0 | JUnit 4 and 5, Testcontainers, Docker |
| [s3proxy](https://github.com/gaul/s3proxy) | An S3 front end over other blob stores — filesystem, in-memory, Azure Blob, Google Cloud Storage and more — through Apache jclouds | Java | Apache 2.0 | Executable jar, Docker, embeddable |
| [MinIO](https://github.com/minio/minio) | A production object storage server that speaks S3 | Go | AGPL-3.0 | Single binary, Docker, Kubernetes |
| [LocalStack](https://github.com/localstack/localstack) | An emulator for a large part of AWS, with S3 as one of its services | Python | Community edition is free; see the project for the current terms | Docker, Testcontainers |

The licenses matter for more than compliance paperwork. LocalS3, S3Mock and s3proxy are Apache 2.0, so they can be a
dependency of a closed-source product. MinIO is AGPL-3.0, which is fine for a container a test starts and stops, and a
question worth asking a lawyer if it would ship inside something.

## Where LocalS3 differs

### One process for a lakehouse test

An Iceberg test normally needs two things running: an object store, and a catalog — Polaris, Lakekeeper, Nessie or a
JDBC catalog on a database. LocalS3 serves an [Iceberg REST catalog](data-tools.md#the-built-in-iceberg-rest-catalog)
under `/iceberg/v1` on the same port as the S3 API, off by default:

```java
LocalS3 s3 = LocalS3.builder().port(29090).icebergCatalog(true).build();
s3.start();

RESTCatalog catalog = new RESTCatalog();
catalog.initialize("local", Map.of("uri", "http://localhost:29090/iceberg"));
catalog.createNamespace(Namespace.of("db"));
```

The tables live in LocalS3 itself, so an `IN_MEMORY` service holds them in memory and a `PERSISTENCE` service writes
them to its data directory, and `loadTable` vends the endpoint, path-style setting and credentials of the service, so
an engine configured with the catalog URI alone reaches the storage too. Namespaces, tables, views, commits and
multi-table transactions are served, and a commit moves the table pointer with a compare-and-set, so a writer that
loses a race gets `409 CommitFailedException` and its client retries rather than losing rows.

[Delta Lake](data-tools.md#delta-lake) rests on a different mechanism — `If-None-Match: *` creating
`_delta_log/<version>.json` only when no other writer did — and that conditional write, including the `412` the losing
writer gets, is covered end to end against `delta-kernel-java`.

### S3 Vectors

LocalS3 implements the [Amazon S3 Vectors API](apis.md#supported-amazon-s3-vectors-apis): vector buckets, indexes,
`PutVectors` / `QueryVectors` / `GetVectors` / `ListVectors` / `DeleteVectors`, vector bucket policies and tagging —
19 operations. Code that stores embeddings in S3 Vectors can be tested without an AWS account.

### Persistence, initial data and seeders

Three different things, and tests use all three:

+ **`IN_MEMORY`**, the default: nothing touches the disk. The content is bounded — half the max heap by default — so a
  test that writes large Parquet files gets `507 InsufficientStorage` instead of taking the JVM that embeds the service
  down with an `OutOfMemoryError`.
+ **`PERSISTENCE`**: metadata in an [H2 MVStore](https://h2database.com/html/mvstore.html) file, object content in a
  storage directory, surviving a restart. `PersistencePolicy.FAST` trades the changes of the last second for about half
  the write time and a hundredth of the writes on a bulk load, which is the trade a data directory built for a test
  can usually make.
+ **A data path without `PERSISTENCE`** is *initial data*: the service starts from what the directory holds and never
  writes back to it, so a fixture directory can be shared by many tests. `localS3.reset()` returns a service to it, far
  quicker than restarting. Directories of hundreds of thousands of objects open without loading all their metadata.

Where the fixtures are code rather than a directory, [`seeder(...)`](embedding.md#seed-the-initial-buckets-and-objects)
puts buckets and objects into the service before it accepts the first request, and again after every reset:

```java
LocalS3.builder()
    .seeder(fixtures -> {
      fixtures.object("uploads", "hello.txt", "Hello!".getBytes(UTF_8));
      fixtures.object("uploads", "images/logo.png", Path.of("src/test/resources/logo.png"));
    })
    .build();
```

### Startup cost

A mock that a test class starts is on the critical path of every build, so the numbers are worth measuring rather than
asserting. On an Apple M5 Max with Temurin 21.0.9, with the standalone
[executable jar](deployment.md#executable-jar) (10 MB) in `IN_MEMORY` mode:

| What | Measured |
|---|---|
| `java -jar local-s3-standalone.jar` to "LocalS3 started", JVM boot included | ~275 ms |
| The first embedded service in a JVM that is already running (`LocalS3.builder().port(0).build().start()`) | ~200 ms, mostly class loading |
| Every further service in that JVM | 1–2 ms |

That last row is the one that decides a build's length: a test class per service, or a service per test method, costs
milliseconds. `localS3.reset()` is cheaper still where one service is shared.

The reason is the dependency list rather than any trick — `local-s3-rest` pulls in three Netty modules, Jackson,
`netty-http-router`, SLF4J and JSpecify, and no application framework:

```
+--- io.netty:netty-codec-http          +--- tools.jackson.core:jackson-databind
+--- io.netty:netty-handler             +--- io.github.robothy:netty-http-router
+--- io.netty:netty-transport           +--- org.slf4j:slf4j-api
+--- tools.jackson.dataformat:jackson-dataformat-xml   \--- org.jspecify:jspecify
```

A container-based mock pays Docker's startup instead, typically seconds for the first container in a build. LocalS3
has a [Testcontainers module](embedding.md#testcontainers) for when a container is what you want — a test of a
non-JVM process, or isolation from the test JVM — but it is the option rather than the only way in.

### Depth of the JVM integrations

**JUnit 5** — `@LocalS3` on a test class starts one service shared by its methods and its `@Nested` classes; on a test
method, one service of its own. It injects a configured `S3Client`, `S3VectorsClient` or `LocalS3Endpoint` into tests
and lifecycle methods, and `accessKey` with `secretKey` turns on signature verification and signs the injected clients
with the same pair. See [JUnit 5](embedding.md#junit-5).

**Spring Boot** — one artifact covers Spring Boot 3 and 4, and the starter defines rather more than a bean:

+ a `LocalS3` bean configured by `local-s3.*` properties that map to the builder options, with
  `LocalS3BuilderCustomizer` beans for the rest;
+ `S3Client`, `S3AsyncClient` and `S3Presigner` beans pointed at the service, which back off if the application
  defines its own, and which start the service when created, so a `@PostConstruct` can use one even with a random port;
+ `local-s3.seed.classpath` and `LocalS3Seeder` beans for the initial data;
+ every committed `S3Change` published as an application event, on the thread that made the change, so a
  `@TransactionalEventListener` sees it when the transaction commits;
+ an Actuator health indicator, and Micrometer timers and gauges for requests, objects and bytes;
+ `local-s3.enabled=false` to leave the whole thing out, which is how the same application runs against Amazon S3 in
  production.

See the [starter's README](../local-s3-spring-boot-starter/README.md).

### The endpoints around S3

Clients rarely talk to S3 alone. LocalS3 answers, on the same port:

+ a [stateless STS endpoint](embedding.md#temporary-credentials-sts) — `AssumeRole`, `GetSessionToken`,
  `GetCallerIdentity` — so Iceberg REST catalogs that vend temporary credentials, and the engines that use them, work;
+ a [stateless KMS endpoint](embedding.md#envelope-encryption-kms) — `GenerateDataKey`, `Encrypt`, `Decrypt`,
  `DescribeKey` — so a client that wraps data keys with KMS, e.g. the Amazon S3 Encryption Client, runs through. Nothing
  is really encrypted;
+ [static website hosting](semantics.md#static-website-hosting) for unsigned requests, with index and error documents,
  directory redirects and routing rules, while an S3 client's signed requests keep their S3 semantics;
+ [HTTPS](deployment.md#https) with a certificate LocalS3 generates on startup, or one of mkcert, for clients that
  insist on TLS such as DuckDB — on the same port as plain HTTP, so both kinds of client
  share one endpoint;
+ a [built-in console](deployment.md#console) at `/_admin/ui` — one self-contained HTML page that lists the buckets
  and creates one, walks a bucket by its prefixes, previews or downloads an object, and uploads a file dropped on it
  or deletes one, so what is in the service can be looked at and changed rather than listed with `aws s3 ls`. It is
  far less than the MinIO console, and it is the thing one usually opens it for;
+ [health check and admin endpoints](deployment.md#health-check) for statistics, recent requests and resetting a
  service between tests.

## Where LocalS3 is not the answer

**It is a mock, not storage.** No erasure coding, no replication, no multi-node anything. A `PERSISTENCE` service
survives a restart and is the right tool for a local development environment; it is not where production data belongs.

**It is not all of AWS.** If the test needs SQS beside S3, LocalStack emulates both and LocalS3 emulates one.

**Some configuration is stored rather than acted on.** Bucket lifecycle configurations are
[saved but never applied](semantics.md#lifecycle-configuration): a framework that configures one on startup works, and
no object ever expires. Server-side encryption headers and storage classes are accepted and recorded, and nothing is
encrypted or tiered. Bucket notification configurations are stored, and no notification is delivered — use
[change listeners](embedding.md#listen-to-bucket-and-object-changes) instead, which are told of every committed change.

**Some operations answer `501`.** Analytics, inventory, metrics and intelligent-tiering configurations,
`SelectObjectContent`, `RestoreObject`, `GetObjectTorrent` and `WriteGetObjectResponse` are routed and answer
`501 NotImplemented` with an error naming the operation, so a client fails clearly instead of appearing to succeed. The
full lists, checked against the router by a test so they can't drift, are in [apis.md](apis.md).

**Its integrations are JVM-side.** The Docker image serves any client in any language, but the parts that make LocalS3
pleasant — `@LocalS3`, the Spring Boot starter, seeders, change listeners, `getS3Manager()` — are Java. A Python or Go
project gets a container, which several other projects also provide.
