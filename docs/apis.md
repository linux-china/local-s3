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
+ CreateSession
+ CompleteMultipartUpload
+ DeleteBucket
+ DeleteBucketAnalyticsConfiguration
+ DeleteBucketCors
+ DeleteBucketEncryption
+ DeleteBucketIntelligentTieringConfiguration
+ DeleteBucketInventoryConfiguration
+ DeleteBucketLifecycle
+ DeleteBucketMetricsConfiguration
+ DeleteBucketOwnershipControls
+ DeleteBucketPolicy
+ DeleteBucketReplication
+ DeleteBucketTagging
+ DeleteBucketWebsite
+ DeleteObject
+ DeleteObjects
+ DeleteObjectTagging
+ GetObject
+ GetObjectTagging
+ GetObjectAcl
+ PutObjectAcl
+ GetObjectAttributes
+ GetObjectLegalHold
+ GetObjectLockConfiguration
+ GetObjectRetention
+ GetBucketAccelerateConfiguration
+ GetBucketAcl
+ GetBucketAnalyticsConfiguration
+ GetBucketCors
+ GetBucketEncryption
+ GetBucketIntelligentTieringConfiguration
+ GetBucketInventoryConfiguration
+ GetBucketLifecycleConfiguration
+ GetBucketLogging
+ GetBucketMetricsConfiguration
+ GetBucketNotificationConfiguration
+ GetBucketOwnershipControls
+ GetBucketPolicy
+ GetBucketPolicyStatus
+ GetBucketReplication
+ GetBucketRequestPayment
+ GetBucketVersioning
+ GetBucketWebsite
+ GetBucketTagging
+ GetBucketLocation
+ HeadBucket
+ HeadObject
+ ListBucketAnalyticsConfigurations
+ ListBucketIntelligentTieringConfigurations
+ ListBucketInventoryConfigurations
+ ListBucketMetricsConfigurations
+ ListBuckets
+ ListObjects
+ ListObjectsV2
+ ListObjectVersions
+ ListMultipartUploads
+ ListParts
+ PutBucketAccelerateConfiguration
+ PutBucketAcl
+ PutBucketAnalyticsConfiguration
+ PutBucketCors
+ PutBucketEncryption
+ PutBucketIntelligentTieringConfiguration
+ PutBucketInventoryConfiguration
+ PutBucketLifecycleConfiguration
+ PutBucketLogging
+ PutBucketMetricsConfiguration
+ PutBucketNotificationConfiguration
+ PutBucketOwnershipControls
+ PutBucketPolicy
+ PutBucketReplication
+ PutBucketRequestPayment
+ PutBucketVersioning
+ PutBucketWebsite
+ PutBucketTagging
+ PostObject
+ PutObject
+ PutObjectTagging
+ PutObjectLegalHold
+ PutObjectLockConfiguration
+ PutObjectRetention
+ RenameObject
+ RestoreObject
+ UploadPart
+ UploadPartCopy
+ PutPublicAccessBlock
+ GetPublicAccessBlock
+ DeletePublicAccessBlock
+ OPTIONS object (CORS preflight requests, answered without authentication)

