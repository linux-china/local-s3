package com.robothy.s3.core.event;

/**
 * What an {@linkplain S3Change} changed.
 */
public enum S3ChangeType {

  /**
   * A bucket was created.
   */
  BUCKET_CREATED,

  /**
   * A bucket was deleted.
   */
  BUCKET_DELETED,

  /**
   * An object was stored, by {@code PutObject}, {@code CopyObject} or {@code CompleteMultipartUpload}.
   */
  OBJECT_CREATED,

  /**
   * An object version was deleted, or a delete marker was created.
   */
  OBJECT_DELETED,

  /**
   * The tagging of an object version was replaced.
   */
  OBJECT_TAGGING_PUT,

  /**
   * The tagging of an object version was deleted.
   */
  OBJECT_TAGGING_DELETED,

  /**
   * The ACL of an object version was replaced.
   */
  OBJECT_ACL_PUT,

  /**
   * A multipart upload was aborted, and the parts it had received were deleted.
   */
  MULTIPART_UPLOAD_ABORTED,

}
