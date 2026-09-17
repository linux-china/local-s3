package com.robothy.s3.core.model.answers;

/**
 * An action that a lifecycle rule took when LocalS3 was asked to apply the lifecycle configurations of its buckets.
 *
 * @param bucket the bucket name.
 * @param ruleId the ID of the rule that took the action; {@code null} if the rule has none.
 * @param type what the action did.
 * @param key the object key.
 * @param versionId the version that was expired or removed, or the delete marker that was created; {@code null} for an
 *     object of a bucket whose versioning was never enabled, and for an upload.
 * @param uploadId the multipart upload that was aborted; {@code null} for the other actions.
 */
public record LifecycleActionAns(String bucket, String ruleId, Type type, String key, String versionId,
                                 String uploadId) {

  /**
   * What a lifecycle action did.
   */
  public enum Type {

    /**
     * The current version of an object expired and was deleted, in a bucket whose versioning was never enabled.
     */
    OBJECT_EXPIRED,

    /**
     * The current version of an object expired, and a delete marker was created for it, in a versioned bucket.
     */
    DELETE_MARKER_CREATED,

    /**
     * A noncurrent version, or a noncurrent delete marker, expired and was deleted permanently.
     */
    NONCURRENT_VERSION_EXPIRED,

    /**
     * A delete marker that was the only version left of its object was removed.
     */
    EXPIRED_DELETE_MARKER_REMOVED,

    /**
     * An incomplete multipart upload was aborted.
     */
    MULTIPART_UPLOAD_ABORTED

  }

}
