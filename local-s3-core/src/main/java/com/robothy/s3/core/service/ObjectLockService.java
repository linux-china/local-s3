package com.robothy.s3.core.service;

import com.robothy.s3.core.assertions.BucketAssertions;
import com.robothy.s3.core.assertions.ObjectAssertions;
import com.robothy.s3.core.assertions.ObjectLockAssertions;
import com.robothy.s3.core.assertions.VersionedObjectAssertions;
import com.robothy.s3.core.exception.LocalS3RequestException;
import com.robothy.s3.core.exception.MethodNotAllowedException;
import com.robothy.s3.core.exception.ObjectNotExistException;
import com.robothy.s3.core.exception.S3ErrorCode;
import com.robothy.s3.core.exception.VersionedObjectNotExistException;
import com.robothy.s3.core.model.ObjectLockMode;
import com.robothy.s3.core.model.internal.BucketMetadata;
import com.robothy.s3.core.model.internal.ObjectLock;
import com.robothy.s3.core.model.internal.ObjectMetadata;
import com.robothy.s3.core.model.internal.VersionedObjectMetadata;
import java.util.Objects;
import java.util.function.Function;

/**
 * The Object Lock retention and legal hold of object versions, in a bucket that has Object Lock enabled.
 *
 * <p>A retention that protects a version can be extended, but not shortened or removed, unless it is in
 * {@code GOVERNANCE} mode and the request sends {@code x-amz-bypass-governance-retention: true}. Its mode can't be
 * changed either: a {@code GOVERNANCE} retention is turned into a {@code COMPLIANCE} one only with the bypass, like
 * Amazon S3 answers, and a {@code COMPLIANCE} one never changes its mode. A retention that has expired can be replaced
 * by any retention.
 *
 * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_PutObjectRetention.html">PutObjectRetention</a>
 * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_GetObjectRetention.html">GetObjectRetention</a>
 * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_PutObjectLegalHold.html">PutObjectLegalHold</a>
 * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_GetObjectLegalHold.html">GetObjectLegalHold</a>
 */
public interface ObjectLockService extends LocalS3MetadataApplicable {

  /**
   * Put the retention of an object version.
   *
   * @param bucketName the bucket name.
   * @param key the object key.
   * @param versionId the version; {@code null} for the current one.
   * @param mode the retention mode; {@code null}, with {@code retainUntilDate}, to remove the retention.
   * @param retainUntilDate the epoch milliseconds to retain the version until; {@code null} to remove the retention.
   * @param bypassGovernanceRetention whether the request bypasses a governance mode retention.
   * @throws LocalS3RequestException {@code InvalidRequest} if the bucket doesn't have Object Lock enabled;
   *     {@code AccessDenied} if the change would weaken a retention that protects the version.
   */
  default void putObjectRetention(String bucketName, String key, String versionId, ObjectLockMode mode,
                                  Long retainUntilDate, boolean bypassGovernanceRetention) {
    long now = System.currentTimeMillis();
    ObjectLockAssertions.assertRequestedObjectLockIsValid(new ObjectLock(mode, retainUntilDate, null), now);
    changeVersion(bucketName, key, versionId, lock -> {
      if (lock.isRetainedAt(now) && !isAllowedRetentionChange(lock, mode, retainUntilDate,
          bypassGovernanceRetention)) {
        throw new LocalS3RequestException(S3ErrorCode.AccessDenied,
            "Access Denied because object protected by object lock.");
      }
      return lock.withRetention(mode, retainUntilDate);
    });
  }

  /**
   * Get the retention of an object version.
   *
   * @param bucketName the bucket name.
   * @param key the object key.
   * @param versionId the version; {@code null} for the current one.
   * @return the protection of the version, which has a retention.
   * @throws LocalS3RequestException {@code NoSuchObjectLockConfiguration} if the version has no retention.
   */
  default ObjectLock getObjectRetention(String bucketName, String key, String versionId) {
    ObjectLock lock = readVersion(bucketName, key, versionId);
    if (!lock.hasRetention()) {
      throw new LocalS3RequestException(S3ErrorCode.NoSuchObjectLockConfiguration);
    }
    return lock;
  }

  /**
   * Turn the legal hold of an object version on or off.
   *
   * @param bucketName the bucket name.
   * @param key the object key.
   * @param versionId the version; {@code null} for the current one.
   * @param on whether the legal hold is on.
   * @throws LocalS3RequestException {@code InvalidRequest} if the bucket doesn't have Object Lock enabled.
   */
  default void putObjectLegalHold(String bucketName, String key, String versionId, boolean on) {
    changeVersion(bucketName, key, versionId, lock -> lock.withLegalHold(on));
  }

