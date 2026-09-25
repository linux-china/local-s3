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
- [Access control lists](#access-control-lists)
- [Lifecycle configuration](#lifecycle-configuration)
- [Object Lock](#object-lock)
- [Storage classes and restores](#storage-classes-and-restores)
- [Appends and renames](#appends-and-renames)
- [S3 Express One Zone directory buckets](#s3-express-one-zone-directory-buckets)
- [Server-side encryption with S3 managed and KMS keys (SSE-S3, SSE-KMS)](#server-side-encryption-with-s3-managed-and-kms-keys-sse-s3-sse-kms)
- [Server-side encryption with customer-provided keys (SSE-C)](#server-side-encryption-with-customer-provided-keys-sse-c)
- [The KMS endpoint](#the-kms-endpoint)
- [Change events](#change-events)

## Request validation

+ Bucket names must follow the [naming rules](https://docs.aws.amazon.com/AmazonS3/latest/userguide/bucketnamingrules.html)
  of general purpose buckets, e.g. 3 to 63 lowercase letters, numbers, periods and hyphens; otherwise `CreateBucket`
  fails with `InvalidBucketName`. Buckets loaded from an existing data path stay accessible.
+ Every part of a multipart upload except the last one must be at least 5 MiB, the
  [minimum part size](https://docs.aws.amazon.com/AmazonS3/latest/userguide/qfacts.html) of Amazon S3; otherwise
  `CompleteMultipartUpload` fails with `EntityTooSmall`. An upload with a single part may be of any size.
+ A request body is limited to 5 GiB by default (`netty(netty -> netty.maxRequestBodySize(...))`), and its header
  section to 16 KiB (`maxRequestHeaderSize`). A body whose declared `Content-Length` is already too large is rejected with
  `EntityTooLarge` before `100 Continue` is sent, so the client never uploads it.
+ A single `PutObject` or `UploadPart` may send up to 5 GiB, like Amazon S3 allows. A body larger than 2 GiB is kept
  in its temporary file and read from there, since no memory-mapped buffer holds it; a browser form upload
  (`POST Object`) larger than 2 GiB is rejected with `EntityTooLarge`.
+ If credentials are configured, the signature of a request with a body is verified before the body is received, so
  the body of a request that fails anyway is neither uploaded nor buffered.
+ `x-amz-expected-bucket-owner` and `x-amz-source-expected-bucket-owner` are **accepted and ignored**: LocalS3 has one
  account, which owns every bucket, so a request never fails with `403 AccessDenied` for naming another owner. A test
  that relies on that check must run against Amazon S3.

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
`s3Api(s3 -> s3.compositeMultipartEtags(false))`, `@LocalS3(compositeMultipartEtags = false)` or
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
names are case-insensitive, the fields after `file` are ignored, and the fields before it are limited to 20 KB. A form
of more than 2 GiB is rejected with `EntityTooLarge`. The object's change event is `PostObject`,
`s3:ObjectCreated:Post`.

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

## Access control lists

`PutBucketAcl` and `PutObjectAcl` store an ACL, which `GetBucketAcl` and `GetObjectAcl` return. LocalS3 doesn't
enforce it against signed requests; the one place an ACL takes effect is
[static website hosting](#static-website-hosting), where a bucket that grants the `AllUsers` group `READ` answers
requests that carry no credentials. Like Amazon S3, a request gives the ACL in exactly one of three ways:

+ **A canned ACL**, the `x-amz-acl` header, which grants the owner `FULL_CONTROL`, and `private` nothing more;
  `public-read` and `public-read-write` grant the `AllUsers` group `READ`, and `WRITE`; `authenticated-read` grants the
  `AuthenticatedUsers` group `READ`; `aws-exec-read` grants Amazon EC2 `READ`. `bucket-owner-read` and
  `bucket-owner-full-control` grant the owner of the bucket `READ` or `FULL_CONTROL`, and only apply to objects;
  `log-delivery-write` grants the `LogDelivery` group `WRITE` and `READ_ACP`, and only applies to buckets.
+ **Grant headers**, `x-amz-grant-read`, `x-amz-grant-write`, `x-amz-grant-read-acp`, `x-amz-grant-write-acp` and
  `x-amz-grant-full-control`, each a comma-separated list of grantees: `id="..."`, `uri="..."` or `emailAddress="..."`.
+ **An `AccessControlPolicy` document** in the body.

An ACL of headers keeps the owner that the bucket or object has. The rejected requests:

| Request | Error |
|---|---|
| A canned ACL together with grant headers | `400 InvalidRequest` |
| A canned ACL or grant headers together with a body | `400 UnexpectedContent` |
| No canned ACL, no grant headers and no body | `400 MissingSecurityHeader` |
| An unknown canned ACL, one that doesn't apply to the resource, or a malformed grant header | `400 InvalidArgument` |
| A body that isn't an `AccessControlPolicy` document | `400 MalformedACLError` |

## Static website hosting

A bucket of static files is served as a website on the port of the S3 API, so a test can put an object with its S3
client and open the page in a browser without a second server:

```
http://localhost:29090/my-site/          -> my-site/index.html
http://localhost:29090/my-site/docs      -> 302 to /my-site/docs/
http://localhost:29090/my-site/docs/     -> my-site/docs/index.html
http://localhost:29090/my-site/gone.html -> 404, the error document of the bucket or a generic error page
```

Virtual-hosted-style requests work too, e.g. `http://my-site.localhost:29090/`.

### Which requests are served as a website

A request is answered as a website request when **all** of these hold; anything else keeps the S3 semantics it had
before:

+ it is a `GET` or a `HEAD`;
+ it **carries no credentials**: no `Authorization` header, and no `X-Amz-Algorithm` of a presigned URL. So the
  requests of an S3 client, which are signed, are never affected, and a presigned URL still reads a private bucket;
+ it names no operation of the S3 API in its query, e.g. `?acl`, `?uploads` or `?list-type=2`. A query of the page
  itself, e.g. `?v=3` of a cache-busting link, is ignored, like Amazon S3 ignores it;
+ the bucket **allows anonymous reads** of the key, see below.

The one request such a bucket keeps the S3 semantics of is its root: with no index document to serve in its place,
`GET /my-site` is answered by `ListObjects`, so an unsigned listing of a public bucket still works.

### Which buckets are public

A bucket allows an anonymous read when either of these makes it public, and the
[public access block](https://docs.aws.amazon.com/AmazonS3/latest/API/API_PublicAccessBlockConfiguration.html) of the
bucket doesn't take it away:

+ its **ACL** grants the `AllUsers` group `READ` or `FULL_CONTROL`, which the canned ACL `public-read` does:

  ```java
  s3.putBucketAcl(b -> b.bucket("my-site").acl(BucketCannedACL.PUBLIC_READ));
  ```

+ its **bucket policy** allows `s3:GetObject` of the key to every principal:

  ```json
  {"Version": "2012-10-17", "Statement": [{
    "Effect": "Allow", "Principal": "*", "Action": "s3:GetObject", "Resource": "arn:aws:s3:::my-site/*"
  }]}
  ```

  The resources are matched against the key, so a policy on `my-site/public/*` publishes that prefix alone. An
  explicit `Deny` wins over every `Allow`. A statement with a `Condition` is skipped: LocalS3 evaluates no conditions,
  and a policy that a condition narrows must not open the bucket wider than it says.

`IgnorePublicAcls` takes the ACL away, `BlockPublicPolicy` the policy, and `RestrictPublicBuckets` either of them.

Every other bucket stays private, and an unsigned request of it is rejected as before. To serve **every** bucket
without publishing it, which is meant for local development, set `LOCAL_S3_WEBSITE_ALL_BUCKETS=true`,
`local-s3.website.all-buckets=true` or `LocalS3.builder().website(website -> website.allBuckets(true))`. That also
lets an unsigned request read the objects of a private bucket, so it is off by default. `LOCAL_S3_WEBSITE=false` turns website hosting off
altogether.

### The index and error documents

A bucket needs no configuration: a request for a directory is answered with `index.html` of that directory if the
bucket has one, and a key that isn't there with a generic error page. `PutBucketWebsite` configures a bucket of its
own, and LocalS3 applies it:

| Element | What it does |
|---|---|
| `IndexDocument/Suffix` | The object that a request for a directory is answered with, e.g. `home.html`. |
| `ErrorDocument/Key` | The object that a `404` is answered with, under the status of the failure rather than `200`. |
| `RedirectAllRequestsTo` | Answers every request of the bucket with a `301` to that host, keeping the key. |
| `RoutingRules` | Redirects the requests that a rule matches: `Condition` by `KeyPrefixEquals`, by `HttpErrorCodeReturnedEquals`, or by both; `Redirect` by `HostName`, `Protocol`, `HttpRedirectCode`, `ReplaceKeyWith` and `ReplaceKeyPrefixWith`. The first rule that matches wins. |

`website(website -> website.indexDocument(...).errorDocument(...))`, or the matching variables and properties,
change the documents of the buckets that have no configuration of their own.

### Website redirect locations

An object stored with `x-amz-website-redirect-location` (`PutObject`, `CopyObject`, `CreateMultipartUpload` or a
`POST Object` form field) is answered by the website with a `301` to that location instead of its content, whether it
is addressed by its key or is the index document of a directory. The location is a path, e.g. `/docs/new.html`, or an
`http://` or `https://` URL; anything else, or one longer than 2 KB, answers `400 InvalidArgument`. `GetObject` and
`HeadObject` of the S3 API answer the header and the content as usual. Like on Amazon S3, `CopyObject` doesn't copy
the location of its source; the copy has the one of the request, if any.

### Content types

An object is served with the content type it was stored with. An object stored **without** one, which LocalS3 keeps as
`binary/octet-stream`, is served with the content type of its extension instead, e.g. `text/html; charset=utf-8` for
`.html` and `text/css; charset=utf-8` for `.css`, so that a directory of files copied into a bucket is a working site
rather than a set of downloads. A content type that was chosen is never overridden.

## Lifecycle configuration

`PutBucketLifecycleConfiguration`, `GetBucketLifecycleConfiguration` and `DeleteBucketLifecycle` store, return and
delete the lifecycle configuration of a bucket, so that frameworks that set one when they start, e.g. to clean up
temporary files, work against LocalS3 instead of failing with `501 NotImplemented`.

**The configuration never takes effect by itself**: nothing expires while a test runs. A test that exercises
expiration asks LocalS3 to apply the configurations at a time of its choosing, which may be in the future, so that it
doesn't wait for days to pass:

```shell
# Apply every bucket's configuration as if it were 31 days from now.
curl -s -X POST "http://localhost:29090/_admin/lifecycle?days=31"
# Apply one bucket's configuration at a given instant.
curl -s -X POST "http://localhost:29090/_admin/lifecycle?bucket=my-bucket&now=2030-01-01T00:00:00Z"
```

```java
List<LifecycleActionAns> actions = localS3.applyLifecycle(Instant.now().plus(Duration.ofDays(31)));
// or, without the HTTP server: localS3.getS3Manager().objectService().applyLifecycle("my-bucket", instant)
```

The answer lists the actions taken, each with the bucket, the rule `ID`, the type, the key and the version or upload.
The enabled rules are applied the way Amazon S3 applies them:

| Rule | Action |
|---|---|
| `Expiration` with `Days` or `Date` | The current version of an object that the filter selects expires: it is deleted in a bucket whose versioning was never enabled (`OBJECT_EXPIRED`), and gets a delete marker in a versioned bucket (`DELETE_MARKER_CREATED`). |
| `NoncurrentVersionExpiration` | The versions and delete markers that have been noncurrent for `NoncurrentDays` are deleted, keeping the `NewerNoncurrentVersions` most recent noncurrent ones (`NONCURRENT_VERSION_EXPIRED`). A version that [Object Lock](#object-lock) protects is kept. |
| `Expiration` with `ExpiredObjectDeleteMarker` | A delete marker that is the only version left of its object is removed (`EXPIRED_DELETE_MARKER_REMOVED`). |
| `AbortIncompleteMultipartUpload` | The uploads of the keys that the prefix selects, created `DaysAfterInitiation` ago, are aborted (`MULTIPART_UPLOAD_ABORTED`). |
| `Transition`, `NoncurrentVersionTransition` | Nothing: LocalS3 has one storage class. |

A filter selects by `Prefix`, `Tag`, `ObjectSizeGreaterThan` and `ObjectSizeLessThan`, alone or in an `And`; a rule
with the legacy `Prefix` outside a `Filter` selects by it. A number of days counts from the creation of the object, the
time a version became noncurrent, i.e. the creation of the next version, or the creation of the upload, and is rounded
up to the next midnight UTC, like Amazon S3 does. The changes are published with the operation `LifecycleExpiration`,
see [change events](#change-events).

What is checked is the structure that Amazon S3 checks, so a configuration that Amazon S3 rejects isn't accepted:

| Configuration | Answer |
|---|---|
| Not well-formed XML, a document type, a root element other than `LifecycleConfiguration`, no `Rule`, or an element other than `Rule` in it | `400 MalformedXML` |
| A rule whose `Status` isn't `Enabled` or `Disabled` | `400 MalformedXML` |
| A rule without an action (`Expiration`, `Transition`, `NoncurrentVersionExpiration`, `NoncurrentVersionTransition` or `AbortIncompleteMultipartUpload`) | `400 InvalidRequest` |
| More than 1000 rules | `400 InvalidRequest` |
| An `ID` longer than 255 characters, or the same `ID` in two rules | `400 InvalidArgument` |
| An `x-amz-transition-default-minimum-object-size` other than `all_storage_classes_128K` or `varies_by_storage_class` | `400 InvalidArgument` |

The contents of the filters and actions aren't checked beyond that. The document is stored as it was put, and `GetBucketLifecycleConfiguration` returns it as is, with the `x-amz-transition-default-minimum-object-size` it
was put with, `all_storage_classes_128K` by default. A bucket without a configuration answers
`404 NoSuchLifecycleConfiguration`; deleting the configuration of such a bucket succeeds.

## Object Lock

[Object Lock](https://docs.aws.amazon.com/AmazonS3/latest/userguide/object-lock.html) protects object versions from
being deleted, for testing compliance code, e.g. that a retention is set on what is stored, or that a delete is refused.

+ A bucket gets Object Lock when it is created with `x-amz-bucket-object-lock-enabled: true`, which enables its
  versioning too, or when `PutObjectLockConfiguration` is sent to a bucket whose versioning is enabled; otherwise it
  answers `409 InvalidBucketState`. Object Lock can't be disabled, and `PutBucketVersioning` can't suspend the
  versioning of such a bucket (`409 InvalidBucketState`). `GetObjectLockConfiguration` of a bucket without it answers
  `404 ObjectLockConfigurationNotFoundError`.
+ The configuration may have a default retention, a `Mode` with either `Days` (at most 36500) or `Years` (at most
  100), which every new version stored without a retention of its own gets.
+ `PutObject`, `CopyObject` and `CreateMultipartUpload` store a version with `x-amz-object-lock-mode`,
  `x-amz-object-lock-retain-until-date` (both or neither, in the future) and `x-amz-object-lock-legal-hold`. They answer
  `400 InvalidRequest` for a bucket without Object Lock. A copy doesn't copy the settings of its source.
  `GetObject` and `HeadObject` answer the same headers.
+ `PutObjectRetention`/`GetObjectRetention` and `PutObjectLegalHold`/`GetObjectLegalHold` address the current version,
  or the one of `versionId`. A version whose retention or legal hold was never set answers
  `404 NoSuchObjectLockConfiguration`.
+ A retention that hasn't expired can be extended, and a `GOVERNANCE` one turned into a `COMPLIANCE` one; shortening,
  removing or relaxing it answers `403 AccessDenied`, unless it is in `GOVERNANCE` mode and the request sends
  `x-amz-bypass-governance-retention: true`.
+ Deleting a version for good, with `DeleteObject` or `DeleteObjects` and a `versionId`, answers `403 AccessDenied` while
  its legal hold is on, or its retention hasn't expired, unless the retention is in `GOVERNANCE` mode and the request
  bypasses it. A delete without a version ID still adds a delete marker, like in Amazon S3.

Unlike Amazon S3, LocalS3 doesn't require a `Content-MD5` or checksum on the requests that store a version with Object
Lock settings.

## Storage classes and restores

`x-amz-storage-class` is stored with an object and answered by `HeadObject`, `GetObject` and the listings, but every
object is kept and read the same way: a `GLACIER` or `DEEP_ARCHIVE` object is readable without a restore.

`RestoreObject` records a restore rather than performing one, so that code that restores cold data before reading it
runs against LocalS3:

+ An object of the `GLACIER` or `DEEP_ARCHIVE` storage class is restored **at once**: the first restore answers
  `202 Accepted`, and one of an object whose restored copy hasn't expired answers `200 OK` and extends the copy.
+ `HeadObject` and `GetObject` then answer `x-amz-restore: ongoing-request="false", expiry-date="..."`, where the expiry
  date is midnight UTC after `Days` days, like Amazon S3 rounds it. After that date the header is gone, and the next
  restore answers `202` again.
+ `Days` is required; the `Tier` and the other elements of the `RestoreRequest` are ignored.
+ An object of any other storage class, including `GLACIER_IR` and `INTELLIGENT_TIERING`, answers
  `403 InvalidObjectState`, and a delete marker `405 MethodNotAllowed`.

## Appends and renames

Amazon S3 offers these two for the directory buckets of S3 Express One Zone. LocalS3 offers them for the buckets whose
versioning was never enabled, which directory buckets are like; a versioned bucket answers `400 InvalidRequest` to a
rename.

+ **Append**: a `PutObject` with `x-amz-write-offset-bytes` appends its content to the object, if the offset is the size
  of the object, or is `0` for a key that holds no object, which creates it; otherwise it answers
  `400 InvalidWriteOffset`, as it does if another request changed the object while the content was being stored. The
  object keeps its content type, metadata and tags. Its entity tag is the MD5 of the whole content, and a checksum or
  `Content-MD5` of the request is verified against the appended bytes. The response carries `x-amz-object-size`.
+ **RenameObject**: `PUT /bucket/new-key?renameObject` with `x-amz-rename-source: /bucket/old-key` moves the object,
  without copying its content, and replaces the object the new key holds. `If-Match`, `If-None-Match`,
  `If-Modified-Since` and `If-Unmodified-Since` are evaluated against the destination, and the
  `x-amz-rename-source-if-*` headers against the source; either answers `412 PreconditionFailed`. The source is in the
  same bucket, named as `/bucket/key` or as the key alone. `x-amz-client-token` is accepted and ignored.

## S3 Express One Zone directory buckets

A bucket may be named like a directory bucket, `base-name--zone-id--x-s3`, e.g. `my-bucket--usw2-az1--x-s3`, which is
otherwise a reserved suffix. An AWS SDK addresses such a bucket the S3 Express way, and LocalS3 answers it the same way
it answers any bucket, so that client code written for S3 Express One Zone runs against LocalS3 unchanged:

+ **CreateSession**: `GET /bucket?session` answers session credentials, issued statelessly like the temporary
  credentials of the [STS endpoint](embedding.md#temporary-credentials-sts): they are valid for five minutes, across
  restarts, and the SDK renews them before they expire. `x-amz-create-session-mode` must be `ReadWrite` or `ReadOnly`,
  but a `ReadOnly` session may write too, and a session isn't bound to its bucket. The `x-amz-server-side-encryption*`
  headers are echoed, `AES256` by default. The bucket needn't exist: with an endpoint override, the AWS SDK for Java
  creates a session even for `CreateBucket`, which Amazon S3 answers through its control endpoint without one.
+ **Session credentials**: the requests of the session are signed for the `s3express` service, and carry the session
  token in `x-amz-s3session-token`, or in `X-Amz-S3session-Token` for a presigned URL. LocalS3 accepts `s3express`
  wherever it accepts `s3`.
+ By default the SDK addresses a directory bucket virtual-hosted style, e.g. `my-bucket--usw2-az1--x-s3.localhost:9090`,
  so the host must resolve to LocalS3, which `*.localhost` does on most systems; with `forcePathStyle` it works as well.
+ The bucket behaves like a general purpose bucket otherwise: the `Location` and `Bucket` of the
  `CreateBucketConfiguration` are ignored, and versioning, which directory buckets lack, isn't refused.

## Server-side encryption with S3 managed and KMS keys (SSE-S3, SSE-KMS)

`PutObject`, `POST Object`, `CopyObject` and `CreateMultipartUpload` accept `x-amz-server-side-encryption` (`AES256`,
`aws:kms` or `aws:kms:dsse`), and for the KMS algorithms `x-amz-server-side-encryption-aws-kms-key-id`,
`-context` and `-bucket-key-enabled`. **Nothing is encrypted, and the KMS endpoint is never called for it**: LocalS3 stores what the
request names with the object version, or with the upload, so that client code that sets these headers, e.g. Iceberg
`S3FileIO` with `s3.sse.type`, sees the responses it sees against Amazon S3.

What LocalS3 proves is that your code sends the right headers and reads the right responses, and nothing beyond it.
**Don't use LocalS3 to verify encryption strength, key rotation, or that a KMS key policy allows an object to be
read**: there is no ciphertext to be strong, no key to rotate, and no policy to deny. Those belong against Amazon S3.

+ The headers are validated: an unknown algorithm, a key ID or a context without a KMS algorithm, a context that isn't
  a base64 encoded JSON object, a `bucket-key-enabled` other than `true` or `false` with a KMS algorithm, and an
  algorithm together with an SSE-C key all answer `400 InvalidArgument`. The KMS key ID isn't resolved or checked.
+ `PutObject`, `POST Object`, `CopyObject`, `CreateMultipartUpload`, `UploadPart`, `UploadPartCopy`,
  `CompleteMultipartUpload`, `GetObject` and `HeadObject` answer the algorithm, the key ID as the request named it,
  and `bucket-key-enabled: true` if it was set; the context is only answered by the requests that store an object with
  it, like Amazon S3 does. A key ID is only answered if the request named one: LocalS3 doesn't make up the ARN of an
  AWS managed key.
+ The parts of a multipart upload get the encryption of the upload. A copy gets the encryption that the `CopyObject`
  request names, not the one of its source, and an append keeps the one of the object that it extends.
+ An object or an upload that is stored without the headers, and without an SSE-C key, gets the default encryption of
  its bucket, the `ApplyServerSideEncryptionByDefault` of the first rule of `PutBucketEncryption`; a
  `KMSMasterKeyID` and `BucketKeyEnabled` only count with a KMS algorithm. The configuration isn't validated when it is
  stored, and one that can't be read, or names an unknown algorithm, applies no encryption. A bucket without a
  configuration applies none either: LocalS3 doesn't answer `AES256` for every object like Amazon S3 does since
  January 2023.

## Server-side encryption with customer-provided keys (SSE-C)

`PutObject`, `CopyObject`, `CreateMultipartUpload`, `UploadPart`, `UploadPartCopy`, `GetObject`, `HeadObject` and
`GetObjectAttributes` accept the `x-amz-server-side-encryption-customer-algorithm`, `-customer-key` and
`-customer-key-MD5` headers, and the `x-amz-copy-source-server-side-encryption-customer-*` ones for the source of a
copy. **Nothing is encrypted**: LocalS3 stores the MD5 of the key, never the key, so that client code that uses SSE-C
runs as it does against Amazon S3. The boundary above holds here too: what LocalS3 checks is that the key you read an
object with is the key you wrote it with, not that the object was ever protected by it.

+ The headers are validated like Amazon S3 validates them: all three, `AES256` (`400 InvalidEncryptionAlgorithmError`
  otherwise), a base64 256-bit key, and its MD5 (`400 InvalidArgument` otherwise).
+ The responses that store or serve such an object echo the algorithm and the MD5 of the key.
+ Reading an object stored with a key, or uploading a part to an upload created with one, without the key answers
  `400 InvalidRequest`, and with another key `403 AccessDenied`. Sending a key for an object stored without one answers
  `400 InvalidRequest`.

Unlike Amazon S3, LocalS3 doesn't require HTTPS for SSE-C requests.

## The KMS endpoint

LocalS3 answers the KMS actions `GenerateDataKey`, `GenerateDataKeyWithoutPlaintext`, `Encrypt`, `Decrypt`,
`DescribeKey` and `GenerateRandom` on its own port, for the clients that encrypt objects themselves and call KMS to
wrap the data key, e.g. the Amazon S3 Encryption Client, and for the code that resolves a key with `DescribeKey`
before it sends `x-amz-server-side-encryption: aws:kms`. It is separate from the server-side encryption headers above,
which never reach it.

**Nothing is kept secret.** A ciphertext blob is the plaintext itself in a framed, base64 encoded envelope that anyone
can unpack, and no key material exists. What LocalS3 does give is a faithful round trip, so a client that wraps a data
key, stores the blob and unwraps it later reads its object back. A blob written by LocalS3 protects nothing; don't put
one where a real one belongs.

The Amazon S3 Encryption Client runs over this endpoint end to end — `GenerateDataKey`, the client encrypting the
object, `PutObject`, `GetObject`, `Decrypt` — and `S3EncryptionClientIntegrationTest` keeps it that way. Such an object
really is encrypted, by the client, but under a key that LocalS3 hands out and wraps in the clear.

+ The requests are AWS JSON 1.1, like KMS: `POST /` with `X-Amz-Target: TrentService.<action>`, signed for the `kms`
  service, with the credentials of LocalS3 or with temporary credentials of its STS endpoint. The errors are the JSON
  errors of KMS, e.g. `ValidationException`, not an `<Error>` document of S3.
+ **No keys are stored**, so every key ID, alias or ARN is valid, and `DescribeKey` describes it as an enabled
  symmetric key. A key ID that isn't an ARN is answered as `arn:aws:kms:us-east-1:000000000000:key/<id>`, an alias as
  `.../alias/<name>`. No key is created, deleted, rotated or disabled.
+ A blob is **bound to its key ID and encryption context**: a `Decrypt` with another context answers
  `InvalidCiphertextException` and one with another `KeyId` answers `IncorrectKeyException`, like KMS does, so a client
  that mixes them up is told rather than handed a plaintext. A blob that LocalS3 didn't write answers
  `InvalidCiphertextException` too. `KeyId` is optional for `Decrypt`, as it is for a symmetric key of KMS.
+ `GenerateDataKey` returns a **fresh random plaintext** of `KeySpec` (`AES_256` by default, or `AES_128`) or of
  `NumberOfBytes`, so no two objects share a data key; naming both answers `ValidationException`. `Encrypt` takes up to
  4096 bytes, as KMS does. The only `EncryptionAlgorithm` is `SYMMETRIC_DEFAULT`: there are no asymmetric keys.
+ Because nothing is stored, a blob **survives a restart** and is read by any LocalS3, in memory and persistence mode
  alike, whatever its credentials.

## The S3 Tables API

LocalS3 answers the [Amazon S3 Tables](data-tools.md#amazon-s3-tables) API on its own port: table buckets, the
namespaces and the tables in them, and the metadata locations that make a commit. Every table bucket is also an
[Iceberg REST catalog](data-tools.md#the-same-table-bucket-as-an-iceberg-rest-catalog), and the two are views of one
catalog rather than two copies of it.

+ A request of this API is told from an Amazon S3 one by the **`s3tables` service in its credential scope**, because the
  two share their paths, and the signature is then verified for that service. A client that signs nothing reaches the API
  under `/s3tables`. See [reaching the API](data-tools.md#reaching-the-api).
+ The tables of a table bucket live in an **ordinary bucket of the same service**, `<table-bucket>--table-s3`, so an
  engine's `S3FileIO` writes its data files there and a test can read them with an `S3Client`.
+ A commit is guarded by the **version token**: `UpdateTableMetadataLocation` with a token that is no longer current is
  answered `409 ConflictException`, and a commit made over the Iceberg REST endpoint of the same table bucket draws a new
  token, so the two protocols commit against one another safely.
+ **Encryption, storage class, resource policies, maintenance, metrics, replication and record expiration are stored and
  read back, and nothing happens.** Nothing is encrypted or tiered, no policy is enforced, and no compaction, replication
  or expiration job ever runs — `GetTableMaintenanceJobStatus` answers `Not_Yet_Run` for every job and
  `GetTableReplicationStatus` answers no destination. What that buys is the code path under test, which usually sets a
  configuration on the way to doing something else, running through instead of failing on an unimplemented operation.
+ Every ARN is answered with the region and the account of LocalS3, `us-east-1` and `000000000000`, and the account of an
  ARN a client sends is ignored rather than refused.
+ A namespace is one level and `ICEBERG` is the only table format, as they are in Amazon S3 Tables. The full list of what
  differs is in [data-tools.md](data-tools.md#limits-1).

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
| `OBJECT_CREATED` | `PutObject`, `CopyObject`, `CompleteMultipartUpload`, `RenameObject` | `s3:ObjectCreated:Put`, `:Copy`, `:CompleteMultipartUpload` | `key()`, `size()`, `etag()`, `versionId()` |
| `OBJECT_DELETED` | `DeleteObject`, `DeleteObjects`, `RenameObject` (the old key) | `s3:ObjectRemoved:Delete`, `:DeleteMarkerCreated` | `key()`, `versionId()`, `deleteMarker()`; `size()` and `etag()` are `null` |
| `OBJECT_DELETED` | `LifecycleExpiration` | `s3:LifecycleExpiration:Delete`, `:DeleteMarkerCreated` | as above, for what an [applied lifecycle rule](#lifecycle-configuration) expired |
| `OBJECT_TAGGING_PUT`, `OBJECT_TAGGING_DELETED` | `PutObjectTagging`, `DeleteObjectTagging` | `s3:ObjectTagging:Put`, `:Delete` | `key()`, `versionId()`, `size()`, `etag()` of the version |
| `OBJECT_ACL_PUT` | `PutObjectAcl` | `s3:ObjectAcl:Put` | `key()`, `versionId()`, `size()`, `etag()` of the version |
| `MULTIPART_UPLOAD_ABORTED` | `AbortMultipartUpload`, `LifecycleExpiration` | `null` | `key()`, `uploadId()`; fired only if the upload existed |

Only a change of a bucket leaves `key()` `null`, which tells the two apart. `versionId()` is `null` if the bucket has
never been versioned.

By default, the listeners run **synchronously on the thread that made the change**, so the change of a request is
delivered before the S3 response is sent. With an executor, `events(events -> events.executor(...))`, changes are
delivered asynchronously, so that slow listeners don't hold up request handling; a single-threaded executor keeps the
changes in order.

LocalS3 does not shut the executor down; that stays with the code that created it. Either way, an exception
thrown by a listener is logged and never fails the S3 request, and a change that the executor rejects is
dropped with a log entry.
