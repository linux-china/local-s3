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
}