  /**
   * Get whether the legal hold of an object version is on.
   *
   * @param bucketName the bucket name.
   * @param key the object key.
   * @param versionId the version; {@code null} for the current one.
   * @return {@code true} if the legal hold is on.
   * @throws LocalS3RequestException {@code NoSuchObjectLockConfiguration} if the legal hold of the version was never
   *     set.
   */
  default boolean getObjectLegalHold(String bucketName, String key, String versionId) {
    ObjectLock lock = readVersion(bucketName, key, versionId);
    if (lock.legalHold() == null) {
      throw new LocalS3RequestException(S3ErrorCode.NoSuchObjectLockConfiguration);
    }
    return lock.legalHold();
  }

  /**
   * Whether a change of a retention that protects a version is allowed: one that keeps the version at least as long in
   * the same mode, or any change of a {@code GOVERNANCE} retention that is bypassed, e.g. into {@code COMPLIANCE} mode.
   */
  private static boolean isAllowedRetentionChange(ObjectLock current, ObjectLockMode mode, Long retainUntilDate,
                                                  boolean bypassGovernanceRetention) {
    if (current.mode() == ObjectLockMode.GOVERNANCE && bypassGovernanceRetention) {
      return true;
    }
    if (mode == null || retainUntilDate < current.retainUntilDate()) {
      return false;
    }
    return mode == current.mode();
  }

  private ObjectLock readVersion(String bucketName, String key, String versionId) {
    return withBucketReadLock(bucketName, () -> {
      BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucketName);
      ObjectLockAssertions.assertObjectLockEnabled(bucketMetadata);
      ObjectMetadata objectMetadata = ObjectAssertions.assertObjectExists(bucketMetadata, key);
      VersionedObjectMetadata version = resolveVersion(objectMetadata, key, versionId).version();
      return Objects.requireNonNullElse(version.getObjectLock(), ObjectLock.NONE);
    });
  }

  private void changeVersion(String bucketName, String key, String versionId, Function<ObjectLock, ObjectLock> change) {
    changeBucket(bucketName, () -> {
      BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucketName);
      ObjectLockAssertions.assertObjectLockEnabled(bucketMetadata);
      ObjectMetadata objectMetadata = ObjectAssertions.assertObjectExists(bucketMetadata, key);
      ResolvedVersion resolved = resolveVersion(objectMetadata, key, versionId);
      ObjectLock changed = change.apply(Objects.requireNonNullElse(resolved.version().getObjectLock(),
          ObjectLock.NONE));
      resolved.version().setObjectLock(changed.isEmpty() ? null : changed);
      objectMetadata.markVersionChanged(resolved.versionId());
    });
  }

  /**
   * A version of an object, and the ID it is stored under.
   */
  record ResolvedVersion(String versionId, VersionedObjectMetadata version) {
  }

  /**
   * Resolve the version that a request names.
   *
   * @throws ObjectNotExistException if no version is named and the current version is a delete marker.
   * @throws MethodNotAllowedException if the named version is a delete marker.
   */
  private static ResolvedVersion resolveVersion(ObjectMetadata objectMetadata, String key, String versionId) {
    ResolvedVersion resolved;
    if (versionId == null) {
      resolved = new ResolvedVersion(objectMetadata.getLatestVersion(), objectMetadata.getLatest());
      if (resolved.version().isDeleted()) {
        throw new ObjectNotExistException(key);
      }
      return resolved;
    }
    if (ObjectMetadata.NULL_VERSION.equals(versionId)) {
      String virtualVersion = objectMetadata.getVirtualVersion()
          .orElseThrow(() -> new VersionedObjectNotExistException(key, versionId));
      resolved = new ResolvedVersion(virtualVersion, VersionedObjectAssertions.assertVirtualVersionExist(objectMetadata));
    } else {
      resolved = new ResolvedVersion(versionId, VersionedObjectAssertions.findVersionedObject(objectMetadata, versionId)
          .orElseThrow(() -> new VersionedObjectNotExistException(key, versionId)));
    }
    if (resolved.version().isDeleted()) {
      throw new MethodNotAllowedException("The specified method is not allowed against this resource.");
    }
    return resolved;
  }

}
