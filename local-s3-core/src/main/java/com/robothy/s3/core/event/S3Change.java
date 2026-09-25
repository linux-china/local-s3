package com.robothy.s3.core.event;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import tools.jackson.databind.node.ObjectNode;

/**
 * A change of a bucket or of an object that a service committed.
 *
 * <p>Two changes are equal when they change the same thing in the same way: {@code eventTime} and {@code sequencer}
 * tell apart when the change was made, and are not compared, so that a test may compare a received change to an
 * expected one.
 *
 * @param type what changed.
 * @param operation the S3 operation that made the change, e.g. {@code PutObject}. An operation that is made of others
 *     names the changes of its parts, e.g. the objects that {@code DeleteObjects} deletes are deleted by
 *     {@code DeleteObjects} rather than by {@code DeleteObject}.
 * @param bucketName the bucket that changed, or that the object belongs to.
 * @param bucketRegion the region of the bucket, for the changes of a bucket; {@code null} if it has none, and for the
 *     changes of an object.
 * @param key the object key; {@code null} for the changes of a bucket.
 * @param versionId the version ID of the created version, of the deleted version or created delete marker, or of the
 *     version whose tagging or ACL changed; {@code null} if the bucket has never been versioned, and for the changes of
 *     a bucket or an upload.
 * @param size the size in bytes of the created version, or of the version whose tagging or ACL changed; {@code null}
 *     for the other changes.
 * @param etag the unquoted entity tag of the created version, or of the version whose tagging or ACL changed;
 *     {@code null} for the other changes.
 * @param deleteMarker whether the deletion created a delete marker instead of removing a version.
 * @param uploadId the ID of the aborted multipart upload; {@code null} for the other changes.
 * @param eventTime when the change was made.
 * @param sequencer the order of the change among the changes of the service, as the {@code sequencer} of an Amazon S3
 *     event notification: a hexadecimal string of 16 digits, so that the later of two changes of a key has the greater
 *     sequencer, compared as strings.
 *
 * @see #toS3EventJson()
 */
