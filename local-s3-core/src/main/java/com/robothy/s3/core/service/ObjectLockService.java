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
 * See {@code docs/semantics.md#object-lock}.
 */
public interface ObjectLockService extends LocalS3MetadataApplicable {

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

  default ObjectLock getObjectRetention(String bucketName, String key, String versionId) {
    ObjectLock lock = readVersion(bucketName, key, versionId);
    if (!lock.hasRetention()) {
      throw new LocalS3RequestException(S3ErrorCode.NoSuchObjectLockConfiguration);
    }
    return lock;
  }

  default void putObjectLegalHold(String bucketName, String key, String versionId, boolean on) {
    changeVersion(bucketName, key, versionId, lock -> lock.withLegalHold(on));
  }

  default boolean getObjectLegalHold(String bucketName, String key, String versionId) {
    ObjectLock lock = readVersion(bucketName, key, versionId);
    if (lock.legalHold() == null) {
      throw new LocalS3RequestException(S3ErrorCode.NoSuchObjectLockConfiguration);
    }
    return lock.legalHold();
  }

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

  record ResolvedVersion(String versionId, VersionedObjectMetadata version) {
  }

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
