# LocalS3

[![Build](https://github.com/Robothy/local-s3/actions/workflows/build.yml/badge.svg)](https://github.com/Robothy/local-s3/actions/workflows/build.yml)
[![Apache 2.0 License](https://img.shields.io/badge/license-Apache%202.0-green.svg)](https://github.com/robothy/local-s3/blob/main/LICENSE)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.robothy/local-s3-rest.svg)](https://search.maven.org/artifact/io.github.robothy/local-s3-rest/)
[![Docker](https://img.shields.io/badge/docker-%230db7ed.svg?logo=docker&logoColor=white)](https://hub.docker.com/r/luofuxiang/local-s3)
[![codecov](https://codecov.io/gh/Robothy/local-s3/branch/main/graph/badge.svg?token=9YLOKDU03D)](https://codecov.io/gh/Robothy/local-s3)

LocalS3 is an Amazon S3 mock service for testing and local development. LocalS3 is based on Netty
and without heavy dependencies, it starts up quickly and handles requests efficiently.

<details>
<summary><b>Supported Amazon S3 APIs</b></summary>

+ AbortMultipartUpload
+ CopyObject
+ CreateBucket
+ CreateMultipartUpload
+ CompleteMultipartUpload
+ DeleteBucket
+ DeleteBucketCors
+ DeleteBucketEncryption
+ DeleteBucketPolicy
+ DeleteBucketReplication
+ DeleteBucketTagging
+ DeleteObject
+ DeleteObjects
+ DeleteObjectTagging
+ GetObject
+ GetObjectTagging
+ GetObjectAcl
+ PutObjectAcl
+ GetObjectAttributes
+ GetBucketAcl
+ GetBucketCors
+ GetBucketEncryption
+ GetBucketPolicy
+ GetBucketPolicyStatus
+ GetBucketReplication
+ GetBucketVersioning
+ GetBucketTagging
+ GetBucketLocation
+ HeadBucket
+ HeadObject
+ ListBuckets
+ ListObjects
+ ListObjectsV2
+ ListObjectVersions
+ ListMultipartUploads
+ ListParts
+ PutBucketAcl
+ PutBucketCors
+ PutBucketEncryption
+ PutBucketPolicy
+ PutBucketReplication
+ PutBucketVersioning
+ PutBucketTagging
+ PutObject
+ PutObjectTagging
+ UploadPart
+ UploadPartCopy
+ PutPublicAccessBlock
+ GetPublicAccessBlock
+ DeletePublicAccessBlock
+ OPTIONS object (CORS preflight requests, answered without authentication)
</details>

<details>
<summary><b>Supported Amazon S3 Vectors APIs</b></summary>

**Vector Bucket Operations:**
+ CreateVectorBucket
+ GetVectorBucket
+ ListVectorBuckets
+ DeleteVectorBucket

**Vector Index Operations:**
+ CreateIndex
+ GetIndex
+ ListIndexes
+ DeleteIndex

**Vector Data Operations:**
+ PutVectors
+ QueryVectors
+ GetVectors
+ ListVectors
+ DeleteVectors

**Vector Bucket Policy Operations:**
+ PutVectorBucketPolicy
+ GetVectorBucketPolicy
+ DeleteVectorBucketPolicy
</details>

<details>
<summary><b>Known unimplemented Amazon S3 APIs</b></summary>

LocalS3 is a mock for testing, so it implements the operations that application code exercises and leaves
the ones that configure a real bucket's lifecycle, billing and reporting alone. The operations below are
routed and answer `501 NotImplemented` with an `<Error>` document naming the operation, so a client fails
with a clear error instead of appearing to succeed. If your tests need one of them, please
[open an issue](https://github.com/Robothy/local-s3/issues/new).

**Lifecycle**
+ DeleteBucketLifecycle
+ GetBucketLifecycleConfiguration
+ PutBucketLifecycleConfiguration

(The deprecated `GetBucketLifecycle` and `PutBucketLifecycle` send the same requests, and get the same answer.)

**Event notifications**
+ GetBucketNotificationConfiguration
+ PutBucketNotificationConfiguration

(The deprecated `GetBucketNotification` and `PutBucketNotification` send the same requests, and get the same
answer. LocalS3 has its own listener API instead; see [Listen to bucket and object changes](#listen-to-bucket-and-object-changes).)

**Object Lock and retention**
+ GetObjectLegalHold
+ GetObjectLockConfiguration
+ GetObjectRetention
+ PutObjectLegalHold
+ PutObjectLockConfiguration
+ PutObjectRetention

**Object retrieval and transformation**
+ GetObjectTorrent
+ RestoreObject
+ SelectObjectContent
+ WriteGetObjectResponse

**Static website hosting**
+ DeleteBucketWebsite
+ GetBucketWebsite
+ PutBucketWebsite

**Access logging**
+ GetBucketLogging
+ PutBucketLogging

**Requester pays**
+ GetBucketRequestPayment
+ PutBucketRequestPayment

**Transfer acceleration**
+ GetBucketAccelerateConfiguration
+ PutBucketAccelerateConfiguration

**Ownership controls**
+ DeleteBucketOwnershipControls
+ GetBucketOwnershipControls
+ PutBucketOwnershipControls

**Analytics, inventory and metrics**
+ DeleteBucketAnalyticsConfiguration
+ DeleteBucketInventoryConfiguration
+ DeleteBucketMetricsConfiguration
+ GetBucketAnalyticsConfiguration
+ GetBucketInventoryConfiguration
+ GetBucketMetricsConfiguration
+ ListBucketAnalyticsConfigurations
+ ListBucketInventoryConfigurations
+ ListBucketMetricsConfigurations
+ PutBucketAnalyticsConfiguration
+ PutBucketInventoryConfiguration
+ PutBucketMetricsConfiguration

**Intelligent tiering**
+ DeleteBucketIntelligentTieringConfiguration
+ GetBucketIntelligentTieringConfiguration
+ ListBucketIntelligentTieringConfigurations
+ PutBucketIntelligentTieringConfiguration

**Not routed at all**

`POST Object`, the browser form upload (`multipart/form-data` to the bucket, with its base64 policy
document and signature), has no route, so it answers `501 NotImplemented` from the fallback handler:
`LocalS3 does not implement POST /<bucket>.` Testing a browser upload flow against LocalS3 therefore needs
presigned `PUT` instead.

Every other operation of the S3 API that isn't listed in this section or in the supported ones above is
also unrouted and answers the same way.

</details>


## Features

+ Support S3 object versioning.
+ Support S3 Vectors for vector storage and similarity search.
+ Support conditional requests and conditional writes (see below).
+ Support paginated `ListBuckets` with `max-buckets`, `continuation-token`, `prefix` and `bucket-region`, e.g. `listBucketsPaginator`.
+ In memory and persistence mode.

### Conditional requests

`GetObject` and `HeadObject` evaluate the `If-Match`, `If-None-Match`, `If-Modified-Since` and
`If-Unmodified-Since` headers, in the order that
[RFC 9110](https://www.rfc-editor.org/rfc/rfc9110.html#section-13.2.2) defines: a read of an object that the
client already holds answers `304 Not Modified`, and one whose `If-Match` or `If-Unmodified-Since` doesn't
hold answers `412 Precondition Failed`.

`PutObject`, `CopyObject` and `CompleteMultipartUpload` evaluate the two entity tag headers as a
[conditional write](https://docs.aws.amazon.com/AmazonS3/latest/userguide/conditional-writes.html), the
way Amazon S3 does, against the current version of the destination key:

+ `If-None-Match: *` stores the object only if the key holds none, otherwise `412 Precondition Failed`;
+ `If-Match: "<etag>"` stores it only if the key holds the object with that entity tag, otherwise
  `412 Precondition Failed`, or `404 NoSuchKey` if the key holds no object at all.

A key whose current version is a delete marker holds no object. A `CompleteMultipartUpload` whose condition fails
keeps the upload and its parts, so it can be completed again or aborted.

`DeleteObject` and `DeleteObjects` evaluate a
[conditional delete](https://docs.aws.amazon.com/AmazonS3/latest/userguide/conditional-deletes.html) against the
current version of the object, whatever version the request deletes:

+ `If-Match: "<etag>"`, or the `ETag` of an object of `DeleteObjects`, deletes only the object with that entity tag,
  and `If-Match: *` only an object that exists: otherwise `412 Precondition Failed` (also if the current version is
  a delete marker), or `404 NoSuchKey` if the key holds no version at all;
+ `x-amz-if-match-size` and `x-amz-if-match-last-modified-time`, or the `Size` and `LastModifiedTime` of an object of
  `DeleteObjects`, delete only an object of that size, or last modified in that second: otherwise
  `412 Precondition Failed`. A key that holds no object satisfies them.

`DeleteObjects` reports an object whose condition fails as an `<Error>` of its own and deletes the others.

`CopyObject` and `UploadPartCopy` also evaluate `x-amz-copy-source-if-match`, `x-amz-copy-source-if-none-match`,
`x-amz-copy-source-if-modified-since` and `x-amz-copy-source-if-unmodified-since` against the source object, in the
order of a read; a condition that doesn't hold answers `412 Precondition Failed`, never `304 Not Modified`.

Every write and delete condition is evaluated under the write lock of the bucket that the object is changed in, so
the request is atomic: of the requests that race for a key, exactly one wins. Code that builds a lock or an
optimistic update on that, e.g. the S3 commit protocols of Delta Lake and Iceberg, is exercised rather than silently
losing its protection.

## Usages

### Programming with LocalS3

Developers could integrate LocalS3 into their own Java applications or testing frameworks via Java APIs.

#### Dependency

```xml
<dependency>
    <groupId>io.github.robothy</groupId>
    <artifactId>local-s3-rest</artifactId>
    <version>last_version</version>
</dependency>
```
#### Run LocalS3 in In-Memory mode

By default, LocalS3 runs in In-Memory mode and listens on `29090` port; all data and metadata retain in the memory.

```java
LocalS3 localS3 = LocalS3.builder().build();

localS3.start();
```

Call the stop method to shut down the service gracefully.

```java
localS3.shutdown();
```


#### Run LocalS3 in Persistence mode

When LocalS3 runs in persistence mode, a data path is required. LocalS3 loads data from and stores all data into 
the specified path.

```java
LocalS3 localS3 = LocalS3.builder()
    .mode(LocalS3Mode.PERSISTENCE)
    .dataPath("C://local-s3")
    .build();

localS3.start();
```

A data path holds two things: `buckets.mvstore`, the [H2 MVStore](https://www.h2database.com/html/mvstore.html)
key-value file that holds the metadata of every bucket, and `.storage/`, the directory that holds the content of the
objects. A change writes only the buckets and objects it touched, so a put into a bucket of many objects stays cheap.
The store is opened while the service runs, and released by `shutdown()`; the services of a JVM that share a data path
share the one open store.

#### Persistence policy

A `PERSISTENCE` service commits the metadata of every change by default, so a process that is killed loses nothing it
answered a request for. A commit appends a chunk to `buckets.mvstore` rather than replacing what it supersedes, so a
bulk load leaves one chunk per object: the file grows far beyond the metadata it holds while the load runs.

`persistencePolicy(FAST)` lets the store commit in the background instead, at most a second after a change, and
commits what is left when the service is shut down:

```java
LocalS3 localS3 = LocalS3.builder()
    .mode(LocalS3Mode.PERSISTENCE)
    .dataPath("/data")
    .persistencePolicy(PersistencePolicy.FAST)
    .build();
```

Storing twenty thousand small objects through a service, measured end to end:

| Policy | Load | `buckets.mvstore` while running | `buckets.mvstore` at rest |
|---|---:|---:|---:|
| `DURABLE` (default) | 4.8 s | 432.6 MB | 1.0 MB |
| `FAST` | 2.9 s | 4.9 MB | 1.0 MB |

A killed `FAST` process loses the changes of the last second; one that is shut down, including by the JVM shutdown
hook, persists everything. That is the trade a data directory built to test against can usually make, e.g. the table
of a big data engine, which is built again if it is lost. Use `DURABLE` when the data directory itself is what
matters.

Either way the file is compacted when the last holder closes the store, so a data directory rests at the size of the
metadata it holds rather than of everything ever written to it. Compaction costs the live metadata, not the size the
file grew to, so it stays quick.

#### Opening a large data path

A `PERSISTENCE` service reads the **keys** of the objects of its data path when it starts, not their metadata: the
metadata of an object is read from `buckets.mvstore` when a request needs it, and a bounded number of them is kept in
heap. Opening a data path therefore costs the keys it holds rather than every version, tag and ACL of every object,
which is what lets a directory of a few hundred thousand data files, e.g. an Iceberg table, be served without loading
it all first.

`GET /_admin/stats` reports the boundary, so it can be seen rather than guessed:

```json
{
  "data": {
    "objects": 412000,
    "loadedObjects": 50000,
    "loadedObjectMetadataBytes": 18432000
  }
}
```

`loadedObjects` is how many objects have their metadata in heap and `loadedObjectMetadataBytes` an estimate of what
that costs, measured as the size of the persisted form of that metadata. The bound defaults to 50000 objects and is
configured with the environment variable, or system property, `LOCAL_S3_OBJECT_METADATA_CACHE_MAX_ENTRIES`:

```shell
LOCAL_S3_OBJECT_METADATA_CACHE_MAX_ENTRIES=200000 java -jar s3.jar
```

Raising it trades heap for fewer reads of the store; the objects that a service serves stay in heap either way, since
what is dropped is what was read longest ago. An `IN_MEMORY` service holds all of its objects, having nowhere to read
them back from, so the bound doesn't apply to it and `loadedObjects` equals `objects`.

#### Run LocalS3 in In-Memory mode with initial data.

LocalS3 loads initial data from the specified path. Changes on such LocalS3 instance only modify the
data in memory, not persist to the disk.

```java
LocalS3 localS3 = LocalS3.builder()
    .port(-1) // assign a random port
    .dataPath("/data")
    .build();

localS3.start();
```

#### Require signed requests

By default LocalS3 serves every request, signed or not. `credentials(...)` enables AWS Signature Version 4
verification with a static access key pair: requests that are unsigned, signed with another key, or whose
signature doesn't match are rejected. Configure your S3 client with the same pair.

```java
LocalS3 localS3 = LocalS3.builder()
    .credentials("access-key-id", "secret-access-key")
    .build();

localS3.start();
```

The [health check](#health-check) needs no authentication, so probes keep working.

The JUnit 5 extension takes the same pair through `accessKey` and `secretKey`, and signs the clients it
injects with it, so a test of signed requests needs no hand-built service:

```java
@LocalS3(accessKey = "access-key-id", secretKey = "secret-access-key")
class AppTest {

  @Test
  void theInjectedClientIsSigned(S3Client s3) {
    s3.createBucket(request -> request.bucket("my-bucket"));
  }
}
```

Both attributes must be set together; setting neither, which is the default, leaves verification off and
injects an anonymous client.

#### Listen to bucket and object changes

`changeListener` subscribes an `S3ChangeListener`, which receives an `S3Change` whenever a bucket or an object
changes, e.g. to trigger an indexer or to assert in a test that an upload happened. Several listeners may be
subscribed; each receives every change.

```java
LocalS3 localS3 = LocalS3.builder()
    .changeListener(change ->
        System.out.println(change.type() + " " + change.bucketName() + " " + change.key()))
    .build();

localS3.start();
```

Changes are published by the services of LocalS3 rather than by its HTTP handlers, so a change is delivered however
it is made: by a client of the HTTP API, or by the application itself through `localS3.getS3Manager()`, e.g.
`getS3Manager().objectService().putObject(...)`. The buckets of `buckets(...)` / `AWS_BUCKETS` fire
`BUCKET_CREATED` too. A change is only delivered once it is persisted and the lock of its bucket is released,
so a listener never hears of a change that was rejected or failed, and may call LocalS3 again. A `reset()` doesn't
fire changes for the data it drops.

Every change carries `type()`, `bucketName()` and `operation()`, the S3 operation that made it, e.g. `PutObject`,
`CopyObject`, `CompleteMultipartUpload`, `DeleteObject`, `DeleteObjects` or `CreateBucket`. `s3EventName()` names the
change like an
[Amazon S3 event notification](https://docs.aws.amazon.com/AmazonS3/latest/userguide/notification-how-to-event-types-and-destinations.html),
e.g. `s3:ObjectCreated:Copy` or `s3:ObjectRemoved:DeleteMarkerCreated`, and is `null` for the changes that Amazon S3
doesn't notify of.

| Change type | Operation | `s3EventName()` | Details |
|---|---|---|---|
| `BUCKET_CREATED`, `BUCKET_DELETED` | `CreateBucket`, `DeleteBucket` | `null` | `bucketName()`, `bucketRegion()`; `key()` is `null` |
| `OBJECT_CREATED` | `PutObject`, `CopyObject`, `CompleteMultipartUpload` | `s3:ObjectCreated:Put`, `:Copy`, `:CompleteMultipartUpload` | `key()`, `size()`, `etag()`, `versionId()` |
| `OBJECT_DELETED` | `DeleteObject`, `DeleteObjects` | `s3:ObjectRemoved:Delete`, `:DeleteMarkerCreated` | `key()`, `versionId()`, `deleteMarker()`; `size()` and `etag()` are `null` |
| `OBJECT_TAGGING_PUT`, `OBJECT_TAGGING_DELETED` | `PutObjectTagging`, `DeleteObjectTagging` | `s3:ObjectTagging:Put`, `:Delete` | `key()`, `versionId()`, `size()`, `etag()` of the version |
| `OBJECT_ACL_PUT` | `PutObjectAcl` | `s3:ObjectAcl:Put` | `key()`, `versionId()`, `size()`, `etag()` of the version |
| `MULTIPART_UPLOAD_ABORTED` | `AbortMultipartUpload` | `null` | `key()`, `uploadId()`; fired only if the upload existed |

Only a change of a bucket leaves `key()` `null`, which tells the two apart. `versionId()` is `null` if the bucket has
never been versioned.

By default, the listeners run **synchronously on the thread that made the change**, so the change of a request is
delivered before the S3 response is sent. Pass an executor to deliver changes asynchronously, so that slow listeners
don't hold up request handling; a single-threaded executor keeps the changes in order.

```java
ExecutorService executor = Executors.newSingleThreadExecutor();

LocalS3 localS3 = LocalS3.builder()
    .changeListenerExecutor(executor)
    .changeListener(change -> index("s3://" + change.bucketName() + "/" + change.key()))
    .build();

localS3.start();
```

LocalS3 does not shut the executor down; that stays with the code that created it. Either way, an exception
thrown by a listener is logged and never fails the S3 request, and a change that the executor rejects is
dropped with a log entry.

### LocalS3 for Spring Boot

Please refer [local-s3-spring-boot-starter/README.md](local-s3-spring-boot-starter/README.md)

### LocalS3 for Junit5

LocalS3 for Junit5 provides a Java annotation `@LocalS3` helps you easily launch S3 services for your tests.

#### Dependency

```xml
<dependency>
    <groupId>io.github.robothy</groupId>
    <artifactId>local-s3-jupiter</artifactId>
    <scope>test</scope>
</dependency>
```

When you annotate it on test classes or test methods, the LocalS3 extension automatically inject instances with 
the following parameter types of test methods or lifecycle methods.

+ `S3Client`
+ `S3VectorsClient`
+ `LocalS3Endpoint`

Example 1: Inject a `S3Client` object to the test method parameter

```java
class AppTest {
  @Test
  @LocalS3
  void test(S3Client client) {
    client.createBucket(b -> b.bucket("my-bucket"));
  }
}
```

Example 2: Inject a `S3VectorsClient` object to the test method parameter

```java
class AppTest {
  @Test
  @LocalS3
  void test(S3VectorsClient vectorsClient) {
    vectorsClient.createVectorBucket(b -> b.vectorBucketName("my-vector-bucket"));
  }
}
```

Example 3: Inject instances to junit5 lifecycle methods.

```java
@LocalS3
class AppTest {
  
  @BeforeAll
  static void beforeAll(S3Client client) {
    client.createBucket(b -> b.bucket("my-bucket"));
  }
  
  @Test
  void test(S3Client client) {
    client.headBucket(b -> b.bucket("my-bucket"));
  }
  
  @AfterAll
  static void afterAll(S3Client client) {
    client.deleteBucket(b -> b.bucket("my-bucket"));
  }
}
```

#### Difference between `@LocalS3` on test classes and test methods

If `@LocalS3` is on a test class, the Junit5 extension will create a shared service for all test methods in the class,
including the test methods of its `@Nested` classes, and shut it down in the "after all" callback.
If `@LocalS3` is on a test method, the extension creates an exclusive service for the method and shut down the
service in the "after each" callback.
A `@Nested` class that is annotated itself gets a service of its own, and a test is always given the innermost service:
the one of its method, then the one of its class, then the one of an enclosing class. Services are kept per test class
and method rather than per thread, so they work the same when tests run in parallel.

### Run LocalS3 in Docker

You can run LocalS3 in Docker since it's image is published to [DockerHub](https://hub.docker.com/r/luofuxiang/local-s3).

```shell
docker run --name s3 -d -v C:\\local-s3:/data -p 29090:29090 luofuxiang/local-s3
```

The container is configured by environment variables:

| Variable | Default | Description |
|---|---|---|
| `LOCAL_S3_PORT` | `29090` | Port that LocalS3 listens on. |
| `LOCAL_S3_HOST` | `127.0.0.1` | host to bind. |
| `LOCAL_S3_MODE` | `PERSISTENCE` | `PERSISTENCE` or `IN_MEMORY`. |
| `LOCAL_S3_DATA_PATH` | `/data` | Data directory, or initial data in `IN_MEMORY` mode. |
| `LOCAL_S3_PERSISTENCE_POLICY` | `DURABLE` | `PERSISTENCE` mode: when changes reach the disk. `DURABLE` commits every change; `FAST` commits in the background and on shutdown, which is much quicker and writes far less. See [Persistence policy](#persistence-policy). |
| `LOCAL_S3_STRICT_BUCKET_NAMES` | `false` | Reject bucket names that Amazon S3 doesn't accept. |
| `LOCAL_S3_STRICT_PART_SIZES` | `false` | Reject a multipart upload whose parts, except the last one, are smaller than the 5 MiB that Amazon S3 requires. |
| `LOCAL_S3_COMPOSITE_MULTIPART_ETAGS` | `true` | Give the object of a completed multipart upload the entity tag of Amazon S3, i.e. the digest of the digests of its parts with a `-<parts>` suffix. `false` answers the digest of the whole content, which LocalS3 answered before 2.5. |
| `LOCAL_S3_VIRTUAL_HOST_DOMAINS` | | Comma-separated base domains of virtual-hosted-style requests, e.g. `s3,s3.local` for `my-bucket.s3`. `localhost`, Amazon S3 (`*.amazonaws.com`) Alibaba Cloud OSS (`my-bucket.oss-cn-hangzhou.aliyuncs.com`) Cloudflare R2 (`my-bucket.<account-id>.r2.cloudflarestorage.com`) and Tigris (`my-bucket.t3.storage.dev`, `my-bucket.fly.storage.tigris.dev`) hosts always work. |
| `LOCAL_S3_VIRTUAL_THREADS` | `true` | Handle every request on a virtual thread of its own. `false` handles the requests on a pool of platform threads, as many as the machine has processors and at least 4. |
| `LOCAL_S3_OBJECT_METADATA_CACHE_MAX_ENTRIES` | `50000` | `PERSISTENCE` mode: the number of objects whose metadata is kept in heap; see [Opening a large data path](#opening-a-large-data-path). |
| `LOCAL_S3_INITIAL_DATA_CACHE_MAX_ENTRIES` | `1024` | `IN_MEMORY` mode with initial data: the max number of data paths whose loaded data the JVM caches. |
| `LOCAL_S3_INITIAL_DATA_CACHE_MAX_BYTES` | a quarter of the max heap | `IN_MEMORY` mode with initial data: the max heap that the copies of the objects read from the data paths take, e.g. `512m`. The least recently used data paths are dropped to make room, and an object that still doesn't fit is read from the disk instead. Also settable with `LocalS3.configureInitialDataCache(maxEntries, maxBytes)`. |
| `AWS_BUCKETS` | | Comma-separated buckets to create on startup. |
| `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` | | Require requests signed with this key pair. |
| `JAVA_OPTS` | `-XX:MaxRAMPercentage=75.0` | JVM options of the JVM based image. |

The same variables configure an embedded service, through `LocalS3Builder.fromEnvironment()`, which reads them
from the environment or from the system properties of the same names. Only the variables that are set are
applied, so the defaults of the embedded service are kept for the rest; the defaults in the table above are
those of the image, which binds every interface and persists to `/data`. Their names are the constants of
`LocalS3Environment`.

```java
LocalS3 localS3 = LocalS3.builder()
    .port(29090)
    .fromEnvironment()
    .build();
```

In `PERSISTENCE` mode, the container gives the data
directory on startup, so that bind-mounted directories stay writable. To run as your own user and keep
the ownership of a bind-mounted directory, start the container with `--user "$(id -u):$(id -g)"`. The images
declare a Docker `HEALTHCHECK` that requests the health check below.

### Run LocalS3 as an executable jar

The same service runs without Docker. `local-s3-standalone` is published to Maven Central as an executable
jar that carries everything it needs, so a JRE 21 is the only requirement:

```shell
curl -LO https://repo1.maven.org/maven2/io/github/robothy/local-s3-standalone/2.5.0/local-s3-standalone-2.5.0.jar
java -jar local-s3-standalone-2.5.0.jar
```

It is configured by the variables of the table above, read from the environment or from the system
properties of the same names, which is what a command line sets most easily:

```shell
java -DLOCAL_S3_PORT=29090 -DLOCAL_S3_MODE=IN_MEMORY -DAWS_BUCKETS=my-bucket \
    -jar local-s3-standalone-2.5.0.jar
```

The defaults are the ones of the container, i.e. it binds every interface and persists to `/data`, so give
it a `LOCAL_S3_DATA_PATH` of your own unless that directory suits you.

A shell that a developer works in often exports `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY` already, and
the jar reads the environment it inherits, so it would require every request to be signed with those
credentials. A container starts with a clean environment and doesn't run into this; clear them for the jar
if you don't mean to sign:

```shell
env -u AWS_ACCESS_KEY_ID -u AWS_SECRET_ACCESS_KEY java -jar local-s3-standalone-2.5.jar
```

### Health check

LocalS3 answers `GET /_health` (and `HEAD /_health`) with `200 OK` and `{"status":"UP"}` once it serves
requests. The health check needs no authentication, even if credentials are configured, so probes can use it.

```yaml
# Kubernetes
readinessProbe:
  httpGet:
    path: /_health
    port: 29090
```

```java
// Testcontainers: wait for the health check instead of the startup log message.
new LocalS3Container("latest")
    .withRandomHttpPort()
    .waitingFor(Wait.forHttp("/_health").forPort(29090));
```

`LocalS3Container` still waits for the startup log message by default, so that it works with images older than
the health check.

### Admin endpoints

A running service answers a few endpoints for local development and tests, with JSON:

| Endpoint | Description |
|---|---|
| `GET /_admin/stats` | The amount of data (buckets, objects, object versions, delete markers, object bytes, multipart uploads in progress, vector buckets, indexes and vectors), how much object metadata the service keeps in heap (`loadedObjects` and `loadedObjectMetadataBytes`, see [Opening a large data path](#opening-a-large-data-path)), the requests in flight, and per operation, e.g. `PutObject`, the number of requests, the `4xx` and `5xx` responses, the requests per second of the last minute, and the average, p50, p90, p99 and max latency in milliseconds. |
| `GET /_admin/requests?limit=n` | The last 100 requests, the most recent first: time, method, URI, operation, status, latency and `x-amz-request-id`. The values of the credentials of presigned URLs are hidden. |
| `POST /_admin/reset` | Replace the data of an `IN_MEMORY` service with the data it started with, i.e. none, or the initial data of its data path, and create the `AWS_BUCKETS` again. The requests recorded for the statistics are forgotten too. A `PERSISTENCE` service answers `409 Conflict`, since a reset would delete its data path. |

Resetting a service between the tests that share it is much quicker than restarting it. The requests in progress
are finished first, and the requests that arrive meanwhile wait for the reset. An embedded service is reset with
`LocalS3#reset()`, and its statistics are read with `LocalS3#statistics()`.

```shell
curl -s http://localhost:29090/_admin/stats
curl -s -X POST http://localhost:29090/_admin/reset
```

The latency of a request is measured from when its body is received until its response is written. The health check
and the admin endpoints aren't recorded. Unlike the health check, the admin endpoints must be signed if credentials
are configured, e.g. with `curl --aws-sigv4 "aws:amz:us-east-1:s3" --user "$AWS_ACCESS_KEY_ID:$AWS_SECRET_ACCESS_KEY" http://localhost:29090/_admin/stats`.

### LocalS3 test container

LocalS3 provides a [testcontainers](https://www.testcontainers.org/) implementation. You can run LocalS3 in your tests 
with testcontainers API.

#### Dependency

```xml
<dependency>
    <groupId>io.github.robothy</groupId>
    <artifactId>local-s3-testcontainers</artifactId>
    <version>last_version</version>
</dependency>
```

#### Start LocalS3 with testcontainers

```java
@Testcontainers
public class AppTest {

  @Container
  public LocalS3Container container = new LocalS3Container("latest")
      .withMode(LocalS3Container.Mode.IN_MEMORY)
      .withRandomHttpPort();
  
  @Test
  void testS3Operations() {
    assertTrue(container.isRunning());
    int port = container.getPort();
    
    // Create S3Client for regular S3 operations
    S3Client s3Client = S3Client.builder()
        .endpointOverride(URI.create("http://localhost:" + port))
        .region(Region.US_EAST_1)
        .credentialsProvider(StaticCredentialsProvider.create(
            AwsBasicCredentials.create("test", "test")))
        .serviceConfiguration(S3Configuration.builder()
            .pathStyleAccessEnabled(true)
            .build())
        .build();
    
    // Test regular S3 operations
    s3Client.createBucket(b -> b.bucket("my-bucket"));
    s3Client.putObject(b -> b.bucket("my-bucket").key("test.txt"), 
        RequestBody.fromString("Hello World"));
  }
  
  @Test
  void testS3VectorOperations() {
    assertTrue(container.isRunning());
    int port = container.getPort();
    
    // Create S3VectorsClient for vector operations
    S3VectorsClient vectorsClient = S3VectorsClient.builder()
        .endpointOverride(URI.create("http://localhost:" + port))
        .region(Region.US_EAST_1)
        .credentialsProvider(StaticCredentialsProvider.create(
            AwsBasicCredentials.create("test", "test")))
        .build();
    
    // Test vector operations
    vectorsClient.createVectorBucket(b -> b.vectorBucketName("my-vector-bucket"));
    vectorsClient.createIndex(b -> b
        .vectorBucketName("my-vector-bucket")
        .indexName("my-index")
        .dimension(128)
        .dataType(DataType.FLOAT32)
        .distanceMetric(DistanceMetric.COSINE));
  }
  
}
```

# References

* [H2 MVStore](https://h2database.com/html/mvstore.html): a persistent, log structured key-value store
* S3 compatibility tests: https://github.com/ceph/s3-tests