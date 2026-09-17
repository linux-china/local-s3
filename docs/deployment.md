# Deployment

LocalS3 runs as a Docker image, as an executable jar, or embedded in a JVM (see [embedding.md](embedding.md)). All of
them are configured by the same environment variables, and answer the same health check and admin endpoints.

- [Docker](#docker)
- [Executable jar](#executable-jar)
- [Configuration](#configuration)
- [Data directory](#data-directory)
- [Health check](#health-check)
- [Kubernetes](#kubernetes)
- [Admin endpoints](#admin-endpoints)

## Docker

The images are published to [DockerHub](https://hub.docker.com/r/luofuxiang/local-s3): `luofuxiang/local-s3`, for
`linux/amd64` and `linux/arm64`, runs on a Java 21 JRE, and `luofuxiang/local-s3:native-<version>` is a much smaller
GraalVM native image.

```shell
docker run --name s3 -d -v "$PWD/local-s3:/data" -p 29090:29090 luofuxiang/local-s3
```

The image sets `LOCAL_S3_HOST=0.0.0.0` and `LOCAL_S3_MODE=PERSISTENCE`, so it listens on every interface and
persists to the `/data` volume. It declares a Docker `HEALTHCHECK` that requests
the [health check](#health-check).

It runs as the unprivileged user `locals3`, so a bind-mounted data directory must be writable by that user. To run as
your own user instead, and keep the ownership of the directory, start the container with
`--user "$(id -u):$(id -g)"`.

`local-s3-standalone/docker-compose.yaml` starts both images side by side, on ports `29090` and `39090`.

## Executable jar

The same service runs without Docker. `local-s3-standalone` is published to Maven Central as an executable
jar that carries everything it needs, so a JRE 21 is the only requirement:

```shell
curl -LO https://repo1.maven.org/maven2/io/github/robothy/local-s3-standalone/2.5.0/local-s3-standalone-2.5.0.jar
java -jar local-s3-standalone-2.5.0.jar
```

It is configured by the variables [below](#configuration), read from the environment or from the system properties of
the same names, which is what a command line sets most easily:

```shell
java -DLOCAL_S3_PORT=29090 -DLOCAL_S3_MODE=IN_MEMORY -DAWS_BUCKETS=my-bucket \
    -jar local-s3-standalone-2.5.0.jar
```

Unlike the container, the jar listens on `127.0.0.1` and runs `IN_MEMORY` by default, starting from the initial data of
`/data` if that directory exists. Set `LOCAL_S3_HOST=0.0.0.0` to serve other hosts, and `LOCAL_S3_MODE=PERSISTENCE`
with a `LOCAL_S3_DATA_PATH` of your own to keep the data:

```shell
java -DLOCAL_S3_MODE=PERSISTENCE -DLOCAL_S3_DATA_PATH="$HOME/local-s3" -jar local-s3-standalone-2.5.0.jar
```

A shell that a developer works in often exports `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY` already, and
the jar reads the environment it inherits, so it would require every request to be signed with those
credentials. A container starts with a clean environment and doesn't run into this; clear them for the jar
if you don't mean to sign:

```shell
env -u AWS_ACCESS_KEY_ID -u AWS_SECRET_ACCESS_KEY java -jar local-s3-standalone-2.5.0.jar
```

## Configuration

| Variable | Default | Description |
|---|---|---|
| `LOCAL_S3_PORT` | `29090` | Port that LocalS3 listens on. |
| `LOCAL_S3_HOST` | `127.0.0.1`; `0.0.0.0` in the image | Host to bind. |
| `LOCAL_S3_MODE` | `IN_MEMORY`; `PERSISTENCE` in the image | `PERSISTENCE` or `IN_MEMORY`. |
| `LOCAL_S3_DATA_PATH` | `/data` | Data directory, or initial data in `IN_MEMORY` mode. |
| `LOCAL_S3_PERSISTENCE_POLICY` | `DURABLE` | `PERSISTENCE` mode: when changes reach the disk. `DURABLE` commits every change; `FAST` commits in the background and on shutdown, which is much quicker and writes far less. See [Persistence policy](#persistence-policy). |
| `LOCAL_S3_COMPOSITE_MULTIPART_ETAGS` | `true` | Give the object of a completed multipart upload the entity tag of Amazon S3, i.e. the digest of the digests of its parts with a `-<parts>` suffix. `false` answers the digest of the whole content, which LocalS3 answered before 2.5. |
| `LOCAL_S3_VIRTUAL_HOST_DOMAINS` | | Comma-separated base domains of virtual-hosted-style requests, e.g. `s3,s3.local` for `my-bucket.s3`. `localhost`, Amazon S3 (`*.amazonaws.com`), Alibaba Cloud OSS (`my-bucket.oss-cn-hangzhou.aliyuncs.com`), Cloudflare R2 (`my-bucket.<account-id>.r2.cloudflarestorage.com`) and Tigris (`my-bucket.t3.storage.dev`, `my-bucket.fly.storage.tigris.dev`) hosts always work. |
| `LOCAL_S3_VIRTUAL_THREADS` | `true` | Handle every request on a virtual thread of its own. `false` handles the requests on a pool of platform threads, as many as the machine has processors and at least 4. |
| `LOCAL_S3_OBJECT_METADATA_CACHE_MAX_ENTRIES` | `50000` | `PERSISTENCE` mode: the number of objects whose metadata is kept in heap; see [Opening a large data path](#opening-a-large-data-path). |
| `LOCAL_S3_INITIAL_DATA_CACHE_MAX_ENTRIES` | `1024` | `IN_MEMORY` mode with initial data: the max number of data paths whose loaded data the JVM caches. |
| `LOCAL_S3_INITIAL_DATA_CACHE_MAX_BYTES` | a quarter of the max heap | `IN_MEMORY` mode with initial data: the max heap that the copies of the objects read from the data paths take, e.g. `512m`. The least recently used data paths are dropped to make room, and an object that still doesn't fit is read from the disk instead. Also settable with `LocalS3.configureInitialDataCache(maxEntries, maxBytes)`. |
| `AWS_BUCKETS` | | Comma-separated buckets to create on startup. |
| `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` | | Require requests signed with this key pair. |
| `LOCAL_S3_TLS_CERT`, `LOCAL_S3_TLS_KEY` | | Serve HTTPS instead of plain HTTP with this certificate chain and unencrypted PKCS#8 private key, each the path of a PEM file or the PEM content itself. Set both or neither; see [HTTPS](#https). |
| `JAVA_OPTS` | `-XX:MaxRAMPercentage=75.0` | JVM options of the JVM based image. |

The same variables configure an embedded service, through `LocalS3Builder.fromEnvironment()`, which reads them
from the environment or from the system properties of the same names. Only the variables that are set are
applied, so the defaults of the embedded service are kept for the rest; the defaults in the table above are
those of the jar and the image. An embedded service has no data path unless one is configured. The names of the
variables are the constants of `LocalS3Environment`.

```java
LocalS3 localS3 = LocalS3.builder()
    .port(29090)
    .fromEnvironment()
    .build();
```

## HTTPS

LocalS3 serves plain HTTP unless it is given a certificate and its private key in PEM format. Several clients use, or
require, HTTPS by default, and then connect to LocalS3 without turning TLS off:

| Client | Default that expects HTTPS |
|---|---|
| DuckDB `httpfs` | `USE_SSL true` in a secret, `s3_use_ssl = true` |
| Hadoop S3A | `fs.s3a.connection.ssl.enabled=true` |
| Snowflake | S3-compatible storage must be reached over HTTPS |
| `object_store` (Rust) and some Go clients | an `http://` endpoint is refused unless e.g. `allow_http` is set |

With TLS configured, the port serves **only** HTTPS; plain HTTP requests to it fail. The
[health check](#health-check) is then `https://…/_health`.

### Create a certificate with mkcert

[mkcert](https://github.com/FiloSottile/mkcert) creates a certificate that the machine trusts, without a warning
in browsers, curl or DuckDB:

```shell
brew install mkcert        # or see the installation of mkcert for Linux and Windows
mkcert -install            # once: adds the CA of mkcert to the trust stores of the system and of browsers
mkcert localhost 127.0.0.1 # creates localhost+1.pem and localhost+1-key.pem in the current directory
```

List every name that clients reach LocalS3 by, e.g. `mkcert localhost 127.0.0.1 s3 s3.local "*.s3.local"` for the
service name in docker-compose and for [virtual-hosted-style](#configuration) requests. The key that mkcert writes is
an unencrypted PKCS#8 key (`-----BEGIN PRIVATE KEY-----`), which is what LocalS3 expects.

### Start LocalS3 with the certificate

```shell
# Executable jar
LOCAL_S3_TLS_CERT=localhost+1.pem LOCAL_S3_TLS_KEY=localhost+1-key.pem java -jar local-s3-standalone-2.5.0.jar

# Docker
docker run -d -p 29090:29090 -v "$PWD:/certs:ro" \
  -e LOCAL_S3_TLS_CERT=/certs/localhost+1.pem -e LOCAL_S3_TLS_KEY=/certs/localhost+1-key.pem \
  luofuxiang/local-s3

curl https://localhost:29090/_health
```

mkcert writes the key readable by its owner only, and the image runs as the user `locals3`: make the key readable in
the container, e.g. `chmod 644 localhost+1-key.pem` for a local certificate, or start it with
`--user "$(id -u):$(id -g)"`.

Embedded, `LocalS3.builder().tls("localhost+1.pem", "localhost+1-key.pem")`; see
[embedding.md](embedding.md#serve-https). The files are read when the service is configured, and an unreadable file,
or a key that doesn't belong to the certificate, fails right away.

### Trust the certificate in clients

`mkcert -install` makes the system trust the certificate, which covers curl, DuckDB, and most Go and Rust clients.
Other clients need the CA of mkcert, `"$(mkcert -CAROOT)/rootCA.pem"`, explicitly:

+ **JVM** (AWS SDK for Java, Hadoop S3A, Iceberg, Spark): the JVM has its own trust store. Import the CA once, e.g.
  `keytool -importcert -noprompt -alias mkcert -cacerts -storepass changeit -file "$(mkcert -CAROOT)/rootCA.pem"`,
  or pass a trust store with `-Djavax.net.ssl.trustStore=…`.
+ **AWS CLI and boto3**: `AWS_CA_BUNDLE="$(mkcert -CAROOT)/rootCA.pem"`, or `--ca-bundle`.
+ **Python requests / Node.js**: `REQUESTS_CA_BUNDLE` / `NODE_EXTRA_CA_CERTS` set to the same file.
+ **Containers**: a client in another container doesn't see the trust store of the host; mount `rootCA.pem` into it
  and point the client at it.

DuckDB, with the defaults of a secret, which uses HTTPS:

```sql
CREATE SECRET local_s3 (
    TYPE s3,
    ENDPOINT 'localhost:29090',
    URL_STYLE 'path',
    KEY_ID 'admin',
    SECRET 'admin',
    REGION 'us-east-1'
);
```

## Data directory

In `PERSISTENCE` mode LocalS3 loads data from and stores all data into its data directory. In `IN_MEMORY` mode with a
data directory, it starts from the data of the directory and never writes to it. How the directory is laid out is
described in [architecture.md](architecture.md#persistence-layout).

> **Upgrading from 2.4 or earlier:** 2.5 keeps the metadata of the buckets in `buckets.mvstore` and no longer reads
> the `*.bucket.meta` files of earlier versions. See the [CHANGELOG](../CHANGELOG.md#upgrading-from-24) before pointing
> 2.5 at an existing data directory.

### Persistence policy

A `PERSISTENCE` service commits the metadata of every change by default, so a process that is killed loses nothing it
answered a request for. A commit appends a chunk to `buckets.mvstore` rather than replacing what it supersedes, so a
bulk load leaves one chunk per object: the file grows far beyond the metadata it holds while the load runs.

`persistencePolicy(FAST)`, or `LOCAL_S3_PERSISTENCE_POLICY=FAST`, lets the store commit in the background instead, at
most a second after a change, and commits what is left when the service is shut down:

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

### Opening a large data path

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
that costs, measured as the size of the persisted form of that metadata; an `IN_MEMORY` service never writes its metadata, so
it reports `0`. The bound defaults to 50000 objects and is
configured with the environment variable, or system property, `LOCAL_S3_OBJECT_METADATA_CACHE_MAX_ENTRIES`:

```shell
LOCAL_S3_OBJECT_METADATA_CACHE_MAX_ENTRIES=200000 java -jar local-s3-standalone-2.5.0.jar
```

Raising it trades heap for fewer reads of the store; the objects that a service serves stay in heap either way, since
what is dropped is what was read longest ago. An `IN_MEMORY` service holds all of its objects, having nowhere to read
them back from, so the bound doesn't apply to it and `loadedObjects` equals `objects`.

## Health check

LocalS3 answers `GET /_health` (and `HEAD /_health`) with `200 OK` and `{"status":"UP"}` once it serves
requests. The health check needs no authentication, even if credentials are configured, so probes can use it.

```java
// Testcontainers: wait for the health check instead of the startup log message.
new LocalS3Container("latest")
    .withRandomHttpPort()
    .waitingFor(Wait.forHttp("/_health").forPort(29090));
```

`LocalS3Container` still waits for the startup log message by default, so that it works with images older than
the health check.

## Kubernetes

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: local-s3
spec:
  replicas: 1
  selector:
    matchLabels:
      app: local-s3
  template:
    metadata:
      labels:
        app: local-s3
    spec:
      containers:
        - name: local-s3
          image: luofuxiang/local-s3:latest
          env:
            - name: LOCAL_S3_MODE
              value: IN_MEMORY
            - name: AWS_BUCKETS
              value: my-bucket
          ports:
            - containerPort: 29090
          readinessProbe:
            httpGet:
              path: /_health
              port: 29090
          livenessProbe:
            httpGet:
              path: /_health
              port: 29090
```

Run a single replica: a data directory is served by one process at a time, and the replicas of an `IN_MEMORY`
deployment would each hold data of their own.

## Admin endpoints

A running service answers a few endpoints for local development and tests, with JSON:

| Endpoint | Description |
|---|---|
| `GET /_admin/stats` | The amount of data (buckets, objects, object versions, delete markers, object bytes, multipart uploads in progress, vector buckets, indexes and vectors), how much object metadata the service keeps in heap (`loadedObjects` and `loadedObjectMetadataBytes`, see [Opening a large data path](#opening-a-large-data-path)), the requests in flight, and per operation, e.g. `PutObject`, the number of requests, the `4xx` and `5xx` responses, the requests per second of the last minute, and the average, p50, p90, p99 and max latency in milliseconds. |
| `GET /_admin/requests?limit=n` | The last 100 requests, the most recent first: time, method, URI, operation, status, latency and `x-amz-request-id`. The values of the credentials of presigned URLs are hidden. |
| `POST /_admin/lifecycle` | Apply the lifecycle configurations of the buckets, which LocalS3 never does by itself, and answer the actions taken. `bucket=name` applies one bucket's only; `now=2030-01-01T00:00:00Z` applies them at that instant, or `days=31` that many days from now, instead of the current time. See [lifecycle configuration](semantics.md#lifecycle-configuration). |
| `POST /_admin/reset` | Replace the data of an `IN_MEMORY` service with the data it started with, i.e. none, or the initial data of its data path, and create the `AWS_BUCKETS` again. The requests recorded for the statistics are forgotten too. A `PERSISTENCE` service answers `409 Conflict`, since a reset would delete its data path. |

Resetting a service between the tests that share it is much quicker than restarting it. The requests in progress
are finished first, and the requests that arrive meanwhile wait for the reset. An embedded service is reset with
`LocalS3#reset()`, and its statistics are read with `LocalS3#statistics()`.

```shell
curl -s http://localhost:29090/_admin/stats
curl -s -X POST http://localhost:29090/_admin/reset
curl -s -X POST "http://localhost:29090/_admin/lifecycle?days=31"
```

The latency of a request is measured from when its body is received until its response is written. The health check
and the admin endpoints aren't recorded. Unlike the health check, the admin endpoints must be signed if credentials
are configured, e.g. with `curl --aws-sigv4 "aws:amz:us-east-1:s3" --user "$AWS_ACCESS_KEY_ID:$AWS_SECRET_ACCESS_KEY" http://localhost:29090/_admin/stats`.
