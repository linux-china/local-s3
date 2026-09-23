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

> **A published port without credentials is open to the network.** The container listens on every interface, so
> `-p 29090:29090` lets everyone who reaches the machine read, write and delete every bucket, and the service says so
> when it starts:
>
> ```
> !! LocalS3 is listening on 0.0.0.0:29090 without authentication: everyone who reaches this port can read, write and delete every bucket.
> !! Set AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY, or LocalS3Builder.credentials(...), to require signed requests; bind 127.0.0.1 to serve this machine alone.
> ```
>
> On a shared network, e.g. an office network or a CI machine, set credentials, or publish the port to the loopback
> address of the host alone:
>
> ```shell
> docker run -d -p 29090:29090 -e AWS_ACCESS_KEY_ID=local -e AWS_SECRET_ACCESS_KEY=local luofuxiang/local-s3
> docker run -d -p 127.0.0.1:29090:29090 luofuxiang/local-s3
> ```
>
> The warning is logged by `com.robothy.s3.rest.LocalS3`; a service that is meant to be open can silence it there.

`local-s3-standalone/docker-compose.yaml` starts both images side by side, on ports `29090` and `39090`.

## Executable jar

The same service runs without Docker. `local-s3-standalone` is published to Maven Central as an executable
jar that carries everything it needs, so a JRE 21 is the only requirement:

```shell
curl -LO https://repo1.maven.org/maven2/io/github/robothy/local-s3-standalone/2.5.0/local-s3-standalone-2.5.0.jar
java -jar local-s3-standalone-2.5.0.jar
```

