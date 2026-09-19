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

`endpoint()` is the URL that clients reach the running service at, so nothing has to assemble it from the scheme, the
bind host and the port:

```java
S3Client s3 = S3Client.builder()
    .endpointOverride(URI.create(localS3.endpoint()))   // e.g. http://127.0.0.1:29090
    .build();
```

A service bound to every interface is reached at the loopback address, `127.0.0.1`, an IPv6 bind host is bracketed, and
a service that serves [HTTPS](#serve-https) has an `https` endpoint. A service that was given a random port has none
until it is started, so `endpoint()` throws an `IllegalStateException` before that.

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

### Seed the initial buckets and objects

`seeder(...)` puts the buckets and objects that the service starts with into it, e.g. the fixtures of a test, before it
accepts the first request. A seeder runs again after `localS3.reset()`, so every test method of a class that shares one
service finds the same fixtures, and the bucket of an object is created if nothing else names it.

```java
LocalS3 localS3 = LocalS3.builder()
    .port(0)
    .seeder(fixtures -> {
      fixtures.object("uploads", "hello.txt", "Hello!".getBytes(UTF_8));
      fixtures.object("uploads", "images/logo.png", Path.of("src/test/resources/logo.png"));
    })
    .build();

localS3.start();
```

Unlike [initial data](#in-memory-mode-with-initial-data), which is a data path that LocalS3 itself wrote, a seeder
writes whatever the application has at hand: files of the classpath, rows of a database, generated content. An object
replaces the one that its key already holds. Spring Boot applications map a directory tree of the classpath with
[`local-s3.seed.classpath`](../local-s3-spring-boot-starter/README.md#initial-data) instead of writing a seeder.

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

### Presigned URLs

`presign(bucket, key, expiration)` signs a URL that reads an object for a while, so that whoever holds the URL — a
browser, a teammate an AI agent hands an artifact to, a tool that takes a download link — gets the object without
credentials:

```java
String url = localS3.presign("artifacts", "reports/q1 summary.pdf", Duration.ofMinutes(15));
// http://127.0.0.1:29090/artifacts/reports/q1%20summary.pdf?X-Amz-Algorithm=AWS4-HMAC-SHA256&...
```

The fourth argument is the HTTP method that the URL is signed for, `GET` by default; `PUT` hands out an upload slot:

```java
String uploadUrl = localS3.presign("artifacts", "input.csv", Duration.ofMinutes(15), "PUT");
```

+ **The same signature the service verifies**: the URL is path-style, signed with AWS Signature Version 4 for the
  credentials of the service, and expires after the given duration — between 1 second and 7 days, as with Amazon S3.
  A request of another method, another key or with an edited signature is answered `403`.
+ **Without `credentials(...)`** the service answers unsigned requests, so the plain URL of the object is returned
  instead, which doesn't expire.
+ **Nothing is touched**: neither the bucket nor the object has to exist when a URL is signed, and a URL used after the
  object is gone is answered `404 NoSuchKey`, exactly as Amazon S3 behaves.

An AWS SDK `S3Presigner` pointed at `localS3.endpoint()` signs URLs that the service accepts too; `presign(...)` is for
the embedding application that has the service at hand and doesn't want to build a second client to share a file.

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

### Envelope encryption (KMS)

LocalS3 answers the KMS actions `GenerateDataKey`, `GenerateDataKeyWithoutPlaintext`, `Encrypt`, `Decrypt`,
`DescribeKey` and `GenerateRandom` on its own port, so a client that encrypts objects itself and wraps the data key
with KMS, e.g. the Amazon S3 Encryption Client, needs no second endpoint.

```java
KmsClient kms = KmsClient.builder()
    .endpointOverride(URI.create("http://localhost:29090"))
    .region(Region.US_EAST_1)
    .credentialsProvider(StaticCredentialsProvider.create(
        AwsBasicCredentials.create("access-key-id", "secret-access-key")))
    .build();
GenerateDataKeyResponse dataKey = kms.generateDataKey(b -> b
    .keyId("alias/local")
    .keySpec(DataKeySpec.AES_256)
    .encryptionContext(Map.of("bucket", "warehouse")));
// Encrypt the object with dataKey.plaintext(), store dataKey.ciphertextBlob() beside it, and unwrap it later:
SdkBytes plaintext = kms.decrypt(b -> b
    .ciphertextBlob(dataKey.ciphertextBlob())
    .encryptionContext(Map.of("bucket", "warehouse"))).plaintext();
```

+ **Nothing is kept secret**: a ciphertext blob is the plaintext in an envelope that anyone can unpack, and there is no
  key material. A blob written by LocalS3 protects nothing; it is for tests and local development only.
+ **Stateless and keyless**: no key is stored, so every key ID, alias or ARN works, `DescribeKey` describes it, and a
  blob is read back after a restart, in both modes.
+ **A faithful round trip**: a blob unwraps to the plaintext it was wrapped from, bound to its key ID and encryption
  context; another context answers `InvalidCiphertextException` and another `KeyId` answers `IncorrectKeyException`,
  see [semantics.md](semantics.md#the-kms-endpoint).
+ This is separate from the `x-amz-server-side-encryption` headers of SSE-KMS, which LocalS3 stores and echoes without
  calling the endpoint.

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

### Settings grouped by domain

The settings a service is usually built with — its address, its mode and data directory, its buckets, its credentials —
are methods of the builder itself. The settings that only some services tune are grouped behind one method per domain,
which takes the settings of that domain and applies them, so the rarely used knobs stay out of the way:

| Domain | Method | What it configures |
|---|---|---|
| HTTP server | `netty(netty -> ...)` | `parentEventGroupThreadNum`, `childEventGroupThreadNum`, `s3ExecutorThreadNum`, `virtualThreads`, `daemonThreads`, `maxRequestBodySize`, `requestBodyFileThreshold`, `maxRequestHeaderSize`, `idleConnectionTimeoutSeconds` |
| HTTPS | `tls(tls -> ...)` | `certificate(...)`, `selfSigned(...)`, `required(...)` |
| Static websites | `website(website -> ...)` | `enabled`, `allBuckets`, `indexDocument`, `errorDocument`, `settings(LocalS3Website)` |
| Iceberg REST catalog | `icebergCatalog(iceberg -> ...)` | `enabled`, `warehouse`, `createWarehouseBucket`, `credentialVending`, `settings(LocalS3IcebergCatalog)` |

```java
LocalS3 localS3 = LocalS3.builder()
    .port(29090)
    .netty(netty -> netty.childEventGroupThreadNum(8)
        .maxRequestBodySize(64 * 1024 * 1024)
        .idleConnectionTimeoutSeconds(30))
    .tls(tls -> tls.selfSigned().required(true))
    .website(website -> website.allBuckets(true).indexDocument("home.html"))
    .icebergCatalog(iceberg -> iceberg.warehouse("s3://lakehouse/"))
    .build();

localS3.start();
```

Each domain also keeps the one-liner that turns it on with its defaults: `tls(certPem, keyPem)`, `tls(LocalS3Tls)`,
`website(true)` and `icebergCatalog(true)`. Entering `icebergCatalog(iceberg -> ...)` turns the catalog on, since
configuring one is asking for one.

A settings object writes through to the builder as it is called, so it must not be kept beyond the call.

## Spring Boot

`local-s3-spring-boot-starter` embeds LocalS3 in a Spring Boot 4 application, configured by `local-s3.*` properties,
and, when the application has the AWS SDK, which is an optional dependency, defines an `S3Client`, an `S3AsyncClient`
and an `S3Presigner` that point at it. `local-s3.seed.classpath` names a directory tree of the classpath that the
service starts with, as `<bucket>/<key>`. `local-s3.website.*` configures
[static website hosting](semantics.md#static-website-hosting): `enabled`, `all-buckets`, `index-document` and
`error-document`. See [its README](../local-s3-spring-boot-starter/README.md).

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