public record S3Change(S3ChangeType type, String operation, String bucketName, String bucketRegion, String key,
                       String versionId, Long size, String etag, boolean deleteMarker, String uploadId,
                       Instant eventTime, String sequencer) {

  /**
   * The next sequencer, starting from the time the class was loaded, so that the sequencers of a service that is
   * restarted on the same data directory go on increasing.
   */
  private static final AtomicLong NEXT_SEQUENCER = new AtomicLong(System.currentTimeMillis() << 16);

  public S3Change {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(operation, "operation");
    Objects.requireNonNull(bucketName, "bucketName");
    Objects.requireNonNull(eventTime, "eventTime");
    Objects.requireNonNull(sequencer, "sequencer");
  }

  /**
   * A change made now, with the next sequencer.
   */
  public S3Change(S3ChangeType type, String operation, String bucketName, String bucketRegion, String key,
                  String versionId, Long size, String etag, boolean deleteMarker, String uploadId) {
    this(type, operation, bucketName, bucketRegion, key, versionId, size, etag, deleteMarker, uploadId, Instant.now(),
        String.format("%016X", NEXT_SEQUENCER.getAndIncrement()));
  }

  public static S3Change bucketCreated(String operation, String bucketName, String bucketRegion) {
    return new S3Change(S3ChangeType.BUCKET_CREATED, operation, bucketName, bucketRegion, null, null, null, null,
        false, null);
  }

  public static S3Change bucketDeleted(String operation, String bucketName, String bucketRegion) {
    return new S3Change(S3ChangeType.BUCKET_DELETED, operation, bucketName, bucketRegion, null, null, null, null,
        false, null);
  }

  /**
   * A change of an object version that still exists afterwards: {@linkplain S3ChangeType#OBJECT_CREATED},
   * {@linkplain S3ChangeType#OBJECT_TAGGING_PUT}, {@linkplain S3ChangeType#OBJECT_TAGGING_DELETED} or
   * {@linkplain S3ChangeType#OBJECT_ACL_PUT}.
   */
  public static S3Change objectVersion(S3ChangeType type, String operation, String bucketName, String key,
                                       String versionId, long size, String etag) {
    return new S3Change(type, operation, bucketName, null, Objects.requireNonNull(key), versionId, size, etag, false,
        null);
  }

  public static S3Change objectDeleted(String operation, String bucketName, String key, String versionId,
                                       boolean deleteMarker) {
    return new S3Change(S3ChangeType.OBJECT_DELETED, operation, bucketName, null, Objects.requireNonNull(key),
        versionId, null, null, deleteMarker, null);
  }

  public static S3Change multipartUploadAborted(String operation, String bucketName, String key, String uploadId) {
    return new S3Change(S3ChangeType.MULTIPART_UPLOAD_ABORTED, operation, bucketName, null,
        Objects.requireNonNull(key), null, null, null, false, Objects.requireNonNull(uploadId));
  }

  /**
   * The same change, made by another operation.
   *
   * @param operation the operation.
   * @return a change that names {@code operation}.
   */
  public S3Change withOperation(String operation) {
    return new S3Change(type, operation, bucketName, bucketRegion, key, versionId, size, etag, deleteMarker, uploadId,
        eventTime, sequencer);
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof S3Change other
        && type == other.type
        && deleteMarker == other.deleteMarker
        && operation.equals(other.operation)
        && bucketName.equals(other.bucketName)
        && Objects.equals(bucketRegion, other.bucketRegion)
        && Objects.equals(key, other.key)
        && Objects.equals(versionId, other.versionId)
        && Objects.equals(size, other.size)
        && Objects.equals(etag, other.etag)
        && Objects.equals(uploadId, other.uploadId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(type, operation, bucketName, bucketRegion, key, versionId, size, etag, deleteMarker, uploadId);
  }

  /**
   * The name of the <a href="https://docs.aws.amazon.com/AmazonS3/latest/userguide/notification-how-to-event-types-and-destinations.html">event
   * type</a> that an Amazon S3 event notification of this change carries, e.g. {@code s3:ObjectCreated:Copy}.
   *
   * @return the name of the event type; {@code null} if Amazon S3 doesn't notify of such a change, e.g. of a created
   *     bucket.
   */
  public String s3EventName() {
    return s3EventName(type, operation, deleteMarker);
  }

  /**
   * The record of this change in an Amazon S3 event notification, see {@linkplain S3EventNotification#toRecord}.
   *
   * @return the record.
   * @throws IllegalStateException if Amazon S3 doesn't notify of such a change, see {@linkplain #s3EventName()}.
   */
  public ObjectNode toS3EventRecord() {
    return S3EventNotification.toRecord(this, null);
  }

  /**
   * The <a href="https://docs.aws.amazon.com/AmazonS3/latest/userguide/notification-content-structure.html">Amazon S3
   * event notification</a> of this change, {@code {"Records":[...]}}, as the JSON that Amazon S3 would post, e.g. to
   * compare in a test, or to send from a webhook.
   *
   * @return the JSON of the notification.
   * @throws IllegalStateException if Amazon S3 doesn't notify of such a change, see {@linkplain #s3EventName()}.
   * @see S3EventNotification
   */
  public String toS3EventJson() {
    if (!S3EventNotification.isNotifiable(this)) {
      throw new IllegalStateException("Amazon S3 doesn't notify of " + type + ".");
    }
    return S3EventNotification.toJson(List.of(this), null);
  }

  public String getObjectS3Url() {
    return "s3://" + bucketName + "/" + key;
  }

  /**
   * The name of the Amazon S3 event notification type of a change, see {@linkplain #s3EventName()}.
   *
   * @param type what changed.
   * @param operation the operation that made the change.
   * @param deleteMarker whether a deletion created a delete marker.
   * @return the name of the event type; {@code null} if Amazon S3 doesn't notify of such a change.
   */
  public static String s3EventName(S3ChangeType type, String operation, boolean deleteMarker) {
    return switch (type) {
      case OBJECT_CREATED -> switch (Objects.toString(operation, "")) {
        case "CopyObject" -> "s3:ObjectCreated:Copy";
        case "CompleteMultipartUpload" -> "s3:ObjectCreated:CompleteMultipartUpload";
        case "PostObject" -> "s3:ObjectCreated:Post";
        default -> "s3:ObjectCreated:Put";
      };
      case OBJECT_DELETED -> "LifecycleExpiration".equals(operation)
          ? (deleteMarker ? "s3:LifecycleExpiration:DeleteMarkerCreated" : "s3:LifecycleExpiration:Delete")
          : (deleteMarker ? "s3:ObjectRemoved:DeleteMarkerCreated" : "s3:ObjectRemoved:Delete");
      case OBJECT_TAGGING_PUT -> "s3:ObjectTagging:Put";
      case OBJECT_TAGGING_DELETED -> "s3:ObjectTagging:Delete";
      case OBJECT_ACL_PUT -> "s3:ObjectAcl:Put";
      case BUCKET_CREATED, BUCKET_DELETED, MULTIPART_UPLOAD_ABORTED -> null;
    };
  }

}
