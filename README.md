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


## Features

+ Support S3 object versioning.
+ Support S3 Vectors for vector storage and similarity search.
+ In memory and persistence mode.

## Usages

### Programming with LocalS3

Developers could integrate LocalS3 into their own Java applications or testing frameworks via Java APIs.

#### Dependency

```xml
<dependency>
    <groupId>io.github.robothy</groupId>
    <artifactId>local-s3-rest</artifactId>
</dependency>
```
#### Run LocalS3 in In-Memory mode

By default, LocalS3 runs in In-Memory mode; all data and metadata retain in the memory.

```java
LocalS3 localS3 = LocalS3.builder()
    .port(29090)
    .build();

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
    .port(29090)
    .mode(LocalS3Mode.PERSISTENCE)
    .dataPath("C://local-s3")
    .build();

localS3.start();
```

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
    .port(29090)
    .credentials("access-key-id", "secret-access-key")
    .build();

localS3.start();
```

The [health check](#health-check) needs no authentication, so probes keep working.

#### Listen to bucket and object events

`bucketEventListener` and `objectEventListener` are functional interfaces that receive an event whenever a
bucket or an object changes, e.g. to trigger an indexer or to assert in a test that an upload happened.

```java
LocalS3 localS3 = LocalS3.builder()
    .port(29090)
    .bucketEventListener(event ->
        System.out.println(event.getEventType() + " " + event.getBucketName()))
    .objectEventListener(event ->
        System.out.println(event.getEventType() + " " + event.getObjectUrl() + " " + event.getSize()))
    .build();

localS3.start();
```

Every event carries `getEventId()`, `getEventType()`, `getTimestamp()` and `getSource()`, the S3 operation that
triggered it, e.g. `PutObject`, `CopyObject`, `CompleteMultipartUpload`, `DeleteObject`, `DeleteObjects` or
`CreateBucket`.

| Event type | Delivered to | Details |
|---|---|---|
| `BUCKET_CREATED`, `BUCKET_DELETED` | `bucketEventListener` | `getBucketName()`, `getBucketRegion()` |
| `OBJECT_CREATED` | `objectEventListener` | `getObjectKey()`, `getObjectUrl()` (`s3://bucket/key`), `getSize()`, `getEtag()`, `getVersionId()` |
| `OBJECT_DELETED` | `objectEventListener` | `getObjectKey()`, `getObjectUrl()`, `getVersionId()`, `isDeleteMarker()`; `getSize()` and `getEtag()` are `null` |

`getVersionId()` is `null` if the bucket has never been versioned.

By default, the listeners run **synchronously on the thread handling the request**, so an event is delivered
before the S3 response is sent. Pass an executor to deliver events asynchronously, so that slow listeners don't
hold up request handling; a single-threaded executor keeps the events in order.

```java
ExecutorService executor = Executors.newSingleThreadExecutor();

LocalS3 localS3 = LocalS3.builder()
    .port(29090)
    .eventListenerExecutor(executor)
    .objectEventListener(event -> index(event.getObjectUrl()))
    .build();

localS3.start();
```

LocalS3 does not shut the executor down; that stays with the code that created it. Either way, an exception
thrown by a listener is logged and never fails the S3 request, and an event that the executor rejects is
dropped with a log entry.

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

If `@LocalS3` is on a test class, the Junit5 extension will create a shared service for all test methods in the class
and shut it down in the "after all" callback.
If `@LocalS3` is on a test method, the extension creates an exclusive service for the method and shut down the
service in the "after each" callback.

### Run LocalS3 in Docker

You can run LocalS3 in Docker since it's image is published to [DockerHub](https://hub.docker.com/r/luofuxiang/local-s3).

```shell
docker run --name s3 -d -v C:\\local-s3:/data -p 29090:29090 luofuxiang/local-s3
```

The container is configured by environment variables:

| Variable | Default | Description |
|---|---|---|
| `LOCAL_S3_PORT` | `29090` | Port that LocalS3 listens on. |
| `LOCAL_S3_MODE` | `PERSISTENCE` | `PERSISTENCE` or `IN_MEMORY`. `MODE` is still accepted. |
| `LOCAL_S3_DATA_PATH` | `/data` | Data directory, or initial data in `IN_MEMORY` mode. |
| `LOCAL_S3_STRICT_BUCKET_NAMES` | `false` | Reject bucket names that Amazon S3 doesn't accept. |
| `LOCAL_S3_VIRTUAL_HOST_DOMAINS` | | Comma-separated base domains of virtual-hosted-style requests, e.g. `s3,s3.local` for `my-bucket.s3`. `localhost`, Amazon S3 (`*.amazonaws.com`) Alibaba Cloud OSS (`my-bucket.oss-cn-hangzhou.aliyuncs.com`) Cloudflare R2 (`my-bucket.<account-id>.r2.cloudflarestorage.com`) and Tigris (`my-bucket.t3.storage.dev`, `my-bucket.fly.storage.tigris.dev`) hosts always work. |
| `AWS_BUCKETS` | | Comma-separated buckets to create on startup. |
| `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` | | Require requests signed with this key pair. |
| `JAVA_OPTS` | `-XX:MaxRAMPercentage=75.0` | JVM options of the JVM based image. |

The same variables configure an embedded service, through `LocalS3.Builder.fromEnvironment()`, which reads them
from the environment or from the system properties of the same names. Only the variables that are set are
applied, so the defaults of the embedded service are kept for the rest; the defaults in the table above are
those of the image, which binds every interface and persists to `/data`.

```java
LocalS3 localS3 = LocalS3.builder()
    .port(29090)
    .fromEnvironment()
    .build();
```

In `PERSISTENCE` mode, the container gives the data
directory to that user on startup, so that bind-mounted directories stay writable. To run as your own user and keep
the ownership of a bind-mounted directory, start the container with `--user "$(id -u):$(id -g)"`. The images
declare a Docker `HEALTHCHECK` that requests the health check below.

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

### LocalS3 test container

LocalS3 provides a [testcontainers](https://www.testcontainers.org/) implementation. You can run LocalS3 in your tests 
with testcontainers API.

#### Dependency

```xml
<dependency>
    <groupId>io.github.robothy</groupId>
    <artifactId>local-s3-testcontainers</artifactId>
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

* S3 compatibility tests: https://github.com/ceph/s3-tests