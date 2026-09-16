# Semantics

How LocalS3 behaves where a client can tell the difference: which requests it rejects, how conditional requests and
versioning work, which entity tags it answers, and when change events are delivered.

LocalS3 aims to reject what Amazon S3 rejects, so a test that passes against LocalS3 doesn't pass with a request that
Amazon S3 would refuse.

- [Request validation](#request-validation)
- [Conditional requests](#conditional-requests)
- [Versioning](#versioning)
- [Entity tags of multipart uploads](#entity-tags-of-multipart-uploads)
- [Browser form uploads (POST Object)](#browser-form-uploads-post-object)
- [Lifecycle configuration](#lifecycle-configuration)
- [Change events](#change-events)

## Request validation

+ Bucket names must follow the [naming rules](https://docs.aws.amazon.com/AmazonS3/latest/userguide/bucketnamingrules.html)
  of general purpose buckets, e.g. 3 to 63 lowercase letters, numbers, periods and hyphens; otherwise `CreateBucket`
  fails with `InvalidBucketName`. Buckets loaded from an existing data path stay accessible.
+ Every part of a multipart upload except the last one must be at least 5 MiB, the
  [minimum part size](https://docs.aws.amazon.com/AmazonS3/latest/userguide/qfacts.html) of Amazon S3; otherwise
  `CompleteMultipartUpload` fails with `EntityTooSmall`. An upload with a single part may be of any size.
+ A request body is limited to 5 GiB by default (`maxRequestBodySize`), and its header section to 16 KiB
  (`maxRequestHeaderSize`). A body whose declared `Content-Length` is already too large is rejected with
  `EntityTooLarge` before `100 Continue` is sent, so the client never uploads it.
+ If credentials are configured, the signature of a request with a body is verified before the body is received, so
  the body of a request that fails anyway is neither uploaded nor buffered.

## Conditional requests

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
losing its protection. See [architecture.md](architecture.md#bucketguard-the-concurrency-model) for the lock.

## Versioning

A bucket is in one of three states, as in Amazon S3: never versioned, versioning enabled, or versioning suspended.
The version that an operation reports in `x-amz-version-id` follows from it:

| The object was stored…                                        | Reported version                                |
|---------------------------------------------------------------|-------------------------------------------------|
| in a bucket that was never versioned                           | none; the header is left out                    |
| before versioning was enabled or suspended                     | `null`, a value that is sent                    |
| while versioning was enabled                                   | the version ID it was given                     |

+ A `DeleteObject` without a version ID adds a delete marker. In a bucket whose versioning is enabled, the marker gets
  a version ID of its own and the earlier versions are kept; otherwise it replaces the `null` version.
+ A `DeleteObject` with a version ID removes that version for good. Unlike Amazon S3, it answers `404 NoSuchKey` if the
  key holds no version at all.
+ `GetObject`, `HeadObject`, `CopyObject` (through `versionId` of the source) and the tagging and ACL operations
  address a version with `versionId`; a conditional read of a version is evaluated against that version.
+ `ListObjectVersions` lists the versions and delete markers of the keys, newest first.

## Entity tags of multipart uploads

The object of a completed multipart upload gets the entity tag of Amazon S3: the MD5 of the concatenated MD5 digests
of its parts, followed by `-<number of parts>`. Before 2.5 LocalS3 answered the MD5 of the whole content instead;
`compositeMultipartEtags(false)`, `@LocalS3(compositeMultipartEtags = false)` or
`LOCAL_S3_COMPOSITE_MULTIPART_ETAGS=false` bring that back for tests that depend on it.

## Browser form uploads (POST Object)

A web page can upload a file straight to a bucket with an HTML form, without the file passing through its backend:
the backend creates a **policy**, a base64-encoded JSON document that says what the form may upload and until when,
signs it, and puts both into hidden fields of the form. LocalS3 implements that flow, `POST Object`, so the
frontend can be developed and its policies debugged locally rather than against Amazon S3.

```html
<form action="http://localhost:29090/uploads" method="post" enctype="multipart/form-data">
  <input type="hidden" name="key" value="user/42/${filename}">
  <input type="hidden" name="Content-Type" value="image/png">
  <input type="hidden" name="success_action_status" value="201">
  <input type="hidden" name="x-amz-algorithm" value="AWS4-HMAC-SHA256">
  <input type="hidden" name="x-amz-credential" value="access-key-id/20300101/us-east-1/s3/aws4_request">
  <input type="hidden" name="x-amz-date" value="20300101T000000Z">
  <input type="hidden" name="policy" value="eyJleHBpcmF0aW9uIjoi...">
  <input type="hidden" name="x-amz-signature" value="4b8c...">
  <input type="file" name="file">
  <button>Upload</button>
</form>
```

The fields are usually generated rather than written by hand, e.g. with `createPresignedPost` of
`@aws-sdk/s3-presigned-post` or `generate_presigned_post` of boto3, pointed at LocalS3 as their endpoint with
path-style addressing.

**The form.** The fields that precede `file` carry what a `PutObject` request carries in headers: `key` (required;
`${filename}` is replaced by the name of the selected file), `Content-Type`, `Cache-Control`, `Content-Disposition`,
`Content-Encoding`, `Content-Language`, `Expires`, `x-amz-meta-*` and `tagging` (a `Tagging` XML document). Field
names are case-insensitive, the fields after `file` are ignored, and the fields before it are limited to 20 KB. The
object's change event is `PostObject`, `s3:ObjectCreated:Post`.

**Authentication.** A service with credentials requires a `policy` and its signature, as fields rather than headers:
Signature Version 4 (`x-amz-algorithm`, `x-amz-credential`, `x-amz-date`, `x-amz-signature`), or the Signature
Version 2 that older upload libraries send (`AWSAccessKeyId`, `signature`). A service without credentials accepts
forms without a policy, but **checks the policy of a form that carries one**, so that its expiration and conditions
can be debugged without setting up credentials.

**The policy.** `expiration` is checked against the current time, and every condition against the form:

| Condition | Satisfied if |
|---|---|
| `{"field": "value"}` or `["eq", "$field", "value"]` | the field equals the value |
| `["starts-with", "$field", "prefix"]` | the field starts with the prefix; `""` accepts any value. Each value of a comma-separated `Content-Type` must |
| `["content-length-range", min, max]` | the size of the file is between `min` and `max` bytes |

`bucket` is always the bucket that the form is posted to. Every field of the form must be named by a condition, except
`policy`, `x-amz-signature`, `signature`, `AWSAccessKeyId`, `file` and the fields that start with `x-ignore-`.

| Problem | Response |
|---|---|
| The body isn't `multipart/form-data` | `400 RequestIsNotMultiPartContent` |
| The body is malformed, or has no `file` | `400 MalformedPOSTRequest`, `400 IncorrectNumberOfFilesInPostRequest` |
| The fields before `file` exceed 20 KB | `400 MaxPostPreDataLengthExceededError` |
| No `key` | `400 InvalidArgument` |
| No policy or signature, on a service with credentials | `403 AccessDenied` |
| A wrong signature, or an unknown access key | `403 SignatureDoesNotMatch`, `403 InvalidAccessKeyId` |
| The policy isn't base64, isn't JSON, lacks `expiration` or `conditions`, or has an unknown condition | `400 InvalidPolicyDocument` |
| The policy expired | `403 AccessDenied`: `Invalid according to Policy: Policy expired.` |
| A condition fails | `403 AccessDenied`: `Invalid according to Policy: Policy Condition failed: ["starts-with","$key","user/42/"]` |
| A field that no condition names | `403 AccessDenied`: `Invalid according to Policy: Extra input fields: x-amz-meta-note` |
| The file is outside of `content-length-range` | `400 EntityTooSmall`, `400 EntityTooLarge` |

**The response.** With `success_action_redirect` (or the older `redirect`), `303 See Other` to that URL with
`bucket`, `key` and `etag` added to its query. Otherwise the `success_action_status` of the form: `200`, `201` with
a `PostResponse` document (`Location`, `Bucket`, `Key`, `ETag`), or `204`, the default. The `ETag`, `Location` and
`x-amz-version-id` headers carry the same. A page on another origin reads the response if the CORS configuration of
the bucket allows `POST` from it and exposes those headers.

Accepted but not applied, like the headers of `PutObject`: `acl`, `x-amz-storage-class` and the server-side
encryption fields. They still have to be named by the policy.

## Lifecycle configuration

`PutBucketLifecycleConfiguration`, `GetBucketLifecycleConfiguration` and `DeleteBucketLifecycle` store, return and
delete the lifecycle configuration of a bucket, so that frameworks that set one when they start, e.g. to clean up
temporary files, work against LocalS3 instead of failing with `501 NotImplemented`.

**The configuration is saved, but never takes effect.** LocalS3 doesn't expire objects or noncurrent versions,
doesn't remove expired delete markers, doesn't transition anything to another storage class, and doesn't abort
incomplete multipart uploads. A test that relies on a rule being applied needs to delete the objects itself.

What is checked is the structure that Amazon S3 checks, so a configuration that Amazon S3 rejects isn't accepted:

| Configuration | Answer |
|---|---|
| Not well-formed XML, a document type, a root element other than `LifecycleConfiguration`, no `Rule`, or an element other than `Rule` in it | `400 MalformedXML` |
| A rule whose `Status` isn't `Enabled` or `Disabled` | `400 MalformedXML` |
| A rule without an action (`Expiration`, `Transition`, `NoncurrentVersionExpiration`, `NoncurrentVersionTransition` or `AbortIncompleteMultipartUpload`) | `400 InvalidRequest` |
| More than 1000 rules | `400 InvalidRequest` |
| An `ID` longer than 255 characters, or the same `ID` in two rules | `400 InvalidArgument` |
| An `x-amz-transition-default-minimum-object-size` other than `all_storage_classes_128K` or `varies_by_storage_class` | `400 InvalidArgument` |

The contents of the filters and actions aren't checked, since nothing reads them. The document is stored as it was
put, and `GetBucketLifecycleConfiguration` returns it as is, with the `x-amz-transition-default-minimum-object-size` it
was put with, `all_storage_classes_128K` by default. A bucket without a configuration answers
`404 NoSuchLifecycleConfiguration`; deleting the configuration of such a bucket succeeds.

## Change events

A service can be given `S3ChangeListener`s, which receive an `S3Change` whenever a bucket or an object changes, e.g.
to trigger an indexer or to assert in a test that an upload happened. [embedding.md](embedding.md#listen-to-bucket-and-object-changes)
shows how to subscribe one. Several listeners may be subscribed; each receives every change.

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
delivered before the S3 response is sent. With a `changeListenerExecutor`, changes are delivered asynchronously, so
that slow listeners don't hold up request handling; a single-threaded executor keeps the changes in order.

LocalS3 does not shut the executor down; that stays with the code that created it. Either way, an exception
thrown by a listener is logged and never fails the S3 request, and a change that the executor rejects is
dropped with a log entry.
