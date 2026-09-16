# Supported APIs

LocalS3 routes every request to one of three kinds of handler: an operation it implements, an operation it knows and
answers `501 NotImplemented`, or the fallback handler for everything else. The lists below are checked against the
routes of the router by `SupportedApiDocumentationTest`, so they can't drift from what the service answers.

For how the supported operations behave where Amazon S3 leaves room for interpretation, e.g. conditional requests and
versioning, see [semantics.md](semantics.md).

## Supported Amazon S3 APIs

+ AbortMultipartUpload
+ CopyObject
+ CreateBucket
+ CreateMultipartUpload
+ CompleteMultipartUpload
+ DeleteBucket
+ DeleteBucketCors
+ DeleteBucketEncryption
+ DeleteBucketPolicy
+ DeleteBucketReplication
+ DeleteBucketTagging
+ DeleteObject
+ DeleteObjects
+ DeleteObjectTagging
+ GetObject
+ GetObjectTagging
+ GetObjectAcl
+ PutObjectAcl
+ GetObjectAttributes
+ GetBucketAcl
+ GetBucketCors
+ GetBucketEncryption
+ GetBucketPolicy
+ GetBucketPolicyStatus
+ GetBucketReplication
+ GetBucketVersioning
+ GetBucketTagging
+ GetBucketLocation
+ HeadBucket
+ HeadObject
+ ListBuckets
+ ListObjects
+ ListObjectsV2
+ ListObjectVersions
+ ListMultipartUploads
+ ListParts
+ PutBucketAcl
+ PutBucketCors
+ PutBucketEncryption
+ PutBucketPolicy
+ PutBucketReplication
+ PutBucketVersioning
+ PutBucketTagging
+ PutObject
+ PutObjectTagging
+ UploadPart
+ UploadPartCopy
+ PutPublicAccessBlock
+ GetPublicAccessBlock
+ DeletePublicAccessBlock
+ OPTIONS object (CORS preflight requests, answered without authentication)

`ListBuckets` is paginated with `max-buckets`, `continuation-token`, `prefix` and `bucket-region`, so
`listBucketsPaginator` works.

Besides the S3 API, a service answers a health check and a few admin endpoints; see
[deployment.md](deployment.md#health-check).

## Supported Amazon S3 Vectors APIs

**Vector Bucket Operations:**
+ CreateVectorBucket
+ GetVectorBucket
+ ListVectorBuckets
+ DeleteVectorBucket

**Vector Index Operations:**
+ CreateIndex
+ GetIndex
+ ListIndexes
+ DeleteIndex

**Vector Data Operations:**
+ PutVectors
+ QueryVectors
+ GetVectors
+ ListVectors
+ DeleteVectors

**Vector Bucket Policy Operations:**
+ PutVectorBucketPolicy
+ GetVectorBucketPolicy
+ DeleteVectorBucketPolicy

## Known unimplemented Amazon S3 APIs

LocalS3 is a mock for testing, so it implements the operations that application code exercises and leaves
the ones that configure a real bucket's lifecycle, billing and reporting alone. The operations below are
routed and answer `501 NotImplemented` with an `<Error>` document naming the operation, so a client fails
with a clear error instead of appearing to succeed. If your tests need one of them, please
[open an issue](https://github.com/Robothy/local-s3/issues/new).

**Lifecycle**
+ DeleteBucketLifecycle
+ GetBucketLifecycleConfiguration
+ PutBucketLifecycleConfiguration

(The deprecated `GetBucketLifecycle` and `PutBucketLifecycle` send the same requests, and get the same answer.)

**Event notifications**
+ GetBucketNotificationConfiguration
+ PutBucketNotificationConfiguration

(The deprecated `GetBucketNotification` and `PutBucketNotification` send the same requests, and get the same
answer. LocalS3 has its own listener API instead; see [change events](semantics.md#change-events).)

**Object Lock and retention**
+ GetObjectLegalHold
+ GetObjectLockConfiguration
+ GetObjectRetention
+ PutObjectLegalHold
+ PutObjectLockConfiguration
+ PutObjectRetention

**Object retrieval and transformation**
+ GetObjectTorrent
+ RestoreObject
+ SelectObjectContent
+ WriteGetObjectResponse

**Static website hosting**
+ DeleteBucketWebsite
+ GetBucketWebsite
+ PutBucketWebsite

**Access logging**
+ GetBucketLogging
+ PutBucketLogging

**Requester pays**
+ GetBucketRequestPayment
+ PutBucketRequestPayment

**Transfer acceleration**
+ GetBucketAccelerateConfiguration
+ PutBucketAccelerateConfiguration

**Ownership controls**
+ DeleteBucketOwnershipControls
+ GetBucketOwnershipControls
+ PutBucketOwnershipControls

**Analytics, inventory and metrics**
+ DeleteBucketAnalyticsConfiguration
+ DeleteBucketInventoryConfiguration
+ DeleteBucketMetricsConfiguration
+ GetBucketAnalyticsConfiguration
+ GetBucketInventoryConfiguration
+ GetBucketMetricsConfiguration
+ ListBucketAnalyticsConfigurations
+ ListBucketInventoryConfigurations
+ ListBucketMetricsConfigurations
+ PutBucketAnalyticsConfiguration
+ PutBucketInventoryConfiguration
+ PutBucketMetricsConfiguration

**Intelligent tiering**
+ DeleteBucketIntelligentTieringConfiguration
+ GetBucketIntelligentTieringConfiguration
+ ListBucketIntelligentTieringConfigurations
+ PutBucketIntelligentTieringConfiguration

## Not routed at all

`POST Object`, the browser form upload (`multipart/form-data` to the bucket, with its base64 policy
document and signature), has no route, so it answers `501 NotImplemented` from the fallback handler:
`LocalS3 does not implement POST /<bucket>.` Testing a browser upload flow against LocalS3 therefore needs
presigned `PUT` instead.

Every other operation of the S3 API that isn't listed on this page is also unrouted and answers the same way.
