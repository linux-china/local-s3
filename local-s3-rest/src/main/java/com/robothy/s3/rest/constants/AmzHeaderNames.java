package com.robothy.s3.rest.constants;

/**
 * Amazon S3 headers.
 */
public class AmzHeaderNames {

  public static final String X_AMZ_REQUEST_ID = "x-amz-request-id";

  public static final String X_AMZ_VERSION_ID = "x-amz-version-id";

  public static final String X_AMZ_CONTENT_SHA256 = "x-amz-content-sha256";

  public static final String X_AMZ_DECODED_CONTENT_LENGTH = "x-amz-decoded-content-length";

  public static final String X_AMZ_BUCKET_REGION = "x-amz-bucket-region";

  public static final String X_AMZ_BUCKET_ARN = "x-amz-bucket-arn";

  /**
   * The minimum size of the objects that the transitions of a lifecycle configuration apply to by default.
   */
  public static final String X_AMZ_TRANSITION_DEFAULT_MINIMUM_OBJECT_SIZE = "x-amz-transition-default-minimum-object-size";

  public static final String X_AMZ_DELETE_MARKER = "x-amz-delete-marker";

  public static final String X_AMZ_STORAGE_CLASS = "x-amz-storage-class";

  public static final String X_AMZ_TAGGING = "x-amz-tagging";

  public static final String X_AMZ_TAGGING_COUNT = "x-amz-tagging-count";

  public static final String X_AMZ_OBJECT_ATTRIBUTES = "x-amz-object-attributes";

  /**
   * The size of a page of the {@code ObjectParts} attribute that {@code GetObjectAttributes} answers.
   */
  public static final String X_AMZ_MAX_PARTS = "x-amz-max-parts";

  /**
   * The part number that a page of the {@code ObjectParts} attribute that {@code GetObjectAttributes}
   * answers starts after.
   */
  public static final String X_AMZ_PART_NUMBER_MARKER = "x-amz-part-number-marker";

  /**
   * The number of parts of an object uploaded in parts, which a {@code GetObject} or {@code HeadObject} of a part
   * answers.
   */
  public static final String X_AMZ_MP_PARTS_COUNT = "x-amz-mp-parts-count";

  public static final String X_AMZN_ERRORTYPE = "x-amzn-errortype";

  /**
   * Specifies the source object for the copy operation.
   */
  public static final String X_AMZ_COPY_SOURCE = "x-amz-copy-source";

  /**
   * The range of bytes of the source object that UploadPartCopy copies into the part, e.g. {@code bytes=0-9}.
   */
  public static final String X_AMZ_COPY_SOURCE_RANGE = "x-amz-copy-source-range";

  /**
   * Version of the copied object in the destination bucket.
   */
  public static final String X_AMZ_COPY_SOURCE_VERSION_ID = "x-amz-copy-source-version-id";

  /**
   * The conditions of the source object of CopyObject and UploadPartCopy, which are evaluated like the
   * {@code If-Match}, {@code If-None-Match}, {@code If-Modified-Since} and {@code If-Unmodified-Since} of a read.
   */
  public static final String X_AMZ_COPY_SOURCE_IF_MATCH = "x-amz-copy-source-if-match";

  public static final String X_AMZ_COPY_SOURCE_IF_NONE_MATCH = "x-amz-copy-source-if-none-match";

  public static final String X_AMZ_COPY_SOURCE_IF_MODIFIED_SINCE = "x-amz-copy-source-if-modified-since";

  public static final String X_AMZ_COPY_SOURCE_IF_UNMODIFIED_SINCE = "x-amz-copy-source-if-unmodified-since";

  /**
   * Deletes the object of DeleteObject only if it was last modified at this HTTP date.
   */
  public static final String X_AMZ_IF_MATCH_LAST_MODIFIED_TIME = "x-amz-if-match-last-modified-time";

  /**
   * Deletes the object of DeleteObject only if it has this many bytes.
   */
  public static final String X_AMZ_IF_MATCH_SIZE = "x-amz-if-match-size";

  /**
   * Specifies whether the metadata is copied from the source object or replaced with metadata
   * provided in the request. Valid values: COPY, REPLACE. Default: COPY.
   */
  public static final String X_AMZ_METADATA_DIRECTIVE = "x-amz-metadata-directive";

  /**
   * Specifies whether the tagging is copied from the source object or replaced with the tagging
   * provided in the request. Valid values: COPY, REPLACE. Default: COPY.
   */
  public static final String X_AMZ_TAGGING_DIRECTIVE = "x-amz-tagging-directive";

  /**
   * The prefix for <a href="https://docs.aws.amazon.com/AmazonS3/latest/userguide/UsingMetadata.html#UserMetadata">user-defined object metadata</a> keys.
   *
   */
  public static final String X_AMZ_META_PREFIX = "x-amz-meta-";

  /**
   * The names of the trailing headers of an {@code aws-chunked} body, e.g. {@code x-amz-checksum-crc32}.
   */
  public static final String X_AMZ_TRAILER = "x-amz-trailer";

