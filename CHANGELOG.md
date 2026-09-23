# Changelog

All notable changes to LocalS3 are documented in this file. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

Every module is released with the same version, to Maven Central under `io.github.robothy`, and as the Docker image
`luofuxiang/local-s3`.

## [2.5.0] - Unreleased

2.5 is a large release. Most of its changes add to what 2.4 did, but several change defaults, and **the data directory
of a `PERSISTENCE` service has a new format that 2.5 doesn't migrate**. Read [Upgrading from 2.4](#upgrading-from-24)
before upgrading.

### Upgrading from 2.4

#### Data directory

The metadata of the buckets moved from one JSON file per bucket, `<bucket>.bucket.meta` and
`vectors/<bucket>.vectorbucket.meta`, into a single [H2 MVStore](https://www.h2database.com/html/mvstore.html) file,
`buckets.mvstore`; see [the persistence layout](docs/architecture.md#persistence-layout).

**2.5 does not read the `*.bucket.meta` files.** A data directory of 2.4 opened by 2.5 shows no buckets, although the
content of its objects is still under `.storage/` (2.5 moves those files into subdirectories on start, which 2.4 can no
longer read either). Back up the data directory before opening it with 2.5.

To carry the objects over, copy them through the S3 API from a 2.4 service to a 2.5 service, e.g. with
[s5cmd](https://github.com/peak/s5cmd):

```shell
export AWS_ACCESS_KEY_ID=any AWS_SECRET_ACCESS_KEY=any AWS_REGION=us-east-1
OLD=http://localhost:8080   # 2.4 serving a copy of the old data directory
NEW=http://localhost:29090  # 2.5 serving a new, empty data directory

for bucket in $(s5cmd --endpoint-url "$OLD" ls | awk '{print $NF}' | sed 's|s3://||;s|/$||'); do
  s5cmd --endpoint-url "$OLD" cp "s3://$bucket/*" "export/$bucket/"
  s5cmd --endpoint-url "$NEW" mb "s3://$bucket"
  s5cmd --endpoint-url "$NEW" cp "export/$bucket/*" "s3://$bucket/"
done
```

This copies the current version of every object. It doesn't carry over older versions, delete markers, tags, ACLs,
user-defined metadata, the settings of the buckets (versioning, CORS, policies, …), multipart uploads in progress, or
vector buckets; set those up again, e.g. with the code that created them in the first place. An `IN_MEMORY` service
that starts from a 2.4 data directory has the same problem, so recreate its initial data with 2.5.

The vectors of 2.4 were written to `.storage/` of the working directory rather than to the data directory, and are not
imported either.

#### Defaults and configuration

| | 2.4 | 2.5 |
|---|---|---|
| Java | 17 | **21** |
| Port of an embedded service | `8080` | `29090` |
| Host that an embedded service binds | all interfaces | `127.0.0.1`; use `acceptFromAnyHost()` or `bindHost("0.0.0.0")` |
| Port of the Docker image | `80` | `29090` |
| Mode variable of the Docker image | `MODE` | `LOCAL_S3_MODE` |
| User of the Docker image | `root` | `locals3`; a bind-mounted data directory must be writable by it |
| Threads of an embedded service | non-daemon | daemon, so a service doesn't keep the JVM alive; `netty(netty -> netty.daemonThreads(false))` restores the old behavior |
| Request handling | 4 platform threads | a virtual thread per request (`netty(netty -> netty.virtualThreads(...))`) |
| Entity tag of a completed multipart upload | MD5 of the whole content | MD5 of the part digests with a `-<parts>` suffix, like Amazon S3; `s3Api(s3 -> s3.compositeMultipartEtags(false))` restores the old one |
| Bucket names | not validated | the naming rules of Amazon S3; invalid names fail with `InvalidBucketName` |
| Part size of a multipart upload | not validated | at least 5 MiB except the last part; otherwise `EntityTooSmall` |
| Content of an `IN_MEMORY` service | unbounded, until the JVM runs out of heap | at most half the max heap; beyond it `507 InsufficientStorage` (`storage(storage -> storage.maxInMemoryBytes(...))`, `LOCAL_S3_IN_MEMORY_MAX_BYTES`, `local-s3.in-memory.max-size`) |

`LocalS3Container` of 2.5 expects port `29090` and `LOCAL_S3_MODE`, so use it with an image of 2.5 or later.
Docker allocates its host port now, so `getPort()` is answered once the container has started, and raises an
`IllegalStateException` before that rather than returning `0`; `withHttpPort(port)` still binds a port of your own.

#### API

+ The builder is the top-level class `LocalS3Builder`, still returned by `LocalS3.builder()`. Code that names the type
  `LocalS3.Builder` needs to use `LocalS3Builder`.
+ **The settings of a domain are grouped behind one builder method**, so that the knobs only some services tune stay out
  of the way of the ones every service is built with; see
  [Settings grouped by domain](docs/embedding.md#settings-grouped-by-domain).

  | 2.4 / earlier 2.5 snapshots | 2.5 |
  |---|---|
  | `persistencePolicy(p)`, `maxInMemoryBytes(n)`, `initialDataCacheEnabled(b)` | the same names under `storage(storage -> ...)`, which also takes `mode(m)` and `dataPath(p)` |
  | `nettyParentEventGroupThreadNum(n)` | `netty(netty -> netty.parentEventGroupThreadNum(n))` |
  | `nettyChildEventGroupThreadNum(n)` | `netty(netty -> netty.childEventGroupThreadNum(n))` |
  | `s3ExecutorThreadNum(n)` | `netty(netty -> netty.s3ExecutorThreadNum(n))` |
  | `virtualThreads(b)`, `daemonThreads(b)` | `netty(netty -> netty.virtualThreads(b).daemonThreads(b))` |
  | `maxRequestBodySize(n)`, `requestBodyFileThreshold(n)`, `maxRequestHeaderSize(n)`, `idleConnectionTimeoutSeconds(n)` | the same names under `netty(netty -> ...)` |
  | `registerShutdownHook(b)`, `requestRecorder(r)` | the same names under `netty(netty -> ...)` |
  | `virtualHostDomains(d...)`, `compositeMultipartEtags(b)` | the same names under `s3Api(s3 -> ...)` |
  | `changeListenerExecutor(e)` | `events(events -> events.executor(e))`, which also takes `listener(l)` |
  | `tlsSelfSigned()`, `tlsRequired(b)` | `tls(tls -> tls.selfSigned().required(b))` |
  | `websiteAllBuckets(b)`, `websiteIndexDocument(s)`, `websiteErrorDocument(s)`, `website(LocalS3Website)` | `website(website -> website.allBuckets(b).indexDocument(s).errorDocument(s))`, `website(website -> website.settings(...))` |
  | `icebergWarehouse(s)`, `icebergCatalog(LocalS3IcebergCatalog)` | `icebergCatalog(iceberg -> iceberg.warehouse(s))`, `icebergCatalog(iceberg -> iceberg.settings(...))` |

  The methods that 2.4 had are kept as deprecated delegates, so code of 2.4 still compiles and behaves the same; the
  ones added during 2.5 are gone. What every service is built with stays a method of the builder itself: `bindHost`,
  `acceptFromAnyHost`, `port`, `mode`, `dataPath`, `buckets`, `credentials`, `seeder`, `changeListener` and
  `fromEnvironment`, beside the one-liners that turn a domain on with its defaults, `tls(certPem, keyPem)`,
  `tls(LocalS3Tls)`, `website(true)` and `icebergCatalog(true)`. The environment variables and the `local-s3.*`
  properties are unchanged.
+ `local-s3-jupiter` no longer injects the AWS SDK v1 `AmazonS3`, and no longer depends on the v1 SDK. Use `S3Client`.
+ `@LocalS3(inmemory = ...)`, deprecated before, is removed. Use `mode`.
+ LocalS3 uses **Jackson 3** (`tools.jackson`, 3.2) instead of Jackson 2, like Spring Boot 4 does. The types of the public
  API that come from Jackson change package with it, e.g. `tools.jackson.databind.JsonNode` for the metadata of a vector
  and `tools.jackson.dataformat.xml.XmlMapper` for the mapper of the XML documents; the annotations of the models are
  still `com.fasterxml.jackson.annotation`. The mappers of the service are configured with the defaults of Jackson 2, so
  the XML and JSON documents it writes, and the metadata of a data directory, stay the same.
+ The module `local-s3-docker` is renamed to `local-s3-standalone`, and `local-s3-integrationtest` to
  `local-s3-integration-test`.
+ `S3Error` writes no field that is `null`, rather than an empty element, and gains `hostId` for the `<HostId>` of an
  error; `LocalS3Exception` gains `getKey()` and `getVersionId()` next to `getBucketName()`. Code that asserts on the
  message of a `NoSuchKey`, `NoSuchBucket` or `NoSuchVersion` reads the field instead; see [Changed](#changed).

### Added

+ **The Amazon S3 Tables API**, on the S3 port and always on: table buckets, their namespaces and tables, and the
  `UpdateTableMetadataLocation` commits that move a table from one metadata file to the next. All 49 operations are
  answered, so an `S3TablesClient` of the AWS SDK and the `s3-tables-catalog` library of Iceberg both run against
  LocalS3. Every table bucket is **also served as an Iceberg REST catalog of its own**, reached by naming the ARN of the
  table bucket as the warehouse of a `RESTCatalog` — the way Amazon documents its own Iceberg REST endpoint — so Spark,
  Trino and PyIceberg reach the same tables the control plane creates, and a commit made through either is what the
  other then reads. The tables live in an ordinary bucket of the same service, `<table-bucket>--table-s3`, so an
  engine's `S3FileIO` writes its data files there and a test can read them with an `S3Client`. A request of this API is
  told from an Amazon S3 one by the `s3tables` service in its credential scope, because the two share their paths; a
  client that signs nothing reaches it under `/s3tables`. `@LocalS3` injects a configured `S3TablesClient`, and
  `LocalS3Container.getS3TablesEndpoint()` points one at a container. Encryption, storage class, policies, maintenance,
  metrics, replication and record expiration are stored and read back, and nothing happens. See
  [Amazon S3 Tables](docs/data-tools.md#amazon-s3-tables).

+ **A built-in console**, served at `GET /_admin/ui`. One self-contained HTML page — no build step, no frontend
  framework and no new dependency — that lists the buckets and creates one, walks the objects of a bucket by their
  prefixes, previews or downloads an object, and uploads or deletes one. Text, JSON, CSV, Markdown, images, audio,
  video, PDF and HTML render in the page and anything else is a download; `Copy URL` yields the S3 URL of the object;
  **files and folders dropped on the page** are uploaded into the prefix that is open, a dropped folder becoming a
  prefix, with a panel showing the progress of the batch; `Delete` removes an object after a confirmation; and
  `+ NEW` creates a bucket, under the naming rules of Amazon S3. It answers what an S3 mock otherwise leaves to
  `aws s3 ls`, which matters when LocalS3 is embedded in an IDE or an application, and when an AI agent stores its
  artifacts in it and a person has to review them.

  Behind the page are six endpoints — `/_admin/ui/buckets`, `/_admin/ui/objects`, `GET`, `PUT` and `DELETE` of
  `/_admin/ui/object`, and `PUT /_admin/ui/bucket` — which call the same services the S3 operations do, so what the
  console creates, uploads and deletes reaches the change listeners like any other change. It deletes no bucket and
  changes no bucket configuration. A browser can't sign a request with SigV4, so the console is guarded with HTTP
  Basic authentication instead: a service configured with credentials asks for the access key ID and the secret
  access key, and one without them serves the console like it serves unsigned S3 requests. A request that changes
  something also wants the `X-LocalS3-Console` header, which no other origin can send, so a page open elsewhere in
  the browser can't write through it. The S3 API is unaffected, and the requests of the console aren't recorded in
  the statistics. See [Console](docs/deployment.md#console).
+ **Connection snippets**, written by the service for the service: `GET /_admin/snippets` answers the configuration
  that DuckDB, the AWS CLI, boto3, PyIceberg and Spark need to reach it — the host the request addressed, plain HTTP
  or HTTPS, path-style addressing, the credentials and, if it serves one, the `ATTACH` of the Iceberg catalog — and
  `GET /_admin/snippets/duckdb` answers one of them as text, so
  `duckdb -init <(curl -s localhost:29090/_admin/snippets/duckdb)` opens a DuckDB that reaches LocalS3. With `bucket`
  and `key`, the DuckDB script ends with a query of that object or prefix, which `curl ... | duckdb` runs. The console shows them under `Connect`, and `DuckDB SQL` in the preview of an object. The mismatched
  `USE_SSL`, `URL_STYLE` or `ENDPOINT` of a hand-written DuckDB secret is the most common first failure; these are
  generated instead. See [Admin endpoints](docs/deployment.md#admin-endpoints).

+ **`localS3.endpoint()` and `localS3.presign(...)`.** `endpoint()` is the URL that clients reach the running service
  at, e.g. `http://127.0.0.1:29090`, instead of every embedding application assembling it from the scheme, the bind
  host and the port; a service bound to every interface is named by its loopback address, and one serving TLS by an
  `https` URL. `presign(bucket, key, expiration)` signs a URL of an object that is valid for a while — `GET` by
  default, `presign(bucket, key, expiration, "PUT")` for an upload slot — so an application that holds the service can
  hand an artifact to a browser, a teammate or a tool without credentials and without building a second client. The
  URL carries the signature that the service already verifies; a service without `credentials(...)` answers unsigned
  requests, so it returns the plain URL of the object instead. See
  [Presigned URLs](docs/embedding.md#presigned-urls).

+ **A service that is open to the network says so when it starts.** A service that has no credentials and binds an
  address other than a loopback one — which the Docker image does, since a container serves its host — answers every
  request, whoever sends it. It now logs a `WARN` naming the address it listens on and how to close it, so that
  `docker run -p 29090:29090 luofuxiang/local-s3` on a shared network isn't an open object store that nothing
  mentioned. `LocalS3Config.reachableFromOtherHosts()` is the condition, beside `authenticationEnabled()`. The
  service still starts: an open service on a private network is a valid setup, and the logger of
  `com.robothy.s3.rest.LocalS3` silences the warning. See [Docker](docs/deployment.md#docker).

+ **Delta Lake is covered end to end.** `DeltaLakeIntegrationTest` drives
  [delta-kernel-java](https://delta.io/blog/delta-kernel/), the Delta client without Spark, against LocalS3: creating a
  table, writing and reading rows, two writers racing for the same version, time travel to an earlier version, and a
  reader that shares nothing with the writer but the bucket. It proves the claim the README makes — the commit protocol
  of Delta rests on `If-None-Match: *` creating `_delta_log/<version>.json` only when no other writer did, and on the
  `412` that LocalS3 answers the loser with. See [Delta Lake](docs/data-tools.md#delta-lake).

+ **A built-in Iceberg REST catalog**, off by default: `icebergCatalog(true)`, `@LocalS3(icebergCatalog = true)`,
  `local-s3.iceberg-catalog.enabled` or `LOCAL_S3_ICEBERG_CATALOG=true` serves an
  [Iceberg REST catalog](https://iceberg.apache.org/spec/#rest-catalog) under `/iceberg/v1` on the same port, so a
  lakehouse test needs one process rather than a catalog — Polaris, Lakekeeper, Nessie or JDBC — beside the object
  store. Namespaces, tables, views, commits and multi-table transactions are served; a commit moves the pointer of a
  table with a compare-and-set, so a writer that lost the race is answered `409 CommitFailedException` and its client
  retries, and concurrent appends never lose one another's rows. The tables live in LocalS3 itself, under the warehouse
  `s3://warehouse/` by default, so an `IN_MEMORY` service holds them in memory and a `PERSISTENCE` service keeps them
  in its data directory. `GET /v1/config` and every loaded table vend the endpoint, path-style access and the
  credentials of the service, so a client configured with the catalog URI alone reaches the storage — including DuckDB,
  which attaches the catalog as a database with `ATTACH 'warehouse' AS ice (TYPE ICEBERG, ENDPOINT '.../iceberg',
  AUTHORIZATION_TYPE 'none')` and creates, writes and queries tables through it without an S3 secret. LocalS3 builds the
  table metadata itself and gains no dependency on Iceberg — and because it does, the catalog is run against **Iceberg's
  own suites for a catalog implementation**, `CatalogTests` and `ViewCatalogTests` of the `iceberg-core` test jar, so
  that a new Iceberg release is checked by bumping the version and running `dataToolsTest` rather than by a user hitting
  the gap; `IcebergProtocolCoverageTest` asks the Iceberg library which commit updates and requirements exist and checks
  that the catalog knows every one of them. `icebergCatalog(iceberg -> iceberg.uniqueTableLocation(true))` gives every
  table a location of its own, the `unique-table-location` of the Iceberg catalogs.
  See [the built-in Iceberg REST catalog](docs/data-tools.md#the-built-in-iceberg-rest-catalog).

+ **Bounded in-memory storage**: `storage(storage -> storage.maxInMemoryBytes(bytes))`,
  `LOCAL_S3_IN_MEMORY_MAX_BYTES` (e.g. `512m`) and `local-s3.in-memory.max-size` limit the heap that the objects and parts of an `IN_MEMORY` service take, half the max
  heap by default. An upload beyond it is answered with `507 InsufficientStorage`, whose message suggests the
  `PERSISTENCE` mode, instead of an `OutOfMemoryError` that takes the embedding application or IDE down.
+ **Signed requests**: `credentials(accessKeyId, secretAccessKey)`, `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` and
  `@LocalS3(accessKey, secretKey)` verify AWS Signature Version 4, before the body of a request is received.
+ **STS temporary credentials**: a stateless STS endpoint on the same port answers `AssumeRole`, `GetSessionToken` and
  `GetCallerIdentity`, like the one of MinIO, and requests signed with the temporary credentials it issues are accepted,
  with their session token in `x-amz-security-token`, a presigned URL or a form upload. The credentials survive restarts
  and are revoked by changing the secret access key. See [temporary credentials](docs/embedding.md#temporary-credentials-sts).
+ **HTTPS**: `tls(certPem, keyPem)` and `LOCAL_S3_TLS_CERT` / `LOCAL_S3_TLS_KEY` serve TLS with a PEM certificate and
  key, e.g. created by [mkcert](https://github.com/FiloSottile/mkcert), so clients that use HTTPS by default, such as
  DuckDB, Hadoop S3A or the `object_store` crate, connect without turning it off. See [HTTPS](docs/deployment.md#https).
+ **A self-signed certificate on startup**: `tls(LocalS3Tls.selfSigned())` and `LOCAL_S3_TLS_SELF_SIGNED=true` generate
  a certificate for `localhost`, `127.0.0.1` and `::1`, or for the hosts given, so HTTPS needs nothing installed. The
  service logs the certificate in PEM format to hand to a client, e.g. `curl --cacert`, `AWS_CA_BUNDLE` or the
  `ca_cert_file` of DuckDB, and `LocalS3Tls.newClientSslContext()` and `trustManagers()` trust it in the JVM that
  embeds the service. See [Generate a certificate on startup](docs/deployment.md#generate-a-certificate-on-startup).
+ **Conditional requests**: `If-Match`, `If-None-Match`, `If-Modified-Since` and `If-Unmodified-Since` for reads;
  conditional writes for `PutObject`, `CopyObject` and `CompleteMultipartUpload`; conditional deletes for
  `DeleteObject` and `DeleteObjects`; `x-amz-copy-source-if-*` for `CopyObject` and `UploadPartCopy`. See
  [semantics](docs/semantics.md#conditional-requests).
+ **Operations**: `UploadPartCopy`, `GetObjectAttributes`, `GetObjectAcl`, `PutObjectAcl`, `ListMultipartUploads`,
  `GetBucketCors`, `PutBucketCors`, `DeleteBucketCors`, `GetBucketPolicyStatus`, and CORS preflight requests.
+ **Flexible checksums**: a `CRC32`, `CRC32C`, `CRC64NVME`, `SHA1` or `SHA256` checksum that a request sends in an
  `x-amz-checksum-*` header, or in the trailer of an `aws-chunked` body like the AWS SDKs do by default, is verified
  (`400 BadDigest` on a mismatch) and stored with the object or the part. `PutObject`, `UploadPart`,
  `CompleteMultipartUpload`, `CopyObject`, `UploadPartCopy`, `ListParts`, `GetObjectAttributes`, `ListObjects(V2)` and
  `ListObjectVersions` answer it, and so do `GetObject` and `HeadObject` when asked with `x-amz-checksum-mode: ENABLED`,
  except for a range. `CreateMultipartUpload` takes `x-amz-checksum-algorithm` and `x-amz-checksum-type`: a
  `COMPOSITE` checksum is the checksum of the checksums of the parts, and a `FULL_OBJECT` CRC is combined from the
  CRCs of the parts without reading their content. An object that is stored without a checksum still gets none, and
  `POST Object` doesn't take one.
+ **Server-side encryption headers**: `x-amz-server-side-encryption` (`AES256`, `aws:kms`, `aws:kms:dsse`) and its
  `-aws-kms-key-id`, `-context` and `-bucket-key-enabled` headers are validated, stored with the object or the
  multipart upload, and answered by the operations that store or serve it; an object stored without them gets the
  default encryption of its bucket. Customer-provided keys (SSE-C) are validated, and a read needs the key the object
  was stored with. Nothing is encrypted. See
  [semantics](docs/semantics.md#server-side-encryption-with-s3-managed-and-kms-keys-sse-s3-sse-kms).
+ **Browser form uploads**: `POST Object` stores the file of an HTML form posted to a bucket, with the policy
  document and its Signature Version 4 or 2 signature checked like Amazon S3 checks them: expiration, `eq`,
  `starts-with` and `content-length-range` conditions, and fields that no condition names. The policy of a form is
  checked even by a service without credentials. `success_action_redirect` and `success_action_status` are honored.
  See [semantics](docs/semantics.md#browser-form-uploads-post-object).
+ **Lifecycle configurations**: `PutBucketLifecycleConfiguration`, `GetBucketLifecycleConfiguration` and
  `DeleteBucketLifecycle` store, return and delete the configuration of a bucket instead of answering
  `501 NotImplemented`. The configuration is validated like Amazon S3 validates its structure, but its rules are
  **never applied**. See [semantics](docs/semantics.md#lifecycle-configuration).
  Paginated `ListBuckets`. Operations that LocalS3 knows but doesn't implement answer `501 NotImplemented` naming the
  operation; see [the API list](docs/apis.md).
+ **Virtual-hosted-style requests**, for `localhost`, Amazon S3, Alibaba Cloud OSS, Cloudflare R2 and Tigris hosts,
  and for the domains of `s3Api(s3 -> s3.virtualHostDomains(...))` / `LOCAL_S3_VIRTUAL_HOST_DOMAINS`.
+ **Change listeners**: `changeListener` and `events(events -> events.listener(...).executor(...))` deliver an
  `S3Change` for every committed change of a bucket or an object. See
  [semantics](docs/semantics.md#change-events).
+ **Operations endpoints**: the health check `GET /_health`, and `GET /_admin/stats`, `GET /_admin/requests` and
  `POST /_admin/reset`, also available as `LocalS3#statistics()` and `LocalS3#reset()`. See
  [deployment](docs/deployment.md#admin-endpoints).
+ **Configuration from the environment**: `LocalS3Builder.fromEnvironment()` and the `LOCAL_S3_*` variables, also as
  system properties; `buckets(...)` / `AWS_BUCKETS` create buckets on startup. The immutable `LocalS3Config` holds the
  configuration of a service.
+ **Persistence**: `storage(storage -> storage.persistencePolicy(DURABLE | FAST))` / `LOCAL_S3_PERSISTENCE_POLICY`; object metadata is read lazily
  from the store, bounded by `LOCAL_S3_OBJECT_METADATA_CACHE_MAX_ENTRIES`, so large data directories open quickly.
  `DURABLE` (the default of `LocalS3Builder`, the standalone jar and the Docker image) commits every change before
  answering, which survives a killed process but not a power loss, since a commit isn't an `fsync`; the Spring Boot
  starter defaults to `FAST` (`local-s3.persistence-policy`). The concurrent writers of a bucket share a commit, and
  `buckets.mvstore` is compacted while the service runs, once most of it is room that superseded chunks take, as well
  as on shutdown. See [persistence policy](docs/deployment.md#persistence-policy).
+ **Limits**, under `netty(netty -> ...)`: `maxRequestBodySize` (5 GiB by default), `requestBodyFileThreshold`,
  `maxRequestHeaderSize` and `idleConnectionTimeoutSeconds`. Large request bodies are buffered in files rather than on the heap.
+ **`local-s3-spring-boot-starter`** for Spring Boot 4 applications.
+ **`local-s3-standalone`** is published to Maven Central as an executable jar.
+ **Docker image**: a `HEALTHCHECK`, `JAVA_OPTS` defaulting to `-XX:MaxRAMPercentage=75.0`, and a non-root user.
+ **JUnit 5**: `@LocalS3` attributes `buckets`, `compositeMultipartEtags`, `virtualHostDomains`, `accessKey` and
  `secretKey`; `@Nested` classes get the service of their enclosing class, and services work with parallel tests.
+ `LocalS3` is `AutoCloseable`.
+ `QueryVectors` returns the `distanceMetric` of the index.
+ **[Choosing an S3 mock](docs/comparison.md)**, a selection page: where LocalS3 sits next to Adobe S3Mock, s3proxy,
  MinIO and LocalStack, what it does that they don't — the built-in Iceberg REST catalog, S3 Vectors, persistence with
  initial data and seeders, measured startup cost, the depth of the JUnit 5 and Spring Boot integrations — and where
  one of the others is the better choice.
+ **`LocalS3Container` configures the whole image.** `withCredentials(accessKey, secretKey)`, `withBuckets(...)`,
  `withIcebergCatalog(...)` with `withIcebergWarehouse(...)`, `withPersistencePolicy(...)`,
  `withInMemoryMaxBytes(...)`, `withVirtualHostDomains(...)`, `withSelfSignedTls(...)` with `withTlsRequired(...)`,
  and `withWebsite(...)` with `withWebsiteAllBuckets(...)` set the environment variables that a test used to spell out
  with `withEnv`. `getEndpoint()` and `getEndpointUri()` are the URL of the running container, which
  `endpointOverride` takes, and `getAccessKey()` with `getSecretKey()` read back the credentials it requires.
  `withDataPath(Path)` takes a `@TempDir`, and the `DockerImageName` constructor is public, so an image of a private
  registry declared `asCompatibleSubstituteFor(LocalS3Container.IMAGE_NAME)` runs. See
  [Testcontainers](docs/embedding.md#testcontainers).

### Changed

+ LocalS3 no longer depends on `commons-codec`, `commons-io`, `commons-lang3` and `commons-collections4`: the JDK
  does what they did. An application that used them through LocalS3 needs to declare them itself. The executable jar
  is about 2.6 MB smaller.
+ All metadata of a data directory, of S3 and S3 Vectors alike, is kept in `buckets.mvstore`, and a change writes only
  the object keys it changed rather than the whole bucket.
+ A change of a vector bucket writes only the vectors and indexes it changed, rather than the whole bucket: putting
  vectors into a large index no longer costs time and heap in proportion to the index. A `buckets.mvstore` of an
  earlier 2.5 snapshot is converted when it is opened for writing.
+ Object content files are spread over two levels of subdirectories, `.storage/ab/cd/<id>`; vectors are stored in one
  file per dimension, `vectors/.storage/vectors-<d>.vec`.
+ The change of a bucket, its persistence and the deletion of the content it replaced form a transaction: a failed
  change leaves neither metadata nor content behind.
+ The locks of the buckets belong to a service instead of the JVM, so services in the same JVM don't block each other.
+ Error responses use the error codes and status codes of Amazon S3.
+ An `<Error>` document names only the fields the error carries, and names what the request addressed in fields of
  its own rather than in the message, like Amazon S3 does. Through 2.4 every field was written, empty when it had no
  value, and the key or the bucket was part of the message:

  ```xml
  <!-- 2.4 -->
  <Error><Code>NoSuchKey</Code><Message>Object key 'a.txt' not exists.</Message><RequestId>…</RequestId>
    <ArgumentName/><ArgumentValue/><BucketName/><Key/><VersionId/></Error>
  <!-- 2.5 -->
  <Error><Code>NoSuchKey</Code><Message>The specified key does not exist.</Message>
    <BucketName>my-bucket</BucketName><Key>a.txt</Key><RequestId>…</RequestId><HostId>…</HostId></Error>
  ```

  Code that read an empty `<Key/>` as "present but empty" now finds no element; code that parsed the key or the
  bucket out of the message reads `<Key>` and `<BucketName>` instead. The messages of `NoSuchKey`, `NoSuchBucket` and
  `NoSuchVersion` are the ones of Amazon S3.
+ Every response carries an `x-amz-id-2`, like Amazon S3 answers on every response, and an error repeats it in the
  `<HostId>` of its body, as it repeats `x-amz-request-id` in `<RequestId>`. The AWS SDK reports it as the extended
  request ID. It is 76 random characters; LocalS3 serves every request itself, so it identifies no host.
+ The root element of an XML response declares the namespace of Amazon S3,
  `xmlns="http://s3.amazonaws.com/doc/2006-03-01/"`, which its children inherit, so a namespace-aware parser, e.g. an
  XPath bound to the namespace or a strict XML binding, reads a response of LocalS3 like one of Amazon S3. An
  `<Error>` declares none, like an error of Amazon S3. The AWS SDK, boto3 and the other clients that ignore
  namespaces are unaffected.
+ `HeadObject` answers `x-amz-delete-marker` only for a version that is a delete marker, like Amazon S3 does and like
  `GetObject` already did. Through 2.4 every response carried the header, `false` for an ordinary object.
+ A `<Deleted>` of a `DeleteObjects` result names `<DeleteMarker>` and `<DeleteMarkerVersionId>` only when the deletion
  created a delete marker, and `<VersionId>` only when it deleted a version, like Amazon S3 does. A bucket that was
  never versioned answers `<Deleted><Key>a.txt</Key></Deleted>`; through 2.4 it also wrote
  `<DeleteMarker>false</DeleteMarker>`, `<DeleteMarkerVersionId/>` and `<VersionId/>`. The AWS SDK reads the absent
  fields as `null` rather than as `false` and `""`.
+ Netty's event loops only parse and write; requests are handled on an executor, and a connection isn't read while
  its request is in flight.

### Fixed

+ `docker run luofuxiang/local-s3` with no arguments works. The image runs `PERSISTENCE` over `/data`, and the
  anonymous volume that `docker run` creates for it is initialized from the `/data` of the image, its ownership
  included; that directory belonged to `root`, so the service, which runs as `locals3` since 2.5, started and then
  failed to open its store with `AccessDeniedException: /data/buckets.mvstore`. The image now creates `/data` owned
  by `locals3`. A bind-mounted directory keeps the ownership it has on the host and must still be writable by that
  user.
+ The jar and the image give an `IN_MEMORY` service the data path of the container, `/data`, only when that directory
  exists, which is what [deployment](docs/deployment.md) already described: an `IN_MEMORY` service reads a data path
  for its initial data alone, so the jar on a machine without `/data` reported a data path it never read, in its
  startup log, in `GET /_admin/stats` and in the log of the vector storage, as though a volume were mounted. A
  `PERSISTENCE` service, and an `IN_MEMORY` service whose `/data` is there, e.g. the volume of the image, are
  unchanged, as is a `LOCAL_S3_DATA_PATH` of your own.
+ `PutObjectAcl` and `PutBucketAcl` accept a canned ACL (`x-amz-acl`) or grant headers (`x-amz-grant-*`) without a
  body, e.g. `aws s3api put-object-acl --acl public-read`, instead of failing with `500 InternalError`; a request
  without any ACL fails with `MissingSecurityHeader`, and a malformed one with `MalformedACLError`, like Amazon S3.
  See [semantics](docs/semantics.md#access-control-lists).
+ The content files that a `PERSISTENCE` data directory no longer references, e.g. after a crash or a failed delete, are
  deleted in the background when a service opens the directory, rather than kept forever. See
  [architecture](docs/architecture.md#a-change).
+ Starting a `PERSISTENCE` service over the data directory of another service of the same JVM no longer deletes the
  temporary files of that service's uploads in progress, which failed them. The temporary files that a process which
  died left behind are deleted by the background sweep of unreferenced content instead, once they are a minute old.
+ A single `PutObject` or `UploadPart` of more than 2 GiB and up to `maxRequestBodySize` (5 GiB) is stored instead of
  failing with `InternalError`: such a body is read from its temporary file rather than memory-mapped.
+ The data of S3 Vectors is written to the data directory instead of the working directory.
+ `QueryVectors` computes distances in double precision rather than rounding every product and difference of two
  components to a float, which ranked candidates of close distances in the wrong order, and ranks candidates at the
  same distance by vector key, so that a query answers the same vectors in the same order every time.
+ `ListObjectVersions` no longer answers every version with an empty `<CheckSumAlgorithm/>`, an element that Amazon S3
  spells `ChecksumAlgorithm`, and leaves it out for a version stored without a checksum.
+ Continuation tokens of `ListObjectsV2` with `encoding-type=url` no longer repeat pages.
+ `max-keys`, opaque continuation tokens, `x-amz-version-id: null`, and `Last-Modified` and `ETag` headers behave like
  Amazon S3.
+ Reading an object on Windows no longer holds a lock that prevents deleting it.
+ **`LocalS3Container` lets Docker allocate the host port**, rather than picking a free one itself and binding it as a
  fixed port. Between opening a `ServerSocket(0)` to find the port and the container binding it, any other process
  could take it, so test classes running at the same time failed now and then with `port is already allocated`. The
  port is now allocated by Docker while it creates the container, and `getPort()` answers with it once the container
  has started; before that it fails with an `IllegalStateException` instead of answering `0`, a port nothing listens
  on. `withRandomHttpPort()` still selects this behaviour, which is now the default, and `withHttpPort(port)` still
  binds a port of your own — and no longer leaves the earlier binding in place when it is called twice.

## [2.4] - 2026-04-03

### Added

+ Multi-platform Docker images for `linux/amd64` and `linux/arm64`.
+ Byte range requests for `GetObject` and `HeadObject`.

### Fixed

+ `ListObjects` with a `delimiter`.

## Earlier versions

The changes of 2.3 and earlier are recorded in the
[commit history](https://github.com/Robothy/local-s3/commits/main).

[2.5.0]: https://github.com/Robothy/local-s3/compare/726c4b5...main
[2.4]: https://github.com/Robothy/local-s3/commits/726c4b5
