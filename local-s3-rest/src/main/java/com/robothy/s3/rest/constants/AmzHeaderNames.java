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

  /**
   * The minimum size of the objects that the transitions of a lifecycle configuration apply to by default.
   */
  public static final String X_AMZ_TRANSITION_DEFAULT_MINIMUM_OBJECT_SIZE = "x-amz-transition-default-minimum-object-size";

  public static final String X_AMZ_DELETE_MARKER = "x-amz-delete-marker";

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
}
