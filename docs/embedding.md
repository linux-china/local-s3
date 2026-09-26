# Embedding LocalS3

LocalS3 runs inside a JVM as well as on its own: started from Java code, by an IDE plugin for as long as the IDE runs,
as a bean of a Spring Boot application, per test with a JUnit 5 annotation, or in a container managed by
Testcontainers. All artifacts are published to Maven Central under the group `io.github.robothy`, and require Java 21.

- [Java API](#java-api)
- [JetBrains IDEs](#jetbrains-ides)
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
beyond the limit is answered with `507 InsufficientStorage`. Set the limit with
`storage(storage -> storage.maxInMemoryBytes(bytes))`, `LOCAL_S3_IN_MEMORY_MAX_BYTES` or
`local-s3.in-memory.max-size`, or use `PERSISTENCE` mode for data that large.

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
share a data path share the one open store, but not what each of them holds in memory, so only one of them is to run at
a time, see [One data directory, one service](#one-data-directory-one-service). How the path is laid out is described in
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

### Look at what is in the service

An embedded service has no window of its own, so `GET /_admin/ui` serves a [built-in console](deployment.md#console):
a single HTML page that lists the buckets and creates one, walks a bucket by its prefixes, previews or downloads an
object, and uploads files dropped on it or deletes one. A service logs its address when it starts, and `endpoint()`
names it as well:

```java
localS3.start();
Desktop.getDesktop().browse(URI.create(localS3.endpoint() + "/_admin/ui"));
```

A service configured with `credentials(...)` asks for them as HTTP Basic authentication, since a browser can't sign a
request with SigV4. What the console creates, uploads and deletes reaches the
[change listeners](#listen-to-bucket-and-object-changes) like every other change, so a fixture dropped on the page is
seen by the application that holds the service.

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
    .events(events -> events.executor(executor)
        .listener(change -> index("s3://" + change.bucketName() + "/" + change.key())))
    .build();

localS3.start();
```

### Configure from the environment

`fromEnvironment()` applies the environment variables of the Docker image, e.g. `LOCAL_S3_MODE` or `AWS_BUCKETS`, that
are set; see [deployment.md](deployment.md#configuration). The credentials come from `LOCAL_S3_ACCESS_KEY_ID` and
`LOCAL_S3_SECRET_ACCESS_KEY`; `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY`, which an IDE or an application inherits
from the shell of the developer as client credentials, are read only with `LOCAL_S3_CREDENTIALS_FROM_AWS_ENV=true`.

### Settings grouped by domain

The settings a service is usually built with — its address, its mode and data directory, its buckets, its credentials —
are methods of the builder itself. The settings that only some services tune are grouped behind one method per domain,
which takes the settings of that domain and applies them, so the rarely used knobs stay out of the way:

| Domain | Method | What it configures |
|---|---|---|
| Storage | `storage(storage -> ...)` | `mode`, `dataPath`, `persistencePolicy`, `maxInMemoryBytes`, `initialDataCacheEnabled` |
| HTTP server | `netty(netty -> ...)` | `parentEventGroupThreadNum`, `childEventGroupThreadNum`, `s3ExecutorThreadNum`, `virtualThreads`, `daemonThreads`, `maxRequestBodySize`, `requestBodyFileThreshold`, `maxRequestHeaderSize`, `idleConnectionTimeoutSeconds`, `registerShutdownHook`, `requestRecorder` |
| S3 API | `s3Api(s3 -> ...)` | `virtualHostDomains`, `compositeMultipartEtags` |
| Change events | `events(events -> ...)` | `listener(S3ChangeListener)`, `executor(Executor)` |
| HTTPS | `tls(tls -> ...)` | `certificate(...)`, `selfSigned(...)`, `required(...)` |
| Static websites | `website(website -> ...)` | `enabled`, `allBuckets`, `indexDocument`, `errorDocument`, `settings(LocalS3Website)` |
| Default CORS rule | `defaultCors(cors -> ...)` | `allowedOrigins`, `allowedMethods`, `allowedHeaders`, `exposeHeaders`, `maxAgeSeconds`, `settings(LocalS3Cors)`; see [CORS](semantics.md#cors) |
| Iceberg REST catalog | `icebergCatalog(iceberg -> ...)` | `enabled`, `warehouse`, `createWarehouseBucket`, `credentialVending`, `uniqueTableLocation`, `settings(LocalS3IcebergCatalog)` |

```java
LocalS3 localS3 = LocalS3.builder()
    .port(29090)
    .storage(storage -> storage.mode(LocalS3Mode.PERSISTENCE)
        .dataPath("/tmp/local-s3")
        .persistencePolicy(PersistencePolicy.FAST))
    .netty(netty -> netty.childEventGroupThreadNum(8)
        .maxRequestBodySize(64 * 1024 * 1024)
        .idleConnectionTimeoutSeconds(30))
    .s3Api(s3 -> s3.virtualHostDomains("s3.local"))
    .tls(tls -> tls.selfSigned().required(true))
    .website(website -> website.allBuckets(true).indexDocument("home.html"))
    .icebergCatalog(iceberg -> iceberg.warehouse("s3://lakehouse/"))
    .build();

localS3.start();
```

The settings every service is built with stay methods of the builder itself: `bindHost`, `acceptFromAnyHost`, `port`,
`mode`, `dataPath`, `buckets`, `credentials`, `seeder`, `changeListener` and `fromEnvironment`. Each domain also keeps
the one-liner that turns it on with its defaults: `tls(certPem, keyPem)`, `tls(LocalS3Tls)`, `website(true)` and
`icebergCatalog(true)`. Entering `icebergCatalog(iceberg -> ...)` turns the catalog on, since configuring one is asking
for one.

A settings object writes through to the builder as it is called, so it must not be kept beyond the call.

## JetBrains IDEs

A plugin of IntelliJ IDEA, PyCharm, DataGrip or another JetBrains IDE can embed LocalS3 as the S3 service of the IDE,
e.g. the default S3 of DuckDB, started with the IDE and stopped when it exits. It uses the [Java API](#java-api), and
what follows holds for any application that keeps a service for its whole run. Such a service lives far longer than
one of a test, shares its JVM with the IDE, and holds data that the user expects to find again, so four things need
a decision: how many services open the data directory, the persistence policy, the order of the shutdown, and the
threads.

### One data directory, one service

The projects that are open in an IDE share its JVM, so the service belongs to the application rather than to a
project: a project-level service would start one per open window. An application-level service that implements
`Disposable` has the lifecycle that LocalS3 needs:

```java
@Service(Service.Level.APP)
public final class LocalS3Service implements Disposable {

  private final LocalS3 localS3 = LocalS3.builder()
      .port(29090)                                           // fixed, so that the S3 secrets of clients stay valid
      .storage(storage -> storage.mode(LocalS3Mode.PERSISTENCE)
          .dataPath(Path.of(PathManager.getSystemPath(), "local-s3").toString())
          .persistencePolicy(PersistencePolicy.FAST))
      .netty(netty -> netty.registerShutdownHook(false))     // the IDE stops the service, see below
      .build();

  public synchronized String endpoint() {
    if (!localS3.isRunning()) {
      localS3.start();                                       // on first use, not while the IDE starts
    }
    return localS3.endpoint();
  }

  @Override
  public void dispose() {
    localS3.close();
  }
}
```

A data directory is opened when a service starts, not when it is built, and released when it is shut down. While it is
open, it is held by that one service:

+ **Another process** that opens it, e.g. a second IDE with the same setting, or a test run that is given the directory
  as its data path or as its initial data, fails to start with `MVStoreException: The file is locked`. Give each IDE a
  directory of its own, e.g. under `PathManager.getSystemPath()`, and point other processes at the endpoint of the
  service rather than at its directory.
+ **Another service of the same JVM** opens the same store, which is reference-counted, but loads the buckets and
  objects of the directory into memory for itself when it starts. The two don't see each other's changes: an object
  one of them puts is `404 NoSuchKey` for the other, an object one of them deletes is `500 InternalError` for the other,
  since its content is gone, and a change of the settings of a bucket, e.g. its tags, is lost when the other one next
  writes the bucket. Never run two `PERSISTENCE` services over one directory at the same time: shut one down before
  the next one starts, e.g. when the user changes a setting. A second service with another persistence policy is
  rejected with an `IllegalStateException` when it starts.
+ **The port** is taken the same way: a second IDE that embeds LocalS3 on port `29090` gets a `BindException` from
  `start()`. Make the port a setting of the plugin, and report the failure to the user.

### Persistence policy

Use `PersistencePolicy.FAST`, as the example does, unless the data can't be built again. A `FAST` service commits its
changes in the background, at most a second after they are made, and everything that is left when it is shut down;
`DURABLE`, the default of `LocalS3Builder`, commits every change before it answers the request. `FAST` loads the data
that a user or a tool writes into the IDE's service, e.g. the Parquet files of a DuckDB `COPY`, faster, and keeps
`buckets.mvstore` far smaller while it runs. What it risks is the changes of the last second when the IDE is killed or
crashes; an IDE that exits normally shuts the service down and loses nothing. See
[Persistence policy](deployment.md#persistence-policy) for the numbers.

An `IN_MEMORY` service suits a plugin whose data is scratch, but its objects take up to half the max heap by default,
i.e. half of the IDE's heap. Give it a limit of its own, e.g. `storage(storage -> storage.maxInMemoryBytes(256L * 1024
* 1024))`, beyond which uploads are answered `507 InsufficientStorage` rather than taking memory from the IDE.

### Stopping with the IDE

`close()`, i.e. `shutdown()`, stops the service in this order:

1. The listening socket is closed, so no connection is accepted anymore.
2. The requests in flight get up to 5 seconds to finish and write their responses. Whatever still runs after that is
   interrupted, and its response cut off.
3. The data directory is released: what a `FAST` service hasn't committed yet is committed, `buckets.mvstore` is
   compacted to the size of the metadata it holds, and the file lock is given up, so that another service or process
   can open the directory.

What follows from that order:

+ **Close the service before what it uses.** An executor given to `events(events -> events.executor(...))` is to be
  shut down after `close()`, and a [change listener](#listen-to-bucket-and-object-changes) that calls into the IDE,
  e.g. to refresh a view, runs until `close()` has returned. `dispose()` of the service that holds LocalS3 is the place
  for `close()`, before the disposal of anything its listeners call.
+ **Turn the shutdown hook off.** A started service registers a JVM shutdown hook of its own by default, which stops it
  when the JVM exits. The IDE runs an orderly shutdown of its own, which disposes the service, and a hook would stop the
  service in parallel with it, possibly before the parts of the plugin that still use it. With
  `netty(netty -> netty.registerShutdownHook(false))` the IDE alone stops the service. An IDE that is killed, or
  crashes, then leaves the directory as a killed process does: a `FAST` service loses at most the last second, a
  `DURABLE` one nothing, and the file is compacted the next time the service is shut down.
+ **Don't block the UI thread.** `close()` takes up to the 5 seconds of step 2, plus the compaction of step 3. A plugin
  that stops or restarts the service on an action of the user, e.g. after a change of its port, does that on a
  background thread.
+ **Restart in order.** A new service over the same directory is started only once the old one is shut down, see
  [One data directory, one service](#one-data-directory-one-service). An `IN_MEMORY` service keeps its data across
  such a restart with `newService.takeOverDataOf(oldService)`, called after the old one is shut down and before the new
  one is started.

### Threads

The event loops of the service, and its request threads if they aren't virtual ones, are daemon threads by default;
virtual threads always are. A service that is never shut down therefore doesn't keep the JVM alive, and doesn't delay
the exit of the IDE. Keep that default in an IDE: `netty(netty -> netty.daemonThreads(false))` is for a `main` method
that starts the service and returns, which only non-daemon threads keep running.

The other side of it is that nothing waits for a daemon thread when the JVM exits: requests still running are dropped
on the spot, and a service that wasn't shut down leaves its data directory as a killed process does. That is why
`dispose()` must call `close()`, rather than leave the service to the exit of the JVM.

LocalS3 needs every Netty module on 4.2, which a plugin bundles. A single Netty 4.1 module that the plugin sees next to
them, e.g. one that the IDE ships, fails at runtime, since Netty 4.2 moved classes between modules;
`io.netty.util.Version.identify()` lists the versions that the plugin actually runs with.

The IDE inherits the `AWS_*` variables of the shell it was launched from; a plugin that builds its service with
`fromEnvironment()` doesn't take them for the credentials of the service, see
[Configure from the environment](#configure-from-the-environment). The [console](#look-at-what-is-in-the-service) at
`endpoint() + "/_admin/ui"` gives the user a view of what the service holds, e.g. from an action that opens it in the
browser.

## Spring Boot

`local-s3-spring-boot-starter` embeds LocalS3 in a Spring Boot 4 application, configured by `local-s3.*` properties,
and, when the application has the AWS SDK, which is an optional dependency, defines an `S3Client`, an `S3AsyncClient`
and an `S3Presigner` that point at it. `local-s3.seed.classpath` names a directory tree of the classpath that the
service starts with, as `<bucket>/<key>`. `local-s3.website.*` configures
[static website hosting](semantics.md#static-website-hosting): `enabled`, `all-buckets`, `index-document` and
`error-document`. `local-s3.cors.*` configures the [default CORS rule](semantics.md#cors) of the buckets without one
of their own: `allowed-origins`, `allowed-methods`, `allowed-headers`, `expose-headers` and `max-age`. See [its README](../local-s3-spring-boot-starter/README.md). The starter is on by default, so declare it for development and tests only (Gradle `developmentOnly` or
`testAndDevelopmentOnly`, a Maven `test` scope or profile). If the production jar includes it, the application
starts a local service and its `S3Client` points at it.

In a `@SpringBootTest`, the service listens on a random free port unless `local-s3.port` is set, so that the test
contexts that Spring caches side by side don't compete for port 29090; the client beans of the starter, and the
`${local.s3.endpoint}` placeholder, name the port it listens on. `@AutoConfigureLocalS3` also resets the data after each
test method.

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

+ `S3Client`, `S3AsyncClient` and `S3Presigner`
+ `S3TransferManager`, when the test brings `software.amazon.awssdk:s3-transfer-manager`
+ `S3VectorsClient` and `S3TablesClient`
+ `LocalS3Endpoint`
+ `com.robothy.s3.rest.LocalS3`, the service itself

The `S3AsyncClient`, the `S3Presigner` and the `S3TransferManager` are closed when the context they were injected into
ends. These are the clients that `local-s3-spring-boot-starter` defines as beans, so a test moves between the two
without changing what it asks for.

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

`buckets` creates the buckets before the test runs, and `versionedBuckets` creates them with versioning enabled, so a
test of a bucket that has versioning enabled in production needs no `PutBucketVersioning`:
`@LocalS3(buckets = "plain", versionedBuckets = "audit")`. `LocalS3.builder().versionedBuckets("audit")` does the same
for an embedded service.

Example 4: inject the service itself, to use what it offers besides the S3 API: `reset()` between the tests of a shared
service, `applyLifecycle(Instant)` to expire objects at a later time, or an `S3ChangeListener` to observe the changes
that the code under test makes. The service shares its simple name with the annotation, so one of the two is written
with its package; the extension shuts the service down, so a test must not.

```java
@LocalS3(buckets = "my-bucket")
class AppTest {

  @AfterEach
  void afterEach(com.robothy.s3.rest.LocalS3 localS3) {
    localS3.reset();
  }

  @Test
  void test(com.robothy.s3.rest.LocalS3 localS3, S3Client s3) {
    List<S3Change> changes = new CopyOnWriteArrayList<>();
    localS3.getS3Manager().addChangeListener(changes::add);
    s3.putObject(b -> b.bucket("my-bucket").key("a.txt"), RequestBody.fromString("a"));
    // changes holds the PutObject of a.txt

    localS3.applyLifecycle(Instant.now().plus(Duration.ofDays(31)));
  }
}
```

The attributes of `@LocalS3` configure the service like the builder does: `port`, `mode`, `dataPath` or
`dataPathSupplier`, `buckets`, `initialDataCacheEnabled`, `maxInMemoryBytes` (e.g. `"512m"`),
`compositeMultipartEtags`, `virtualHostDomains`, and `accessKey` with `secretKey`.

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
class AppTest {

  @Container
  LocalS3Container localS3 = new LocalS3Container("latest")
      .withMode(LocalS3Container.Mode.IN_MEMORY)
      .withCredentials("local-s3", "local-s3-secret")
      .withBuckets("my-bucket");

  @Test
  void s3Operations() {
    S3Client s3 = S3Client.builder()
        .endpointOverride(localS3.getEndpointUri())
        .region(Region.US_EAST_1)
        .credentialsProvider(StaticCredentialsProvider.create(
            AwsBasicCredentials.create(localS3.getAccessKey(), localS3.getSecretKey())))
        .forcePathStyle(true)
        .build();

    s3.putObject(b -> b.bucket("my-bucket").key("test.txt"), RequestBody.fromString("Hello World"));
  }

  @Test
  void s3VectorOperations() {
    S3VectorsClient vectors = S3VectorsClient.builder()
        .endpointOverride(localS3.getEndpointUri())
        .region(Region.US_EAST_1)
        .credentialsProvider(StaticCredentialsProvider.create(
            AwsBasicCredentials.create(localS3.getAccessKey(), localS3.getSecretKey())))
        .build();

    vectors.createVectorBucket(b -> b.vectorBucketName("my-vector-bucket"));
    vectors.createIndex(b -> b
        .vectorBucketName("my-vector-bucket")
        .indexName("my-index")
        .dimension(128)
        .dataType(DataType.FLOAT32)
        .distanceMetric(DistanceMetric.COSINE));
  }

}
```

### The port

**Docker allocates the host port** when the container starts, and `getPort()` returns it, so that test classes which
run at the same time can't pick the same port. `getEndpoint()` and `getEndpointUri()` assemble the URL from it, which
is what `endpointOverride` takes; `getPort()` before the container has started fails with an `IllegalStateException`
rather than answering a port nothing listens on.

`withHttpPort(29090)` binds a port of your own instead, for the cases that need one known in advance, e.g. a URL in a
configuration file; that port has to be free when the container starts. `withRandomHttpPort()` goes back to letting
Docker choose.

### Configuration

Every setting of the image is reachable. The methods below set the [environment
variables](deployment.md#configuration) of the same meaning, and anything they don't cover is `withEnv(name, value)`.

| Method | Sets |
|---|---|
| `withMode(Mode)` | `LOCAL_S3_MODE`: `IN_MEMORY` or `PERSISTENCE` |
| `withDataPath(String \| Path)` | binds a host directory at `/data` |
| `withPersistencePolicy(PersistencePolicy)` | `LOCAL_S3_PERSISTENCE_POLICY`: `DURABLE` or `FAST` |
| `withInMemoryMaxBytes("512m")` | `LOCAL_S3_IN_MEMORY_MAX_BYTES` |
| `withCredentials(accessKey, secretKey)` | `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`; `getAccessKey()` and `getSecretKey()` read them back |
| `withBuckets("one", "two")` | `AWS_BUCKETS`: the buckets to create on startup |
| `withVersionedBuckets("audit")` | `AWS_BUCKETS` as `audit:versioned`: the buckets to create with versioning enabled, besides those of `withBuckets` |
| `withIcebergCatalog(true)` | `LOCAL_S3_ICEBERG_CATALOG`: serve `/iceberg/v1` |
| `withIcebergWarehouse("s3://warehouse/")` | `LOCAL_S3_ICEBERG_WAREHOUSE` |
| `withVirtualHostDomains("s3", "s3.local")` | `LOCAL_S3_VIRTUAL_HOST_DOMAINS` |
| `withSelfSignedTls(hosts...)` | `LOCAL_S3_TLS_SELF_SIGNED`; `getEndpoint()` becomes an `https://` URL |
| `withTlsRequired(true)` | `LOCAL_S3_TLS_REQUIRED`: refuse plain HTTP |
| `withWebsite(false)` / `withWebsiteAllBuckets(true)` | `LOCAL_S3_WEBSITE`, `LOCAL_S3_WEBSITE_ALL_BUCKETS` |

Without `withCredentials(...)` the service accepts unsigned requests, so a client may sign with anything, and
`getAccessKey()` answers `null`.

An image of a private registry is run by the `DockerImageName` constructor:

```java
new LocalS3Container(DockerImageName.parse("registry.internal/local-s3:2.5.0")
    .asCompatibleSubstituteFor(LocalS3Container.IMAGE_NAME));
```

To wait for the health check rather than the startup log message, see [deployment.md](deployment.md#health-check).

### `@ServiceConnection` with Spring Cloud AWS

In a Spring Boot test, `@ServiceConnection` points the clients that [Spring Cloud AWS](https://awspring.io/)
auto-configures, e.g. `S3Client`, `S3Template` and `S3Presigner`, at the container, as it does for LocalStack. No
property of the application is needed: `local-s3-testcontainers` registers a `ConnectionDetailsFactory` that answers
with the `AwsConnectionDetails` of the container. It takes effect where the test classpath has
`spring-boot-testcontainers` and a Spring Cloud AWS starter, e.g. `spring-cloud-aws-starter-s3`.

```java
@SpringBootTest
@Testcontainers
class UploadTest {

  @Container
  @ServiceConnection
  static LocalS3Container localS3 = new LocalS3Container("latest")
      .withBuckets("uploads");

  @Autowired
  S3Template s3Template;

  @Test
  void uploads() {
    s3Template.upload("uploads", "hello.txt", new ByteArrayInputStream("Hello".getBytes()));
  }

}
```

| `AwsConnectionDetails` | Value |
|---|---|
| endpoint | `getEndpoint()` with the host resolved to an IP address, e.g. `http://127.0.0.1:32773` |
| region | `us-east-1` |
| access key, secret key | those of `withCredentials(...)`; `local-s3` for both when the container accepts unsigned requests |

The endpoint is an IP address because the AWS SDK addresses buckets by path on an IP address, and by host name, e.g.
`uploads.localhost`, on anything else, which the service doesn't serve unless configured with
`withVirtualHostDomains(...)`; so no `spring.cloud.aws.s3.path-style-access-enabled` is needed either.

### Docker Compose with Spring Cloud AWS

An application that runs LocalS3 as a service of its `compose.yaml`, rather than embedding it, gets the same service
connection from [Spring Boot's Docker Compose support](https://docs.spring.io/spring-boot/reference/features/dev-services.html#features.dev-services.docker-compose):
`local-s3-testcontainers` registers a `DockerComposeConnectionDetailsFactory` too, so the clients of Spring Cloud AWS
reach the service with no property of the application.

```yaml
# compose.yaml
services:
  local-s3:
    image: luofuxiang/local-s3
    ports:
      - "29090"
```

```groovy
dependencies {
    implementation("io.awspring.cloud:spring-cloud-aws-starter-s3")
    developmentOnly("org.springframework.boot:spring-boot-docker-compose")
    developmentOnly("io.github.robothy:local-s3-testcontainers:<version>")
}
```

It matches a service of the image `luofuxiang/local-s3`; a service of another image, e.g. of a private registry, is
matched by the label `org.springframework.boot.service-connection: luofuxiang/local-s3`. The connection details follow
the environment of the service:

| `AwsConnectionDetails` | Value |
|---|---|
| endpoint | the host port mapped to `LOCAL_S3_PORT` (`29090` by default), with the host resolved to an IP address; `https://` where `LOCAL_S3_TLS_CERT` or `LOCAL_S3_TLS_SELF_SIGNED` is set |
| region | `us-east-1` |
| access key, secret key | `LOCAL_S3_ACCESS_KEY_ID` and `LOCAL_S3_SECRET_ACCESS_KEY`, or `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY` (the image sets `LOCAL_S3_CREDENTIALS_FROM_AWS_ENV=true`); `local-s3` for both when the service accepts unsigned requests |
