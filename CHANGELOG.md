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
| Credentials variables of the jar and `fromEnvironment()` | `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` | `LOCAL_S3_ACCESS_KEY_ID`, `LOCAL_S3_SECRET_ACCESS_KEY`; `AWS_*` only with `LOCAL_S3_CREDENTIALS_FROM_AWS_ENV=true`, which the Docker images set |
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

+ **Buckets created with versioning enabled**: `@LocalS3(buckets = "plain", versionedBuckets = "audit")`,
  `LocalS3.builder().versionedBuckets("audit")`, `local-s3.versioned-buckets=audit`,
  `LocalS3Container.withVersionedBuckets("audit")`, or the `:versioned` suffix of `AWS_BUCKETS=plain,audit:versioned`
  (also `buckets("audit:versioned")` and `--buckets`), so that a local bucket matches a production bucket that has
  versioning enabled without a `PutBucketVersioning` in every test. They are created again on a reset. An existing
  bucket gets versioning enabled only if its versioning was never configured; a suspended one stays suspended.
+ **A default CORS rule for browsers**, off by default: `LocalS3.builder().defaultCors(cors -> cors.allowedOrigins("*"))`,
  `LOCAL_S3_CORS_ALLOWED_ORIGINS=*` or `local-s3.cors.allowed-origins=http://localhost:5173`. It answers the
  cross-origin requests of a bucket that has **no** CORS configuration of its own, and of the requests that address no
  bucket (`ListBuckets`, `/iceberg/v1`, `/s3tables`), so a single page application, a presigned upload, DuckDB-WASM or a
  notebook in a browser needs no `PutBucketCors` for every bucket first. A bucket configured with `PutBucketCors` keeps
  its own rules, as in Amazon S3. See [CORS](docs/semantics.md#cors).

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
  video, PDF and HTML render in the page and anything else is a download; `Share` copies a presigned URL of the object,
  valid for 15 minutes to 7 days, to hand an artifact to someone without credentials (`GET /_admin/ui/presign`);
  **files and folders dropped on the page** are uploaded into the prefix that is open, a dropped folder becoming a
  prefix, with a panel showing the progress of the batch; `Delete` removes an object after a confirmation; and
  `+ NEW` creates a bucket, under the naming rules of Amazon S3. It answers what an S3 mock otherwise leaves to
  `aws s3 ls`, which matters when LocalS3 is embedded in an IDE or an application, and when an AI agent stores its
  artifacts in it and a person has to review them.

  A file larger than 64 MiB is uploaded in parts of 16 MiB through `/_admin/ui/multipart`, like an S3 client would,
  so a large dataset dropped on the page isn't bounded by `maxRequestBodySize`.

  Behind the page are its own endpoints — `/_admin/ui/buckets`, `/_admin/ui/objects`, `GET`, `PUT` and `DELETE` of
  `/_admin/ui/object`, `PUT /_admin/ui/bucket`, and the multipart upload of `/_admin/ui/multipart` — which call the same services the S3 operations do, so what the
  console creates, uploads and deletes reaches the change listeners like any other change. It deletes no bucket and
  changes no bucket configuration. A browser can't sign a request with SigV4, so the console is guarded with HTTP
  Basic authentication instead: a service configured with credentials asks for the access key ID and the secret
  access key, and one without them serves the console like it serves unsigned S3 requests. A request that changes
  something also wants the `X-LocalS3-Console` header, which no other origin can send, so a page open elsewhere in
  the browser can't write through it. The S3 API is unaffected, and the requests of the console aren't recorded in
  the statistics. See [Console](docs/deployment.md#console).
+ **Connection snippets**, written by the service for the service: `GET /_admin/snippets` answers the configuration
  that DuckDB, s5cmd and the AWS CLI (as environment variables, and as a profile), boto3, Polars (`storage_options`),
  PyIceberg and Spark need to reach it — the host the request addressed, plain HTTP
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

+ **Hadoop S3A is covered end to end.** `HadoopS3AIntegrationTest` runs `S3AFileSystem` of `hadoop-aws`, the path
  that Spark, Delta Lake, Hudi and Paimon take to S3: `mkdirs`, `listStatus`, `rename` and `delete` of directories,
  a multipart upload and a multipart copy of a large file, the magic committer committing one task and aborting
  another, and conditional creates with `If-None-Match: *` and `If-Match`. It runs under `dataToolsTest`. See
  [Hadoop S3A](docs/data-tools.md#hadoop-s3a).

+ **Apache Paimon and Apache Hudi are covered end to end, over S3A.** `PaimonIntegrationTest` runs the filesystem
  catalog of Paimon 2.0, without Flink or Spark, on a warehouse under `s3a://`: a partitioned primary-key table
  created, written, updated and deleted by key, read merged and by time travel, compacted to one file per bucket, its
  old snapshots expired and the table dropped. `HudiIntegrationTest` runs the Java write client of Hudi 1.2 on a
  copy-on-write table (insert, upsert, delete) and on a merge-on-read one, whose updates go to log files until a
  compaction merges them into new base files. Both run under `dataToolsTest`. See
  [Apache Paimon](docs/data-tools.md#apache-paimon) and [Apache Hudi](docs/data-tools.md#apache-hudi).

+ **Lance and LanceDB are covered end to end.** `ceph-s3-tests/data-tools/test_lance.py` drives Lance (`pylance`) and
  LanceDB, which reach S3 with Rust's `object_store`, against the executable jar: writes, updates, deletes and time
  travel, the compaction of fragments and the cleanup of old versions, an IVF_PQ index with a nearest-neighbour search,
  a LanceDB table searched and optimized, and four writers racing for one version, whose manifests Lance creates with
  `If-None-Match: *`: the losers get `412` and commit again, and every row is kept. See
  [Lance and LanceDB](docs/data-tools.md#lance-and-lancedb).

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
  table a location of its own, the `unique-table-location` of the Iceberg catalogs. The credentials route of a table,
  `GET .../tables/{table}/credentials`, answers temporary credentials of the STS endpoint, and a service that verifies
  signatures names it in `client.refresh-credentials-endpoint`, so the `S3FileIO` of Iceberg refreshes its credentials
  before they expire.
  See [the built-in Iceberg REST catalog](docs/data-tools.md#the-built-in-iceberg-rest-catalog).

+ **Bounded in-memory storage**: `storage(storage -> storage.maxInMemoryBytes(bytes))`,
  `LOCAL_S3_IN_MEMORY_MAX_BYTES` (e.g. `512m`), `local-s3.in-memory.max-size` and `@LocalS3(maxInMemoryBytes)` limit the heap that the objects and parts of an `IN_MEMORY` service take, half the max
  heap by default. An upload or a copy beyond it is answered with `507 InsufficientStorage`, whose message suggests the
  `PERSISTENCE` mode, instead of an `OutOfMemoryError` that takes the embedding application or IDE down.
+ **Signed requests**: `credentials(accessKeyId, secretAccessKey)`, `LOCAL_S3_ACCESS_KEY_ID` /
  `LOCAL_S3_SECRET_ACCESS_KEY` and `@LocalS3(accessKey, secretKey)` verify AWS Signature Version 4, before the body of
  a request is received. `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY`, where the AWS SDKs read the client credentials
  of a developer from, are read only with `LOCAL_S3_CREDENTIALS_FROM_AWS_ENV=true`, which the Docker images set, so a
  jar or an embedded service started from a shell with a real AWS key doesn't require every request to be signed with
  it. The startup log names the variables the credentials came from, with the access key ID masked, e.g. `AKIA****1234`.
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
+ **Analytics, inventory and metrics configurations**: the `Put`, `Get`, `List` and `Delete` operations of
  `?analytics`, `?inventory` and `?metrics` store and return the configurations of a bucket by ID instead of answering
  `501 NotImplemented`, so that Terraform's `aws_s3_bucket_metric`, `aws_s3_bucket_inventory` and
  `aws_s3_bucket_analytics_configuration`, and CDK stacks, work. They are **never applied**: no report or metric is
  produced. See [the API list](docs/apis.md).
+ **Intelligent-Tiering configurations**: the `Put`, `Get`, `List` and `Delete` operations of `?intelligent-tiering`
  store and return the configurations of a bucket by ID like the analytics ones, so that Terraform's
  `aws_s3_bucket_intelligent_tiering_configuration` works. No object ever moves between access tiers.
+ **RestoreObject**: restoring a `GLACIER` or `DEEP_ARCHIVE` object completes at once, answering `202 Accepted` the
  first time and `200 OK` while the restored copy lasts; `HeadObject` and `GetObject` answer `x-amz-restore`. Other
  storage classes answer `403 InvalidObjectState`. See [semantics](docs/semantics.md#storage-classes-and-restores).
+ **Website redirect location**: `x-amz-website-redirect-location` of `PutObject`, `CopyObject`,
  `CreateMultipartUpload` and `POST Object` is stored with the object, answered by `GetObject` and `HeadObject`, and
  the static website redirects a request for the object to it.
+ **Virtual-hosted-style requests**, for `localhost`, Amazon S3, Alibaba Cloud OSS, Cloudflare R2 and Tigris hosts,
  and for the domains of `s3Api(s3 -> s3.virtualHostDomains(...))` / `LOCAL_S3_VIRTUAL_HOST_DOMAINS`.
+ **Change listeners**: `changeListener` and `events(events -> events.listener(...).executor(...))` deliver an
  `S3Change` for every committed change of a bucket or an object, with its `eventTime()` and `sequencer()`;
  `toS3EventJson()` / `S3EventNotification` map it to the Amazon S3 event notification JSON. See
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
  Besides `S3Client`, `S3VectorsClient`, `S3TablesClient` and `LocalS3Endpoint`, `@LocalS3` injects the clients that
  the Spring Boot starter defines, `S3AsyncClient`, `S3Presigner` and, with `s3-transfer-manager`, `S3TransferManager`,
  and the `LocalS3` service itself, for `reset()`, `applyLifecycle(...)` and change listeners in tests.
+ **[Deployment](docs/deployment.md#opening-a-large-data-path)** gives the heap that the keys of a `PERSISTENCE` data
  path take: about 200–300 MB per million keys.
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
+ **`@ServiceConnection` of `LocalS3Container`** for Spring Cloud AWS, as its `LocalStackContainer` has:
  `@Container @ServiceConnection LocalS3Container` points the `S3Client`, `S3Template` and `S3Presigner` of Spring
  Cloud AWS at the container, with its credentials, and with no property of the application. See
  [`@ServiceConnection`](docs/embedding.md#serviceconnection-with-spring-cloud-aws).

### Changed

+ An `aws-chunked` upload, which the AWS SDK for Java 2.30+ sends by default for `PutObject` and `UploadPart` over
  plain HTTP (`STREAMING-AWS4-HMAC-SHA256-PAYLOAD-TRAILER`), is decoded while its body is written to the temporary
  file, and its chunk and trailer signatures are verified along the way. In `PERSISTENCE` mode the file of such an
  upload is renamed into place like any other, rather than decoded into a second file, so a large upload is written to
  the disk once instead of twice. A body whose chunk signatures don't match is answered with `403 SignatureDoesNotMatch`
  and a malformed or incomplete one with `400 IncompleteBody`; either closes the connection and deletes the file.
+ Closer to Amazon S3, as ceph/s3-tests checks it:
  + `AbortMultipartUpload` of an unknown upload ID, or of one already aborted or completed, answers
    `404 NoSuchUpload` instead of `204`.
  + `DeletePublicAccessBlock` answers `204 No Content` instead of `200`.
  + `UploadPartCopy` rejects an `x-amz-copy-source-range` beyond the end of the source with `400 InvalidRange`.
  + `PutBucketLifecycleConfiguration` gives a rule put without an `ID` a generated one, and rejects a `Date` of an
    `Expiration` or a `Transition` that isn't an ISO 8601 date at midnight UTC with `400 InvalidArgument`.
  + `GetObject` and `HeadObject` that send `x-amz-server-side-encryption` answer `400 InvalidArgument`.
  + A part of an object, read with `partNumber`, has the checksum type of the object, e.g. `COMPOSITE` for SHA-1 and
    SHA-256, rather than `FULL_OBJECT`.
  + POST Object stores the `x-amz-server-side-encryption-customer-*` fields of the form like `PutObject` stores the
    headers, and checks the policy against the key with `${filename}` replaced by the name of the file.
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

+ An `IN_MEMORY` service receives the large body of a `PutObject` or `UploadPart` (above `requestBodyFileThreshold`,
  4 MiB) into the heap, in chunks that its storage takes over, rather than into a file of the temporary directory that
  it then copied into the heap: an upload no longer touches the disk, e.g. a small `/tmp` of a CI container, nor is
  held twice. Its declared length is reserved in `maxInMemoryBytes` before `100 Continue`, so a body that can't fit is
  answered `507 InsufficientStorage` before it is uploaded. Other large bodies, and those without a declared length or
  larger than 2 GiB, are still buffered in files.
+ A signed request whose body doesn't have the hash of its `x-amz-content-sha256` is answered
  `400 XAmzContentSHA256Mismatch`, with `<ClientComputedContentSHA256>` and `<S3ComputedContentSHA256>`, like Amazon
  S3; it was answered `403 SignatureDoesNotMatch`, which sent the developer looking for a wrong key.
+ `PutObject`, `CopyObject`, `POST Object` and `CompleteMultipartUpload` to a bucket whose versioning is suspended
  answer no `x-amz-version-id`, like Amazon S3; they answered `null`. Reading or deleting the null version still
  answers `null`.
+ `ListObjectVersions` answers `null` as the `NextVersionIdMarker` of a page that ends with the null version, rather
  than the ID that LocalS3 holds the version by inside; a `null` marker whose null version was deleted since lists every
  version left of the key, so a client that empties a bucket page by page skips none. A `key-marker` without a
  `version-id-marker` lists the keys after it, rather than the versions of the key too; an empty marker is no marker;
  and a page that ends with the last version is no longer truncated.
+ `PutBucketVersioning` answers `400 MalformedXML` for a `Status` other than `Enabled` or `Suspended`, which it used to
  take for `Suspended`, and a configuration without a `Status` leaves the bucket as it is rather than suspending the
  versioning of a bucket that was never versioned. The `MfaDelete` of the configuration is stored and answered back by
  `GetBucketVersioning`; no MFA device is asked for.
+ A version ID that is neither `null` nor a number answers `400 InvalidArgument` (`Invalid version id specified`) rather
  than `404 NoSuchVersion`, like Amazon S3, and so does any version ID but `null` for the tagging, ACL and restore
  operations of a bucket that was never versioned, as `GetObject` already did. The ID that LocalS3 holds the null
  version by names no version for any operation; the tagging, ACL, restore, retention and legal hold operations used
  to find the version by it. `DeleteObject` still never fails on its version ID.
+ `ListObjectsV2` echoes the `start-after` of the request like Amazon S3 does: also next to a `continuation-token`,
  which the listing continues from instead, and also when it is whitespace alone, e.g. `\n`. With `encoding-type=url`
  it is URL-encoded like the keys.
+ `PutObjectRetention` that turns a `GOVERNANCE` retention into a `COMPLIANCE` one answers `403 AccessDenied` unless
  the request sends `x-amz-bypass-governance-retention: true`, like Amazon S3. It was allowed without the bypass.
+ A browser form upload (`POST Object`) with a policy but no signature answers `400 InvalidArgument` rather than
  `403 AccessDenied`, and a policy with a condition object that doesn't name exactly one field, e.g. `{}`, answers
  `400 InvalidPolicyDocument` rather than failing its conditions with `403 AccessDenied`, like Amazon S3.
+ A service binds its port with `SO_REUSEADDR` (except on Windows), so that a fixed port, e.g. the `29090` of the
  Spring Boot starter, is bound again right after a stop while the connections that the stop closed are still in
  `TIME_WAIT`, e.g. when Spring Boot DevTools restarts the application context in the same JVM. A test of the starter
  closes and creates the context again on the same port and `PERSISTENCE` data directory, and checks that the
  metadata store is released.
+ `CreateBucket` of a bucket that already exists answers like Amazon S3 answers the owner of the bucket, which every
  client of LocalS3 is: `200 OK` in us-east-1, leaving the bucket and its objects as they are, and
  `409 BucketAlreadyOwnedByYou` in any other region. It answered `409 BucketAlreadyExists`, which code that creates a
  bucket and ignores a `BucketAlreadyOwnedByYouException` took for a real error. The console, and the services called
  directly, still refuse a bucket that exists, with `BucketAlreadyOwnedByYou`; `BucketAlreadyOwnedByYouException` is a
  `BucketAlreadyExistsException`, so code that catches that one keeps working.
+ A retried `CompleteMultipartUpload` of an upload that was completed already, e.g. by an SDK whose first request
  timed out, answers the result of the completed upload, like Amazon S3 does, rather than `404 NoSuchUpload`. The
  answer is read off the version that the upload stored, so a retry fails with `NoSuchUpload` again once that version
  is overwritten or deleted. Uploads completed before this version aren't recognized.
+ The listings with `encoding-type=url` encode like RFC 3986, which is what Amazon S3 does: a space is `%20`, not `+`,
  which a client that decodes by RFC 3986, e.g. of Python or Rust, read as a plus, so that it asked for a key that
  doesn't exist, e.g. a Hive partition `city=New York`. The AWS SDK for Java decodes both. `ListObjectVersions`
  encodes its prefix, delimiter, key markers and common prefixes too, and no longer encodes the `/` of a key.
+ `ListObjects` (v1) with `encoding-type=url` leaves its `Prefix` unencoded, as Amazon S3 does, since botocore decodes
  only its delimiter, markers and keys: boto3 and the AWS CLI read a prefix such as `\n` back as it was sent.
  `ListObjectsV2` still encodes it.
+ Error codes and details that differed from the ones of Amazon S3, which tests that assert the type of an exception
  tell apart; ceph/s3-tests pass 29 more tests:
  + `HeadObject` and `GetObject` of a key whose current version is a delete marker answer `404 NoSuchKey` with
    `x-amz-delete-marker: true` and the `x-amz-version-id` of the marker.
  + `GetObjectAttributes` answers the `ETag` without its quotes.
  + `PutBucketTagging` and `DeleteBucketTagging` answer `204 No Content`, not `200`.
  + A `Content-MD5` that isn't the base64 of an MD5 digest, e.g. an empty one, is `InvalidDigest`; one that doesn't
    match the content is still `BadDigest`. A malformed `x-amz-checksum-*` value is `BadDigest`, not `InvalidRequest`.
  + A presigned URL whose `X-Amz-Expires` isn't within 1 to 604800 seconds, e.g. a negative one, is
    `403 AccessDenied`, like an expired one, not `400 AuthorizationHeaderMalformed`.
  + `CopyObject` of an object onto itself that changes nothing of it, i.e. neither its metadata, its tags, its storage
    class, its encryption nor its checksum algorithm, is `400 InvalidRequest`.
  + `PutObjectTagging` refuses more than 10 tags, a key longer than 128 characters, a value longer than 256 and a key
    given twice with `400 InvalidTag`, and the object keeps its tags. A tag of `x-amz-tagging` without `=`, e.g. the
    `bar` of `foo=bar&bar`, has an empty value rather than being refused.
  + A `Content-Encoding` without `aws-chunked` is stored as it is, e.g. `deflate, gzip`, not rewritten to
    `deflate,gzip`.
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
+ A request whose body isn't the XML of its operation, e.g. a `PutBucketTagging`, `PutBucketVersioning`,
  `DeleteObjects` or `CompleteMultipartUpload` whose body isn't well-formed, is empty, or names an unknown value, fails
  with `400 MalformedXML` instead of `500 InternalError`, which the AWS SDKs retried before failing, and which was
  logged as a failure of LocalS3. An S3 Vectors request whose body isn't JSON fails with `400 ValidationException`.
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