It takes an option per setting, one for each variable [below](#configuration); `--help` lists them all, beside the
variable each one stands for:

```shell
java -jar local-s3-standalone-2.5.0.jar --port 29292 --bucket my-bucket
java -jar local-s3-standalone-2.5.0.jar --help
```

An option wins over the variable of that setting, which in turn wins over the system property of the same name, so the
three configure the same service:

```shell
LOCAL_S3_MODE=PERSISTENCE java -jar local-s3-standalone-2.5.0.jar --data-path "$HOME/local-s3"
java -DLOCAL_S3_PORT=29090 -DLOCAL_S3_MODE=IN_MEMORY -DAWS_BUCKETS=my-bucket \
    -jar local-s3-standalone-2.5.0.jar
```

An argument that isn't an option of the service, e.g. a misspelled `--ports`, an option given twice, or a value that
isn't valid, stops the jar with the reason and exit code `2`, rather than starting a service that isn't the one that
was asked for.

Unlike the container, the jar listens on `127.0.0.1` and runs `IN_MEMORY` by default, starting from the initial data of
`/data` if that directory exists. Pass `--host 0.0.0.0` to serve other hosts, and `--mode PERSISTENCE` with a
`--data-path` of your own to keep the data:

```shell
java -jar local-s3-standalone-2.5.0.jar --mode PERSISTENCE --data-path "$HOME/local-s3"
```

A shell that a developer works in often exports `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY` already, and
the jar reads the environment it inherits, so it would require every request to be signed with those
credentials. A container starts with a clean environment and doesn't run into this; clear them for the jar
if you don't mean to sign:

```shell
env -u AWS_ACCESS_KEY_ID -u AWS_SECRET_ACCESS_KEY java -jar local-s3-standalone-2.5.0.jar
```

## Configuration

Every variable of the table below is also an option of the [executable jar](#executable-jar), named after it:
`LOCAL_S3_PORT` is `--port`, `LOCAL_S3_WEBSITE_ALL_BUCKETS` is `--website-all-buckets`, `AWS_BUCKETS` is `--buckets`
(or `--bucket`, repeated), and `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY` are `--access-key` and `--secret-key`.
The exceptions are the cache limits, which are read when a cache is created rather than applied to a service, so they
are set by a variable alone. A container is configured by the variables; `java -jar s3.jar --help` prints the options.

| Variable | Default | Description |
|---|---|---|
| `LOCAL_S3_PORT` | `29090` | Port that LocalS3 listens on. |
| `LOCAL_S3_HOST` | `127.0.0.1`; `0.0.0.0` in the image | Host to bind. |
| `LOCAL_S3_MODE` | `IN_MEMORY`; `PERSISTENCE` in the image | `PERSISTENCE` or `IN_MEMORY`. |
| `LOCAL_S3_DATA_PATH` | `/data` | Data directory, or initial data in `IN_MEMORY` mode. |
| `LOCAL_S3_IN_MEMORY_MAX_BYTES` | half the max heap | `IN_MEMORY` mode: the max heap that the objects and parts stored in the service take, e.g. `512m`. An upload beyond it is answered with `507 InsufficientStorage`; delete objects, raise the limit, or use `PERSISTENCE` mode for large data. The initial data of the data path doesn't count. |
| `LOCAL_S3_PERSISTENCE_POLICY` | `DURABLE` | `PERSISTENCE` mode: when changes reach the disk. `DURABLE` commits every change; `FAST` commits in the background and on shutdown, which is much quicker and writes far less. See [Persistence policy](#persistence-policy). |
| `LOCAL_S3_COMPOSITE_MULTIPART_ETAGS` | `true` | Give the object of a completed multipart upload the entity tag of Amazon S3, i.e. the digest of the digests of its parts with a `-<parts>` suffix. `false` answers the digest of the whole content, which LocalS3 answered before 2.5. |
| `LOCAL_S3_VIRTUAL_HOST_DOMAINS` | | Comma-separated base domains of virtual-hosted-style requests, e.g. `s3,s3.local` for `my-bucket.s3`. `localhost`, Amazon S3 (`*.amazonaws.com`), Alibaba Cloud OSS (`my-bucket.oss-cn-hangzhou.aliyuncs.com`), Cloudflare R2 (`my-bucket.<account-id>.r2.cloudflarestorage.com`) and Tigris (`my-bucket.t3.storage.dev`, `my-bucket.fly.storage.tigris.dev`) hosts always work. |
| `LOCAL_S3_VIRTUAL_THREADS` | `true` | Handle every request on a virtual thread of its own. `false` handles the requests on a pool of platform threads, as many as the machine has processors and at least 4. |
| `LOCAL_S3_OBJECT_METADATA_CACHE_MAX_ENTRIES` | `50000` | `PERSISTENCE` mode: the number of objects whose metadata is kept in heap; see [Opening a large data path](#opening-a-large-data-path). |
| `LOCAL_S3_INITIAL_DATA_CACHE_MAX_ENTRIES` | `1024` | `IN_MEMORY` mode with initial data: the max number of data paths whose loaded data the JVM caches. |
| `LOCAL_S3_INITIAL_DATA_CACHE_MAX_BYTES` | a quarter of the max heap | `IN_MEMORY` mode with initial data: the max heap that the copies of the objects read from the data paths take, e.g. `512m`. The least recently used data paths are dropped to make room, and an object that still doesn't fit is read from the disk instead. Also settable with `LocalS3.configureInitialDataCache(maxEntries, maxBytes)`. |
| `AWS_BUCKETS` | | Comma-separated buckets to create on startup. |
| `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` | | Require requests signed with this key pair. Unset, every request is answered, whoever sends it; a service that also binds an address other than a loopback one warns about that when it starts, see [Docker](#docker). |
| `LOCAL_S3_WEBSITE` | `true` | Serve the buckets as [static websites](semantics.md#static-website-hosting) to the requests that carry no credentials. Only a public bucket answers one; the signed requests of an S3 client are never affected. |
| `LOCAL_S3_WEBSITE_ALL_BUCKETS` | `false` | Serve **every** bucket as a static website, not the public ones alone, which also lets an unsigned request read the objects of a private bucket. Meant for local development. |
| `LOCAL_S3_WEBSITE_INDEX_DOCUMENT` | `index.html` | The index document of the buckets that have no `WebsiteConfiguration` of their own. |
| `LOCAL_S3_WEBSITE_ERROR_DOCUMENT` | | The error document of the buckets that have no `WebsiteConfiguration` of their own, e.g. `error.html`; unset answers a generic error page. |
| `LOCAL_S3_TLS_CERT`, `LOCAL_S3_TLS_KEY` | | Serve HTTPS, alongside plain HTTP on the same port, with this certificate chain and unencrypted PKCS#8 private key, each the path of a PEM file or the PEM content itself. Set both or neither; see [HTTPS](#https). |
| `LOCAL_S3_TLS_SELF_SIGNED` | | Serve HTTPS with a certificate that the service generates for itself on startup: `true` issues it for `localhost`, `127.0.0.1` and `::1`, and a comma-separated list of hosts issues it for those. Not to be set together with `LOCAL_S3_TLS_CERT`; see [Generate a certificate on startup](#generate-a-certificate-on-startup). |
| `LOCAL_S3_TLS_REQUIRED` | `false` | Serve HTTPS alone, instead of answering HTTP and HTTPS on the same port, so that a plain HTTP request fails. No effect without a certificate; see [HTTPS](#https). |
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

With TLS configured, the port serves **both HTTP and HTTPS**: each connection is told apart by its first bytes, so a
client that speaks TLS and one that doesn't reach the same endpoint, and a test suite covering both needs one service
rather than two. The [health check](#health-check) answers at `https://…/_health` and `http://…/_health` alike.

Set `LOCAL_S3_TLS_REQUIRED=true`, or `tls(tls -> tls.required(true))`, to serve HTTPS alone, which makes a plain HTTP request fail;
that is what a test asserting that its client really uses TLS needs. Without a certificate the setting has no effect.

There are two ways to get a certificate: LocalS3 [generates one for itself](#generate-a-certificate-on-startup), which
needs nothing installed but has to be handed to every client, or [mkcert](#create-a-certificate-with-mkcert) issues one
that the machine already trusts, which clients then need nothing for.

### Generate a certificate on startup

`LOCAL_S3_TLS_SELF_SIGNED=true`, or `tls(LocalS3Tls.selfSigned())`, generates a certificate for `localhost`,
`127.0.0.1` and `::1` when the service starts, in a few milliseconds and with nothing to install:

```shell
# Executable jar
LOCAL_S3_TLS_SELF_SIGNED=true java -jar local-s3-standalone-2.5.0.jar

# Docker
docker run -d -p 29090:29090 -e LOCAL_S3_TLS_SELF_SIGNED=true luofuxiang/local-s3
```

Set it to the comma-separated hosts to issue the certificate for instead of `true`, for every name that clients reach
the service by, e.g. `LOCAL_S3_TLS_SELF_SIGNED=localhost,127.0.0.1,local-s3` for the service name in docker-compose, or
`localhost,*.s3.local` for [virtual-hosted-style](#configuration) requests. A client verifies the host it connects to
against them, and refuses a host that the certificate doesn't name.

**No client trusts the certificate, because it signed itself.** The service logs it in PEM format when it starts, which
is the only place it exists:

```text
LocalS3 generated a self-signed certificate for this service: CN=localhost,O=LocalS3 for
[localhost, 127.0.0.1, 0:0:0:0:0:0:0:1], self-signed, valid until 2027-09-18T03:15:41Z, SHA-256 fingerprint 05:B7:…
-----BEGIN CERTIFICATE-----
MIIBtTCCAVqgAwIBAgIUBBaG2d/qt2FBS7W0dsx8AiwalSowCgYIKoZIzj0EAwIw
…
-----END CERTIFICATE-----
```

Save that block to a file, e.g. `local-s3.pem`, and hand it to the client as its CA: `curl --cacert local-s3.pem`,
`AWS_CA_BUNDLE=local-s3.pem`, `SET ca_cert_file = 'local-s3.pem'` in DuckDB, or the other clients of
[Trust the certificate in clients](#trust-the-certificate-in-clients). In the JVM that embeds the service,
`localS3.getConfig().tls()` holds it, and `newClientSslContext()` trusts it without a file:

```java
LocalS3 localS3 = LocalS3.builder().port(29090).tls(LocalS3Tls.selfSigned()).build();
localS3.start();

LocalS3Tls tls = localS3.getConfig().tls();
Files.writeString(Path.of("local-s3.pem"), tls.certificateChainPem()); // for a client outside the JVM
SSLContext sslContext = tls.newClientSslContext();                     // for one inside it
```

The certificate is valid for a year, and a new one is generated on every start, so a client that was given the
certificate of one run has to be given the next one as well. Use mkcert below where that gets in the way, e.g. for a
machine that several people or containers develop against.

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
Other clients need the CA of mkcert, `"$(mkcert -CAROOT)/rootCA.pem"`, explicitly. A
[generated certificate](#generate-a-certificate-on-startup) is trusted by nothing, so every client below needs it, with
the PEM file that the startup log was saved to in place of `rootCA.pem`:

+ **JVM** (AWS SDK for Java, Hadoop S3A, Iceberg, Spark): the JVM has its own trust store. Import the CA once, e.g.
  `keytool -importcert -noprompt -alias mkcert -cacerts -storepass changeit -file "$(mkcert -CAROOT)/rootCA.pem"`,
  or pass a trust store with `-Djavax.net.ssl.trustStore=…`.
+ **AWS CLI and boto3**: `AWS_CA_BUNDLE="$(mkcert -CAROOT)/rootCA.pem"`, or `--ca-bundle`.
+ **Python requests / Node.js**: `REQUESTS_CA_BUNDLE` / `NODE_EXTRA_CA_CERTS` set to the same file.
+ **Containers**: a client in another container doesn't see the trust store of the host; mount `rootCA.pem` into it
  and point the client at it.

DuckDB, with the defaults of a secret, which uses HTTPS:

```sql
-- Only for a certificate that the machine doesn't trust, e.g. a generated one; mkcert needs no CA file.
SET ca_cert_file = 'local-s3.pem';
CREATE SECRET local_s3 (
    TYPE s3,
    ENDPOINT 'localhost:29090',
    URL_STYLE 'path',
    KEY_ID 'admin',
    SECRET 'admin',
    REGION 'us-east-1'
);
```

Without the `ca_cert_file` of a certificate it doesn't trust, DuckDB fails with
`IO Error: SSL peer certificate or SSH remote key was not OK`, or `SSL connect error` against a plain HTTP service.

## Data directory

In `PERSISTENCE` mode LocalS3 loads data from and stores all data into its data directory. In `IN_MEMORY` mode with a
data directory, it starts from the data of the directory and never writes to it. How the directory is laid out is
described in [architecture.md](architecture.md#persistence-layout).

### Persistence policy

A `PERSISTENCE` service commits the metadata of every change by default, so a process that is killed loses nothing it
answered a request for. A commit appends a chunk to `buckets.mvstore` rather than replacing what it supersedes, so a
bulk load leaves one chunk per object: the file grows far beyond the metadata it holds while the load runs.

`storage(storage -> storage.persistencePolicy(FAST))`, or `LOCAL_S3_PERSISTENCE_POLICY=FAST`, lets the store commit
in the background instead, at most a second after a change, and commits what is left when the service is shut down:

```java
LocalS3 localS3 = LocalS3.builder()
    .storage(storage -> storage.mode(LocalS3Mode.PERSISTENCE)
        .dataPath("/data")
        .persistencePolicy(PersistencePolicy.FAST))
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

## Console

LocalS3 serves a built-in console at `GET /_admin/ui`: a single self-contained HTML page, with no build step and no
frontend framework, that lists the buckets and creates one, walks the objects of a bucket by their prefixes, previews
or downloads what is in them, and uploads or deletes an object. Open `http://localhost:29090/_admin/ui` in a browser.

It answers the question a mock service otherwise leaves to `aws s3 ls`: what is actually in there? That matters when
LocalS3 is embedded in an IDE or an application, and when an AI agent stores artifacts in it — a person then reads the
charts, reports and datasets the agent produced instead of listing keys.

| What it does | |
|---|---|
| Buckets | Name, region and creation date, with a filter. `+ NEW` creates one, in the default region and under the naming rules of Amazon S3, and opens it. |
| Objects | The objects and the directories under a prefix, with size and last-modified time, a prefix filter, and a page of 200 at a time. |
| An object | A preview of text, JSON, CSV, Markdown, images, audio, video, PDF and HTML, the rest as a download; `Download` saves it, and `Copy URL` copies the S3 URL of the object. |
| Upload | **Drop files or folders** anywhere on the listing, or use `Upload`: each file is stored under the prefix that is open, a dropped folder becomes a prefix, and a panel shows the progress of the batch, three files at a time. A file that the browser knows no type for is stored with the content type of its extension. |
| Delete | `Delete` on a row, after a confirmation. In a versioned bucket it puts a delete marker, like `DeleteObject` does. |

Behind the page are six endpoints of its own, which call the same services the S3 operations do:
`GET /_admin/ui/buckets`, `GET /_admin/ui/objects?bucket=&prefix=&continuation-token=`,
`GET /_admin/ui/object?bucket=&key=`, `PUT /_admin/ui/object?bucket=&key=`,
`DELETE /_admin/ui/object?bucket=&key=` and `PUT /_admin/ui/bucket?bucket=`. They answer JSON, or the content of the
object.

An upload is a single `PutObject` of the whole file rather than the multipart upload an S3 client would use for a
large one, so a file the browser can hold and send is one the console can store. The console **deletes no bucket** and
changes no bucket configuration — versioning, policies, CORS and the rest: for those, use the S3 API.

A browser can't sign a request with AWS Signature Version 4, so the console is guarded with **HTTP Basic
authentication** rather than a signature: a service configured with credentials asks for the access key ID as the user
name and the secret access key as the password, and a service without credentials, which answers unsigned S3 requests
anyway, serves the console to everyone who reaches the port. The S3 API of the same service is unaffected: it keeps
requiring signatures.

The three endpoints that change the data also want the `X-LocalS3-Console` header, which the page sends and which a
browser lets no other origin send without this service allowing it first. So a page a user has open elsewhere can't
create, upload or delete through the console, whether the service has credentials or not.

```shell
curl -s -u "$AWS_ACCESS_KEY_ID:$AWS_SECRET_ACCESS_KEY" "http://localhost:29090/_admin/ui/objects?bucket=my-bucket"
curl -s -u "$AWS_ACCESS_KEY_ID:$AWS_SECRET_ACCESS_KEY" -H "X-LocalS3-Console: 1" \
    -X PUT "http://localhost:29090/_admin/ui/bucket?bucket=my-bucket"
curl -s -u "$AWS_ACCESS_KEY_ID:$AWS_SECRET_ACCESS_KEY" -H "X-LocalS3-Console: 1" \
    -X PUT --data-binary @report.html "http://localhost:29090/_admin/ui/object?bucket=my-bucket&key=report.html"
```

The content of an object is served with `Content-Security-Policy: sandbox`, so a stored HTML page is previewed in a
sandbox of its own rather than as a page of the console; an object stored without a content type is previewed as the
type of its extension, the way [static website hosting](semantics.md#static-website-hosting) serves one. The requests
of the console aren't recorded in the statistics of the service, so browsing doesn't show up as traffic of the
application under test; the bucket it creates and what it uploads and deletes do reach the
[change listeners](embedding.md#listen-to-bucket-and-object-changes), like every other change.

Being paths of the service, `/_admin/ui` and the paths below it are the console rather than the objects of a bucket
named `_admin`, like the other `/_admin` endpoints and `/_health` are.

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
