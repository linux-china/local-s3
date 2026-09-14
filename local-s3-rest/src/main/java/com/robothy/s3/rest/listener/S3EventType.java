package com.robothy.s3.rest.listener;

/**
 * The types of {@linkplain S3Event}s. Each is named like the {@linkplain com.robothy.s3.core.event.S3ChangeType} that
 * it is delivered for.
 */
public enum S3EventType {
    BUCKET_CREATED,
    BUCKET_DELETED,
    OBJECT_CREATED,
    OBJECT_DELETED,
    /**
     * The tagging of an object version was replaced, by {@code PutObjectTagging}.
     */
    OBJECT_TAGGING_PUT,
    /**
     * The tagging of an object version was deleted, by {@code DeleteObjectTagging}.
     */
    OBJECT_TAGGING_DELETED,
    /**
     * The ACL of an object version was replaced, by {@code PutObjectAcl}.
     */
    OBJECT_ACL_PUT,
    /**
     * A multipart upload was aborted, by {@code AbortMultipartUpload}.
     */
    MULTIPART_UPLOAD_ABORTED,
}
