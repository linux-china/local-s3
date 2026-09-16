# Architecture

This page is for contributors, and for users who want to know why LocalS3 behaves the way it does under concurrency or
after a crash. It covers the modules, the path of a request, the concurrency model around `BucketGuard`, the layers of
storage, and the layout of a data directory.

- [Modules](#modules)
- [The path of a request](#the-path-of-a-request)
- [BucketGuard: the concurrency model](#bucketguard-the-concurrency-model)
- [Metadata and storage](#metadata-and-storage)
- [Persistence layout](#persistence-layout)

## Modules

| Module | Responsibility |
|---|---|
| `local-s3-datatypes` | The request and response models of S3 and S3 Vectors (Jackson XML / JSON). |
| `local-s3-core` | The metadata model, the services, the storage, the locks, persistence and vector search. No HTTP. |
| `local-s3-rest` | The Netty HTTP server: routing, controllers, SigV4 verification, CORS, virtual hosts, change listeners, admin endpoints. |
| `local-s3-jupiter` | The JUnit 5 extension `@LocalS3`, which injects `S3Client` and `S3VectorsClient`. |
| `local-s3-testcontainers` | `LocalS3Container`. |
| `local-s3-standalone` | The executable fat jar and the Docker images (JVM and GraalVM native). |
| `local-s3-spring-boot-starter` | The starter for Spring Boot 4 applications. |
| `local-s3-integration-test` | End-to-end tests with the AWS SDK v2 and DuckDB. Not published. |

`local-s3-core` knows nothing of HTTP, so the services can be called directly, e.g. through
`localS3.getS3Manager().objectService()`, and behave the same as through the HTTP API: they take the same locks and
publish the same changes.

## The path of a request

```
socket ─▶ event loop (Netty)                                  ─▶ executor (virtual thread per request)
          HttpRequestDecoder                                      LocalS3Router ─▶ controller
          LocalS3HttpRequestDecoder  (head verified, body            │
                                      buffered in heap or file)     ▼
          LocalS3HttpResponseEncoder                              service (local-s3-core)
          LocalS3HttpMessageHandler  ◀── response written ───────   │  BucketGuard: lock, persist, publish
                                                                     ▼
                                                                  metadata store + storage
```

+ **Event loop.** `LocalS3ServerInitializer` builds the pipeline. HTTP parsing, body aggregation and response encoding
  run on the connection's event loop. A body up to `requestBodyFileThreshold` (4 MiB) is buffered on the heap, a larger
  one in a temporary file, written by the executor so that an event loop never waits for the disk. In `PERSISTENCE`
  mode that file is created under `.storage/.request-bodies/`, on the file system of the storage, so that storing an
  upload renames the file into place instead of writing the body a second time.
+ **Verification before the body.** The signature of a request with a body is verified from its head, before
  `100 Continue` is sent, so a request that fails anyway is never uploaded.
+ **Executor.** `LocalS3HttpMessageHandler` hands the request to the executor, which all connections share, and stops
  reading the connection until the response is written. The requests of a connection are handled one at a time, in
  order; a slow request only holds up its own connection.
+ **Routing.** `LocalS3Router` picks the route whose conditions on parameters and headers are the most specific, never
  depending on registration order. A request that still matches several routes equally, e.g. `?acl&tagging`, is
  answered with `InvalidRequest`. Routes are verified for ambiguity when the router is built.
+ **Streaming.** A controller writes a `StreamingHttpResponse`, so the content of a large object is streamed from the
  storage rather than loaded into memory.

`InFlightRequests` counts every request from the moment it is handed to the executor until its response is written,
which is what a graceful shutdown waits for.

## BucketGuard: the concurrency model

Every operation of a service on a bucket runs through the `BucketGuard` of its LocalS3 service. The guard ties three
things together, so that they can't disagree: the **lock** of the bucket, the **persistence** of the bucket, and the
**publication** of its changes.

```java
// A read
return withBucketReadLock(bucketName, () -> ...);

// A change
return changeBucket(bucketName, BucketGuard.Change.UPDATE, () -> ...);
```

The services call it explicitly, through `BucketGuardApplicable`, so the lock and the persistence of an operation are
visible where the operation is implemented.

### Locks

+ **One read-write lock per bucket.** `DefaultBucketLock` stripes the buckets over 64 `ReentrantReadWriteLock`s by the
  hash of the bucket name, so the locks take bounded memory however many buckets come and go. Two buckets that share a
  stripe may wait for each other, which is safe because an operation locks a single bucket.
+ **Per service.** Each LocalS3 service has its own locks, so services in the same JVM don't block each other.
+ **Reentrant, not upgradable.** An operation may call another operation on the same bucket. A change nested in a
  change of the same bucket runs within the outer one, which persists the bucket once. An operation that holds the
  read lock must not call one that changes the bucket.
+ **Exclusive operations.** Around every bucket lock, an operation holds a service-wide lock for reading.
  `exclusive(...)`, e.g. `reset()`, takes it for writing: it waits for the operations in progress and holds off new ones.

### Why a bucket lock, and not a lock per key

A lock per bucket serializes the changes of a bucket, which looks like a bottleneck. What takes the time is kept outside
the lock, though:

+ `PutObject` and `UploadPart` store the content **before** taking the lock. Only the commit of the metadata, which
  takes microseconds, holds the write lock; a failed commit deletes the stored content.
+ `GetObject` opens the content under the read lock, and the caller streams it without the lock.

What the lock buys is that every conditional write and delete (`If-Match`, `If-None-Match`) is evaluated and applied
atomically, so of the requests that race for a key exactly one wins; see [semantics.md](semantics.md#conditional-requests).
A lock per key would add little on top of that, and would complicate the operations that span keys, such as
`DeleteObjects`, listings and `DeleteBucket`.

### A change

`change(bucket, CREATE | UPDATE | DELETE, operation)` runs the operation under the write lock and then:

1. **Starts a storage transaction.** Within it, deleting an object only records the deletion (`TransactionalStorage`).
2. **Runs the operation**, which changes the in-memory metadata of the bucket. The metadata records which object keys
   it handed out for change (`BucketChangeScope`).
3. **Persists the bucket** to the metadata store: the settings of the bucket and only the changed keys.
4. **Commits the transaction**, which performs the recorded deletions.

If the operation or the persistence fails:

+ the objects written during the transaction are deleted, and the recorded deletions are discarded;
+ the in-memory metadata of the bucket is reloaded from the store, dropping what was changed in memory only — unless
  the failure is a rejection of the request (a `LocalS3Exception`), which the services raise before they change
  anything.

Deleting only after the metadata is persisted means that persisted metadata never references a deleted object, even if
the process dies in between; at worst an unreferenced file is left behind.

### Change events

The changes an operation publishes are held back until the outermost change of the thread has ended. A change that was
persisted is then delivered, after the bucket lock is released, so a listener may call the services again; a change
that failed is dropped. See [semantics.md](semantics.md#change-events).

## Metadata and storage

A service keeps two kinds of data apart:

+ **Metadata**: buckets, their settings, and for every object key its versions, tags, ACLs and the ID of each version's
  content; multipart uploads and their parts; vector buckets and their indexes.
+ **Content**: the bytes of objects and parts, and the values of vectors, each stored under an ID that the metadata
  references.

### Metadata: `LocalS3Store`

Both modes keep their metadata in an [H2 MVStore](https://www.h2database.com/html/mvstore.html), one per service,
wrapped by `LocalS3Store`. An `IN_MEMORY` service opens one that never writes a file; a `PERSISTENCE` service opens
`buckets.mvstore` in its data directory. The operations therefore behave the same in both modes, including the reload
of a bucket after a failed change.

`MVStoreBucketMetadataStore` spreads an S3 bucket over several maps, so that a change writes only what it changed:

| Map | Key | Value |
|---|---|---|
| `buckets` | bucket name | the settings of the bucket, e.g. region, versioning, ACL, CORS, without its objects |
| `objects/<bucket>` | object key | the metadata of the object, with all of its versions |
| `uploads/<bucket>` | object key | the multipart uploads in progress for that key |
| `vectors/buckets` | vector bucket name | the vector bucket, with its indexes and the metadata of its vectors |

The values are JSON, so the metadata model needs no `Serializable`, and a later version that adds fields still reads
the store. A vector bucket is written whole, unlike an S3 bucket.

**Lazy loading.** Opening a persistent store reads the keys of the objects, not their metadata. Each key gets an
`ObjectMetadataRef`, which reads the metadata from `objects/<bucket>` on first use; `ObjectMetadataCache` bounds how
many are in heap (`LOCAL_S3_OBJECT_METADATA_CACHE_MAX_ENTRIES`) and evicts the least recently read. A reference that a
change holds is pinned until the change is written.

**Sharing.** MVStore locks the file it opens, so the services of a JVM that use the same data directory share one open
store, reference-counted, and closed when the last of them shuts down. The file is compacted on that close.

**Commits.** `PersistencePolicy.DURABLE` commits every change; `FAST` lets MVStore commit in the background, at most a
second later, and commits the rest on close. See [deployment.md](deployment.md#persistence-policy).

### Content: the `Storage` layers

`Storage` is a key-value store of content by ID. The implementations compose:

| Storage | Role |
|---|---|
| `InMemoryStorage` | Content on the heap, in chunks, so an object may exceed the largest Java array. |
| `LocalFileSystemStorage` | One file per ID under `.storage/`, written to a temporary file and renamed into place, so a crash leaves the old content or none. Read-only over initial data. |
| `TransactionalStorage` | Defers deletions to the commit of a change, and deletes the writes of a failed one; see [A change](#a-change). |
| `LayeredStorage` | A writable frontend over a read-only backend: writes and deletes go to the frontend, reads fall through to the backend. |
| `CopyOnAccessStorage` | Over a read-only backend, copies an object into the heap the first time it is read, within a shared byte budget (`CopyBudget`). |

How a service combines them:

| Mode | Metadata | Content |
|---|---|---|
| `IN_MEMORY` | in-memory `LocalS3Store` | `InMemoryStorage` |
| `IN_MEMORY` with initial data | in-memory `LocalS3Store`, seeded from the data directory's `buckets.mvstore`, opened read-only | `LayeredStorage` over the read-only directory; with the initial data cache, `CopyOnAccessStorage` over it, shared by the services that start from the same directory |
| `PERSISTENCE` | `buckets.mvstore` | `TransactionalStorage` over `LocalFileSystemStorage` |

Vectors mirror this with `VectorStorage`: `InMemoryVectorStorage`, `FileSystemVectorStorage`, `TransactionalVectorStorage`
and `LayeredVectorStorage`. `FileSystemVectorStorage` keeps all vectors of one dimension in one file of fixed-length
records, and in memory in contiguous `float` arrays, so a query reads no file.

## Persistence layout

```
<data-path>/
├── buckets.mvstore            metadata of all S3 buckets and vector buckets
├── .storage/                  content of objects and parts
│   ├── 3f/a2/<id>             one file per content ID, in two levels of hashed subdirectories
│   └── .request-bodies/       temporary files of large request bodies, renamed into place on store
└── vectors/
    └── .storage/
        └── vectors-<d>.vec    all vectors of dimension <d>
```

+ **`buckets.mvstore`** holds every piece of metadata, of S3 and S3 Vectors alike, so a copy of this file taken while the
  service is stopped is a consistent point of the whole service. Everything else in the directory is referenced from it
  by ID.
+ **`.storage/ab/cd/<id>`**: the subdirectories are named by a hash of the ID (`ShardedFileLayout`), not by its bits,
  because IDs generated around the same time share their high bits. This keeps any one directory small, which matters
  for listing and lookups on some file systems. The layout decides where existing files are found, so it must never
  change.
+ **`vectors-<d>.vec`**: a 16-byte header (magic `LS3VECTS`, format version, dimension) followed by records of
  `8 + 4·d` bytes, the storage ID and the `float32` values, little-endian. A record whose ID is `0` is free and reused.
  A vector is written as a free record first and its ID last, so a crash never leaves a partial vector.

### Layouts of earlier versions

| Before 2.5 | 2.5 |
|---|---|
| `<bucket>.bucket.meta`, one JSON file per bucket | `buckets.mvstore` — **not migrated**, see the [CHANGELOG](../CHANGELOG.md#upgrading-from-24) |
| `vectors/<bucket>.vectorbucket.meta` | `buckets.mvstore`, map `vectors/buckets` — **not migrated** |
| `.storage/<id>`, flat | `.storage/ab/cd/<id>` — moved on start, one rename at a time |
| one file per vector | `vectors/.storage/vectors-<d>.vec` — the files of `vectors/.storage/` are imported on start and deleted; 2.4 wrote them to `.storage/` of the working directory instead, where they aren't looked for |

A read-only storage, e.g. over the initial data of an `IN_MEMORY` service, reads both layouts of the content and changes
nothing.
