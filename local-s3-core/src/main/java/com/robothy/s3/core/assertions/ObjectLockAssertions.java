package com.robothy.s3.core.assertions;

import com.robothy.s3.core.exception.LocalS3InvalidArgumentException;
import com.robothy.s3.core.exception.LocalS3RequestException;
import com.robothy.s3.core.exception.S3ErrorCode;
import com.robothy.s3.core.model.BucketObjectLockConfiguration;
import com.robothy.s3.core.model.DefaultRetention;
import com.robothy.s3.core.model.internal.BucketMetadata;
import com.robothy.s3.core.model.internal.ObjectLock;
import com.robothy.s3.core.model.internal.VersionedObjectMetadata;
import java.util.Objects;

/**
 * The rules of <a href="https://docs.aws.amazon.com/AmazonS3/latest/userguide/object-lock.html">Object Lock</a> that
 * the operations that store or delete an object version share.
 */
public final class ObjectLockAssertions {

  private ObjectLockAssertions() {
  }

  /**
   * Validate the Object Lock settings that a request stores a new object version with.
   *
   * @param requested the settings of the request; {@code null} if it names none.
   * @param now the epoch milliseconds of the request.
   * @throws LocalS3InvalidArgumentException if only one of the mode and the retain until date is given, or the date
   *     isn't in the future.
   */
  public static void assertRequestedObjectLockIsValid(ObjectLock requested, long now) {
    if (requested == null) {
      return;
    }
    if ((requested.mode() == null) != (requested.retainUntilDate() == null)) {
      throw new LocalS3InvalidArgumentException("ObjectLockMode", Objects.toString(requested.mode(), null),
          "x-amz-object-lock-retain-until-date and x-amz-object-lock-mode must both be supplied");
    }
    if (requested.retainUntilDate() != null && requested.retainUntilDate() <= now) {
      throw new LocalS3InvalidArgumentException("RetainUntilDate", String.valueOf(requested.retainUntilDate()),
          "The retain until date must be in the future!");
    }
  }

  /**
   * Assert that a bucket has Object Lock enabled, which the Object Lock settings of an object need.
   *
   * @param bucketMetadata the bucket.
   * @return the object lock configuration of the bucket.
   * @throws LocalS3RequestException {@code InvalidRequest} if the bucket doesn't have Object Lock enabled.
   */
  public static BucketObjectLockConfiguration assertObjectLockEnabled(BucketMetadata bucketMetadata) {
    return bucketMetadata.getObjectLock().orElseThrow(() ->
        new LocalS3RequestException(S3ErrorCode.InvalidRequest, "Bucket is missing Object Lock Configuration"));
  }

  /**
   * Resolve the protection of a new version that is added to a bucket: the settings the version was requested with,
   * with the default retention of the bucket if it wasn't requested with a retention of its own. Called under the write
   * lock of the bucket, by every operation that adds a version with content.
   *
   * @param bucketMetadata the bucket the version is added to.
   * @param version the new version, whose protection is replaced by the resolved one.
   * @throws LocalS3RequestException {@code InvalidRequest} if the version was requested with Object Lock settings, but
   *     the bucket doesn't have Object Lock enabled.
   */
  public static void applyBucketObjectLock(BucketMetadata bucketMetadata, VersionedObjectMetadata version) {
    ObjectLock requested = version.getObjectLock();
    BucketObjectLockConfiguration configuration = bucketMetadata.getObjectLock().orElse(null);
    if (configuration == null) {
      if (requested != null && !requested.isEmpty()) {
        assertObjectLockEnabled(bucketMetadata);
      }
      return;
    }
    DefaultRetention defaultRetention = configuration.defaultRetention();
    ObjectLock lock = requested == null ? ObjectLock.NONE : requested;
    if (!lock.hasRetention() && defaultRetention != null) {
      lock = lock.withRetention(defaultRetention.mode(), defaultRetention.retainUntil(version.getCreationDate()));
    }
    version.setObjectLock(lock.isEmpty() ? null : lock);
  }

  /**
   * Assert that a version can be deleted permanently, i.e. that neither a legal hold nor a retention protects it.
   *
   * @param version the version to delete.
   * @param now the epoch milliseconds of the request.
   * @param bypassGovernanceRetention whether the request bypasses a governance mode retention.
   * @throws LocalS3RequestException {@code AccessDenied} if the version is protected.
   */
  public static void assertVersionIsDeletable(VersionedObjectMetadata version, long now,
                                              boolean bypassGovernanceRetention) {
    if (isProtected(version, now, bypassGovernanceRetention)) {
      throw new LocalS3RequestException(S3ErrorCode.AccessDenied,
          "Access Denied because object protected by object lock.");
    }
  }

  /**
   * Whether a version is protected from being deleted permanently.
   *
   * @param version the version.
   * @param now the epoch milliseconds to evaluate the protection at.
   * @param bypassGovernanceRetention whether a governance mode retention is bypassed.
   * @return {@code true} if a legal hold or a retention protects the version.
   */
  public static boolean isProtected(VersionedObjectMetadata version, long now, boolean bypassGovernanceRetention) {
    ObjectLock lock = version.getObjectLock();
    return lock != null && !version.isDeleted() && lock.protects(now, bypassGovernanceRetention);
  }

}