  /**
   * The algorithm of the checksum that an AWS SDK sends with the content, in a header or a trailing header.
   */
  public static final String X_AMZ_SDK_CHECKSUM_ALGORITHM = "x-amz-sdk-checksum-algorithm";

  /**
   * The algorithm of the checksum of the object that CreateMultipartUpload and CopyObject store.
   */
  public static final String X_AMZ_CHECKSUM_ALGORITHM = "x-amz-checksum-algorithm";

  /**
   * The type of a checksum, {@code FULL_OBJECT} or {@code COMPOSITE}.
   */
  public static final String X_AMZ_CHECKSUM_TYPE = "x-amz-checksum-type";

  /**
   * {@code ENABLED} asks GetObject and HeadObject for the checksum of the object.
   */
  public static final String X_AMZ_CHECKSUM_MODE = "x-amz-checksum-mode";

  /**
   * {@code true} creates a bucket with Object Lock enabled.
   */
  public static final String X_AMZ_BUCKET_OBJECT_LOCK_ENABLED = "x-amz-bucket-object-lock-enabled";

  /**
   * The retention mode of an object, {@code GOVERNANCE} or {@code COMPLIANCE}.
   */
  public static final String X_AMZ_OBJECT_LOCK_MODE = "x-amz-object-lock-mode";

  /**
   * The ISO 8601 date and time that an object is retained until.
   */
  public static final String X_AMZ_OBJECT_LOCK_RETAIN_UNTIL_DATE = "x-amz-object-lock-retain-until-date";

  /**
   * The legal hold of an object, {@code ON} or {@code OFF}.
   */
  public static final String X_AMZ_OBJECT_LOCK_LEGAL_HOLD = "x-amz-object-lock-legal-hold";

  /**
   * {@code true} bypasses the governance mode retention of the versions that a request deletes or changes.
   */
  public static final String X_AMZ_BYPASS_GOVERNANCE_RETENTION = "x-amz-bypass-governance-retention";

  /**
   * The server-side encryption algorithm of an object: {@code AES256} (SSE-S3), {@code aws:kms} (SSE-KMS) or
   * {@code aws:kms:dsse} (DSSE-KMS).
   */
  public static final String X_AMZ_SERVER_SIDE_ENCRYPTION = "x-amz-server-side-encryption";

  /**
   * The ID of the KMS key of an SSE-KMS or DSSE-KMS encrypted object.
   */
  public static final String X_AMZ_SSE_KMS_KEY_ID = "x-amz-server-side-encryption-aws-kms-key-id";

  /**
   * The base64 encoded JSON encryption context of an SSE-KMS or DSSE-KMS encrypted object.
   */
  public static final String X_AMZ_SSE_CONTEXT = "x-amz-server-side-encryption-context";

  /**
   * Whether an SSE-KMS encrypted object uses an S3 Bucket Key.
   */
  public static final String X_AMZ_SSE_BUCKET_KEY_ENABLED = "x-amz-server-side-encryption-bucket-key-enabled";

  /**
   * The algorithm of a customer-provided encryption key (SSE-C), which is always {@code AES256}.
   */
  public static final String X_AMZ_SSE_CUSTOMER_ALGORITHM = "x-amz-server-side-encryption-customer-algorithm";

  /**
   * The base64 encoded customer-provided encryption key.
   */
  public static final String X_AMZ_SSE_CUSTOMER_KEY = "x-amz-server-side-encryption-customer-key";

  /**
   * The base64 encoded MD5 digest of the customer-provided encryption key.
   */
  public static final String X_AMZ_SSE_CUSTOMER_KEY_MD5 = "x-amz-server-side-encryption-customer-key-MD5";

  /**
   * The prefix of the headers that provide the customer key of the source object of a copy, e.g.
   * {@code x-amz-copy-source-server-side-encryption-customer-key}.
   */
  public static final String X_AMZ_COPY_SOURCE_PREFIX = "x-amz-copy-source-";

  /**
   * The offset that {@code PutObject} appends its content at, which must be the size of the object.
   */
  public static final String X_AMZ_WRITE_OFFSET_BYTES = "x-amz-write-offset-bytes";

  /**
   * The size of the object that an append stored.
   */
  public static final String X_AMZ_OBJECT_SIZE = "x-amz-object-size";

  /**
   * The object that {@code RenameObject} renames, as {@code /bucket/key}.
   */
  public static final String X_AMZ_RENAME_SOURCE = "x-amz-rename-source";

  /**
   * The conditions of the object that {@code RenameObject} renames, e.g. {@code x-amz-rename-source-if-match}.
   */
  public static final String X_AMZ_RENAME_SOURCE_IF_MATCH = "x-amz-rename-source-if-match";

  public static final String X_AMZ_RENAME_SOURCE_IF_NONE_MATCH = "x-amz-rename-source-if-none-match";

  public static final String X_AMZ_RENAME_SOURCE_IF_MODIFIED_SINCE = "x-amz-rename-source-if-modified-since";

  public static final String X_AMZ_RENAME_SOURCE_IF_UNMODIFIED_SINCE = "x-amz-rename-source-if-unmodified-since";
}