The lifecycle configuration of a bucket is **stored, and applied only when a test asks for it**, with
`POST /_admin/lifecycle` or `LocalS3#applyLifecycle(Instant)`, at a time of its choosing, e.g. 30 days from now. See
[semantics.md](semantics.md#lifecycle-configuration). The deprecated `PutBucketLifecycle` and `GetBucketLifecycle`
send the same requests, and are answered the same way.

The notification configuration of a bucket is **stored and returned as it was put, but no event is sent** to the
SNS topics, SQS queues, Lambda functions or EventBridge it names, and their ARNs aren't checked, so that code that
configures notifications when it starts, e.g. Terraform, CDK or an application, works against LocalS3. A bucket that
was never configured answers an empty `NotificationConfiguration`. To hear of changes, register a listener; see
[change events](semantics.md#change-events). The deprecated `PutBucketNotification` and `GetBucketNotification` send
the same requests, and are answered the same way.

Transfer acceleration, access logging, requester pays and ownership controls are **stored and returned as they were
put, but never applied**: nothing is accelerated, logged or billed to the requester, and ACLs keep working whatever the
object ownership. They exist so that clients that read or write them, e.g.
Terraform refreshing an `aws_s3_bucket`, or CDK and SDK tool chains setting the ownership controls, work against
LocalS3. A bucket that was never configured answers like a new bucket of Amazon S3 does:

| Operation                          | Never configured                                         | After the delete                     |
|------------------------------------|----------------------------------------------------------|--------------------------------------|
| `GetBucketAccelerateConfiguration` | an empty `AccelerateConfiguration`                       | –                                    |
| `GetBucketLogging`                 | an empty `BucketLoggingStatus`                           | –                                    |
| `GetBucketRequestPayment`          | `<Payer>BucketOwner</Payer>`                             | –                                    |
| `GetBucketOwnershipControls`       | `<ObjectOwnership>BucketOwnerEnforced</ObjectOwnership>` | `404 OwnershipControlsNotFoundError` |

The analytics, intelligent-tiering, inventory and metrics configurations of a bucket, of which it has several, each
named by the `id` parameter, are **stored and returned as they were put, but never applied** either: no storage class
analysis runs, no object moves between access tiers, no inventory report is written and no CloudWatch metric is
published. They exist so that Terraform's `aws_s3_bucket_metric`, `aws_s3_bucket_inventory`,
`aws_s3_bucket_analytics_configuration` and `aws_s3_bucket_intelligent_tiering_configuration`, or CDK stacks that
declare them, work against LocalS3. A bucket has none until one is put; getting or deleting an `id` it doesn't have
answers `404 NoSuchConfiguration`, and a `PUT` whose `id` isn't the `<Id>` of its document answers
`400 InvalidArgument`. The `List…` operations answer every configuration on one page, in the order of their IDs.

The website configuration of a bucket (`PutBucketWebsite`, `GetBucketWebsite`, `DeleteBucketWebsite`) **is applied**:
its index and error documents, redirects and routing rules decide how the bucket is served as a website on the port of
the S3 API, see [semantics.md](semantics.md#static-website-hosting). A bucket that was never configured, or whose
configuration was deleted, answers `GetBucketWebsite` with `404 NoSuchWebsiteConfiguration`.

Object Lock (`x-amz-bucket-object-lock-enabled`, the `x-amz-object-lock-*` headers of `PutObject`, `CopyObject` and
`CreateMultipartUpload`, retention and legal holds) protects object versions from being deleted, see
[semantics.md](semantics.md#object-lock).

`RestoreObject` of a `GLACIER` or `DEEP_ARCHIVE` object completes at once, and `HeadObject` answers the restored copy
with `x-amz-restore`, so that code that restores cold data before reading it works; see
[semantics.md](semantics.md#storage-classes-and-restores).

`RenameObject` and appends (`PutObject` with `x-amz-write-offset-bytes`), which Amazon S3 offers for S3 Express One Zone
directory buckets, work on the buckets whose versioning was never enabled, see
[semantics.md](semantics.md#appends-and-renames). A bucket named like a directory bucket, e.g.
`my-bucket--usw2-az1--x-s3`, is addressed by the AWS SDKs the S3 Express way, with the stateless session credentials
of `CreateSession`, see [semantics.md](semantics.md#s3-express-one-zone-directory-buckets).

Server-side encryption with customer-provided keys (SSE-C) is accepted and echoed, but nothing is encrypted, see
[semantics.md](semantics.md#server-side-encryption-with-customer-provided-keys-sse-c).
The `x-amz-server-side-encryption*` headers of SSE-S3, SSE-KMS and DSSE-KMS are stored with the object and answered
like Amazon S3 answers them, but nothing is encrypted either, see
[semantics.md](semantics.md#server-side-encryption-with-s3-managed-and-kms-keys-sse-s3-sse-kms).

`PostObject` is the upload of a file by an HTML form that a browser posts to a bucket, with its policy document and
signature; see [semantics.md](semantics.md#browser-form-uploads-post-object).

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

**Tagging Operations** (of a vector bucket or an index, addressed by its ARN; `CreateVectorBucket` and `CreateIndex` accept `tags` too):
+ TagResource
+ UntagResource
+ ListTagsForResource

## Supported Amazon S3 Tables APIs

The [Amazon S3 Tables](https://docs.aws.amazon.com/AmazonS3/latest/userguide/s3-tables.html) API, which an
`S3TablesClient` of the AWS SDK and the `s3-tables-catalog` library of Iceberg speak. Every table bucket is also served
as an [Iceberg REST catalog](data-tools.md#amazon-s3-tables) of its own, so an engine reaches the same tables by giving
a `RESTCatalog` the ARN of the table bucket as its warehouse.

A request of this API shares its paths with Amazon S3 — `PUT /buckets` is `CreateTableBucket` here and `CreateBucket` of
a bucket named `buckets` there — so it is told apart by the `s3tables` service in the credential scope of its signature.
A client that signs nothing reaches the API under `/s3tables` instead; see
[the S3 Tables API](data-tools.md#reaching-the-api).

**Table Bucket Operations:**
+ CreateTableBucket
+ GetTableBucket
+ ListTableBuckets
+ DeleteTableBucket

**Namespace Operations:**
+ CreateNamespace
+ GetNamespace
+ ListNamespaces
+ DeleteNamespace

**Table Operations:**
+ CreateTable
+ GetTable
+ ListTables
+ DeleteTable
+ RenameTable
+ GetTableMetadataLocation
+ UpdateTableMetadataLocation

**Tagging Operations** (of a table bucket or a table, addressed by its ARN; `CreateTableBucket` and `CreateTable` accept `tags` too):
+ TagResource
+ UntagResource
+ ListTagsForResource

**Encryption and Storage Class Operations** (stored and read back; nothing is encrypted or tiered, see [semantics.md](semantics.md#the-s3-tables-api)):
+ PutTableBucketEncryption
+ GetTableBucketEncryption
+ DeleteTableBucketEncryption
+ GetTableEncryption
+ PutTableBucketStorageClass
+ GetTableBucketStorageClass
+ GetTableStorageClass

**Resource Policy Operations** (stored and read back; nothing is enforced):
+ PutTableBucketPolicy
+ GetTableBucketPolicy
+ DeleteTableBucketPolicy
+ PutTablePolicy
+ GetTablePolicy
+ DeleteTablePolicy

**Maintenance and Metrics Operations** (stored and read back; no job ever runs):
+ PutTableBucketMaintenanceConfiguration
+ GetTableBucketMaintenanceConfiguration
+ PutTableMaintenanceConfiguration
+ GetTableMaintenanceConfiguration
+ GetTableMaintenanceJobStatus
+ PutTableBucketMetricsConfiguration
+ GetTableBucketMetricsConfiguration
+ DeleteTableBucketMetricsConfiguration

**Record Expiration Operations** (stored and read back; no record ever expires):
+ PutTableRecordExpirationConfiguration
+ GetTableRecordExpirationConfiguration
+ GetTableRecordExpirationJobStatus

**Replication Operations** (stored and read back; nothing is replicated):
+ PutTableBucketReplication
+ GetTableBucketReplication
+ DeleteTableBucketReplication
+ PutTableReplication
+ GetTableReplication
+ DeleteTableReplication
+ GetTableReplicationStatus

## Supported AWS STS APIs

A stateless STS endpoint on the same port issues temporary credentials of LocalS3, like the one of MinIO; see
[embedding.md](embedding.md#temporary-credentials-sts). STS requests are `POST /` with a form-urlencoded body.

+ AssumeRole
+ GetSessionToken
+ GetCallerIdentity

## Supported AWS KMS APIs

A stateless KMS endpoint on the same port wraps and unwraps data keys, so that a client which calls KMS before it
talks to S3, e.g. the Amazon S3 Encryption Client, runs against LocalS3; see
[embedding.md](embedding.md#envelope-encryption-kms). KMS requests are `POST /` with an `X-Amz-Target:
TrentService.<action>` header and a JSON body. **Nothing is really encrypted**, see
[semantics.md](semantics.md#the-kms-endpoint).

+ GenerateDataKey
+ GenerateDataKeyWithoutPlaintext
+ Encrypt
+ Decrypt
+ DescribeKey
+ GenerateRandom

## Known unimplemented Amazon S3 APIs

LocalS3 is a mock for testing, so it implements the operations that application code exercises and leaves
the ones that configure a real bucket's billing, hosting and reporting alone. The operations below are
routed and answer `501 NotImplemented` with an `<Error>` document naming the operation, so a client fails
with a clear error instead of appearing to succeed. If your tests need one of them, please
[open an issue](https://github.com/Robothy/local-s3/issues/new).

**Object retrieval and transformation**
+ GetObjectTorrent
+ SelectObjectContent
+ WriteGetObjectResponse

## Not routed at all

Every other operation of the S3 API that isn't listed on this page has no route, and answers `501 NotImplemented`
from the fallback handler, e.g. `LocalS3 does not implement PATCH /<bucket>.`
