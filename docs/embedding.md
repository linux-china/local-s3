# Embedding LocalS3

LocalS3 runs inside a JVM as well as on its own: started from Java code, as a bean of a Spring Boot application, per
test with a JUnit 5 annotation, or in a container managed by Testcontainers. All artifacts are published to Maven
Central under the group `io.github.robothy`, and require Java 21.

- [Java API](#java-api)
- [Spring Boot](#spring-boot)
- [JUnit 5](#junit-5)
- [Testcontainers](#testcontainers)

## Java API

```xml
<dependency>
    <groupId>io.github.robothy</groupId>
    <artifactId>local-s3-rest</artifactId>
    <version>last_version</version>
</dependency>
```

### In-memory mode

By default, LocalS3 runs in `IN_MEMORY` mode and listens on `127.0.0.1:29090`; all data and metadata are kept in memory.

The objects of an `IN_MEMORY` service take at most half the max heap by default, so that tests which write large files,
e.g. Parquet files of DuckDB or Iceberg, don't run the application or IDE that embeds LocalS3 out of heap: an upload
beyond the limit is answered with `507 InsufficientStorage`. Set the limit with `maxInMemoryBytes(bytes)`,
`LOCAL_S3_IN_MEMORY_MAX_BYTES` or `local-s3.in-memory.max-size`, or use `PERSISTENCE` mode for data that large.

```java
LocalS3 localS3 = LocalS3.builder().build();

localS3.start();
```

Call `shutdown()`, or `close()`, since `LocalS3` is `AutoCloseable`, to stop the service gracefully.

```java
localS3.shutdown();
```

`port(0)` binds a random free port, which `getPort()` returns once the service is started. `acceptFromAnyHost()` binds
every interface instead of `127.0.0.1`.

### Persistence mode

When LocalS3 runs in persistence mode, a data path is required. LocalS3 loads data from and stores all data into
the specified path.

```java
LocalS3 localS3 = LocalS3.builder()
    .mode(LocalS3Mode.PERSISTENCE)
    .dataPath("/var/lib/local-s3")
    .build();

localS3.start();
```

The store of a data path is opened while the service runs, and released by `shutdown()`; the services of a JVM that
share a data path share the one open store. How the path is laid out is described in
[architecture.md](architecture.md#persistence-layout); when changes reach the disk, and how a large data path is opened,
in [deployment.md](deployment.md#data-directory).

### In-memory mode with initial data

A data path without `mode(PERSISTENCE)` is initial data: LocalS3 starts from the data of the path, and changes on such
an instance only modify the data in memory, never the disk. Tests that start from the same path share its loaded
metadata and the objects read from it, within a bounded cache; see `LOCAL_S3_INITIAL_DATA_CACHE_MAX_BYTES` in
[deployment.md](deployment.md#configuration).

```java
LocalS3 localS3 = LocalS3.builder()
    .port(0) // assign a random port
    .dataPath("/data")
    .build();

localS3.start();
```

`localS3.reset()` brings the service back to that initial data, much quicker than restarting it.

### Require signed requests

By default LocalS3 serves every request, signed or not. `credentials(...)` enables AWS Signature Version 4
verification with a static access key pair: requests that are unsigned, signed with another key, or whose
signature doesn't match are rejected. Configure your S3 client with the same pair.

```java
LocalS3 localS3 = LocalS3.builder()
    .credentials("access-key-id", "secret-access-key")
    .build();

localS3.start();
```

The [health check](deployment.md#health-check) needs no authentication, so probes keep working.

### Serve HTTPS

`tls(LocalS3Tls.selfSigned())` generates a certificate for `localhost`, `127.0.0.1` and `::1` when the service is
built, so that a client that uses HTTPS by default, e.g. DuckDB, connects without a certificate to install:

```java
LocalS3Tls tls = LocalS3Tls.selfSigned();       // or selfSigned("localhost", "s3.local"), for other hosts
LocalS3 localS3 = LocalS3.builder().tls(tls).build();
localS3.start();

// Nothing trusts a certificate that signed itself, so the client is given it:
S3Client s3 = S3Client.builder()
    .endpointOverride(URI.create("https://localhost:" + localS3.getPort()))
    .httpClient(ApacheHttpClient.builder().tlsTrustManagersProvider(tls::trustManagers).build())
    .build();

// For a client outside the JVM, e.g. curl --cacert local-s3.pem or DuckDB's ca_cert_file:
Files.writeString(Path.of("local-s3.pem"), tls.certificateChainPem());
```

`newClientSslContext()` returns an `SSLContext` that trusts the certificate for a client that takes one, e.g.
`HttpClient` or `HttpsURLConnection`, and the service logs the certificate in PEM format when it starts. A new
certificate is generated per call, so a client that holds the certificate of one service doesn't trust another. See
[Generate a certificate on startup](deployment.md#generate-a-certificate-on-startup).

`tls(certPem, keyPem)` serves HTTPS with a certificate of your own instead. Each argument is the path of a PEM file, or
the PEM content itself, e.g. read from a secret; `tls(Path, Path)` takes the files as paths. Create the certificate and
key with [mkcert](https://github.com/FiloSottile/mkcert), e.g. `mkcert localhost 127.0.0.1`, and the clients of the
machine trust them without being given anything:

```java
LocalS3 localS3 = LocalS3.builder()
    .tls("localhost+1.pem", "localhost+1-key.pem")
    .build();

localS3.start();
// localS3.isTlsEnabled() == true; clients use https://localhost:29090
```

The key must be an unencrypted PKCS#8 key (`-----BEGIN PRIVATE KEY-----`), which mkcert writes. A JVM client trusts the
certificate once the CA of mkcert is in its trust store; see [HTTPS](deployment.md#https) for that, and for the clients
that need TLS.

### Temporary credentials (STS)

LocalS3 answers the STS actions `AssumeRole`, `GetSessionToken` and `GetCallerIdentity` on its own port, so a client
that gets temporary credentials from STS, e.g. an Iceberg REST catalog that vends them to DuckDB, PyIceberg or Spark,
can use LocalS3 as its STS endpoint. Requests signed with the temporary credentials, i.e. with their
`x-amz-security-token`, `X-Amz-Security-Token` of a presigned URL, or the `x-amz-security-token` field of a form upload,
are accepted until the credentials expire.

```java
StsClient sts = StsClient.builder()
    .endpointOverride(URI.create("http://localhost:29090"))
    .region(Region.US_EAST_1)
    .credentialsProvider(StaticCredentialsProvider.create(
        AwsBasicCredentials.create("access-key-id", "secret-access-key")))
    .build();
Credentials credentials = sts.assumeRole(b -> b
    .roleArn("arn:aws:iam::123456789012:role/reader")
    .roleSessionName("spark")
    .durationSeconds(3600)).credentials();
```

+ **Stateless**: nothing is stored. The session token carries the access key ID, the expiration and the identity,
  authenticated with a key derived from the secret access key, and the temporary secret access key is derived from the
  token again. The credentials stay valid across restarts, in both modes, as long as the secret access key is the same;
  changing it revokes all of them.
+ **No IAM**: `RoleArn`, `RoleSessionName`, `DurationSeconds` and `Policy` are validated like STS validates them
  (`AssumeRole` 15 minutes to 12 hours, 1 hour at most when chained from temporary credentials; `GetSessionToken` up to
  36 hours, not from temporary credentials), but temporary credentials can do everything the static key pair can.
  Any role ARN is accepted; one of the form `arn:aws:iam::<account>:role/<name>` names the account and the role of the
  assumed-role ARN.
+ **Without `credentials(...)`** the endpoint issues credentials as well, and every request is accepted as before, so a
  catalog configured with an STS endpoint works with an unauthenticated LocalS3 too.
+ **Errors**: an STS request is answered with the errors of STS, e.g. `InvalidClientTokenId`; an S3 request signed with a
  forged or foreign token with `400 InvalidToken`, and with an expired one with `400 ExpiredToken`.

### Listen to bucket and object changes

`changeListener` subscribes an `S3ChangeListener`, which receives an `S3Change` whenever a bucket or an object
changes. [semantics.md](semantics.md#change-events) lists the changes and when they are delivered.

```java
LocalS3 localS3 = LocalS3.builder()
    .changeListener(change ->
        System.out.println(change.type() + " " + change.bucketName() + " " + change.key()))
    .build();

localS3.start();
```

The listeners run synchronously on the thread that made the change by default. Pass an executor to deliver changes
asynchronously, so that slow listeners don't hold up request handling; a single-threaded executor keeps the changes in
order.

```java
ExecutorService executor = Executors.newSingleThreadExecutor();

LocalS3 localS3 = LocalS3.builder()
    .changeListenerExecutor(executor)
    .changeListener(change -> index("s3://" + change.bucketName() + "/" + change.key()))
    .build();

localS3.start();
```

### Configure from the environment

`fromEnvironment()` applies the environment variables of the Docker image, e.g. `LOCAL_S3_MODE` or `AWS_BUCKETS`, that
are set; see [deployment.md](deployment.md#configuration).

## Spring Boot

`local-s3-spring-boot-starter` embeds LocalS3 in a Spring Boot 4 application, configured by `local-s3.*` properties,
and, when the application has the AWS SDK, which is an optional dependency, defines an `S3Client`, an `S3AsyncClient`
and an `S3Presigner` that point at it. See
[its README](../local-s3-spring-boot-starter/README.md).

## JUnit 5

`local-s3-jupiter` provides the annotation `@LocalS3`, which launches S3 services for your tests.

```xml
<dependency>
    <groupId>io.github.robothy</groupId>
    <artifactId>local-s3-jupiter</artifactId>
    <version>last_version</version>
    <scope>test</scope>
</dependency>
```

When you annotate test classes or test methods with it, the LocalS3 extension injects instances of the following
parameter types into test methods and lifecycle methods:

+ `S3Client`
+ `S3VectorsClient`
+ `LocalS3Endpoint`

Example 1: inject an `S3Client` into a test method.

```java
class AppTest {
  @Test
  @LocalS3
  void test(S3Client client) {
    client.createBucket(b -> b.bucket("my-bucket"));
  }
}
```

Example 2: inject an `S3VectorsClient` into a test method.

```java
class AppTest {
  @Test
  @LocalS3
  void test(S3VectorsClient vectorsClient) {
    vectorsClient.createVectorBucket(b -> b.vectorBucketName("my-vector-bucket"));
  }
}
```

Example 3: inject instances into JUnit 5 lifecycle methods.

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

The attributes of `@LocalS3` configure the service like the builder does: `port`, `mode`, `dataPath` or
`dataPathSupplier`, `buckets`, `initialDataCacheEnabled`, `compositeMultipartEtags`, `virtualHostDomains`, and
`accessKey` with `secretKey`.

### Signed requests

`accessKey` and `secretKey` enable signature verification, and sign the clients that the extension injects with the
same pair, so a test of signed requests needs no hand-built service:

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

### `@LocalS3` on test classes and test methods

If `@LocalS3` is on a test class, the extension creates a service shared by all test methods in the class,
including the test methods of its `@Nested` classes, and shuts it down in the "after all" callback.
If `@LocalS3` is on a test method, the extension creates an exclusive service for the method and shuts it down in the
"after each" callback.

A `@Nested` class that is annotated itself gets a service of its own, and a test is always given the innermost service:
the one of its method, then the one of its class, then the one of an enclosing class. Services are kept per test class
and method rather than per thread, so they work the same when tests run in parallel.

## Testcontainers

`local-s3-testcontainers` runs the [Docker image](deployment.md#docker) with [Testcontainers](https://testcontainers.com/).

```xml
<dependency>
    <groupId>io.github.robothy</groupId>
    <artifactId>local-s3-testcontainers</artifactId>
    <version>last_version</version>
    <scope>test</scope>
</dependency>
```

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

To wait for the health check rather than the startup log message, see [deployment.md](deployment.md#health-check).
