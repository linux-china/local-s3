package com.robothy.s3.core.service;

import com.robothy.s3.core.assertions.BucketAssertions;
import com.robothy.s3.core.assertions.ObjectAssertions;
import com.robothy.s3.core.exception.LocalS3RequestException;
import com.robothy.s3.core.exception.MethodNotAllowedException;
import com.robothy.s3.core.exception.S3ErrorCode;
import com.robothy.s3.core.model.internal.BucketMetadata;
import com.robothy.s3.core.model.internal.ObjectMetadata;
import com.robothy.s3.core.model.internal.SystemMetadata;
import com.robothy.s3.core.model.internal.VersionedObjectMetadata;
import com.robothy.s3.core.util.VersionedObjectUtils;
import com.robothy.s3.datatypes.enums.StorageClass;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Set;

/**
 * See {@code docs/semantics.md#storage-classes-and-restores}.
 */
public interface RestoreObjectService extends LocalS3MetadataApplicable {

  Set<StorageClass> ARCHIVED_STORAGE_CLASSES = Set.of(StorageClass.GLACIER, StorageClass.DEEP_ARCHIVE);

  /**
   * @return {@code true} if the object already had a restored copy, which is extended: {@code 200} rather than
   *     {@code 202}.
   */
  default boolean restoreObject(String bucketName, String key, String versionId, int days) {
    if (days < 1) {
      throw new LocalS3RequestException(S3ErrorCode.InvalidArgument, "The Days of a restore must be positive.");
    }
    return changeBucket(bucketName, () -> {
      BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucketName);
      ObjectMetadata objectMetadata = ObjectAssertions.assertObjectExists(bucketMetadata, key);
      VersionedObjectMetadata versionedObjectMetadata =
          VersionedObjectUtils.getVersionedObjectMetadata(bucketMetadata, objectMetadata, versionId);
      if (versionedObjectMetadata.isDeleted()) {
        throw new MethodNotAllowedException("Cannot restore a delete marker.");
      }
      StorageClass storageClass = SystemMetadata.storageClassOf(versionedObjectMetadata.getSystemMetadata());
      if (!ARCHIVED_STORAGE_CLASSES.contains(storageClass)) {
        throw new LocalS3RequestException(S3ErrorCode.InvalidObjectState,
            "Restore is not allowed for the object's current storage class");
      }
      boolean restored = Objects.nonNull(activeRestoreExpiryDate(versionedObjectMetadata));
      versionedObjectMetadata.setRestoreExpiryDate(restoreExpiryDate(System.currentTimeMillis(), days));
      objectMetadata.markVersionChanged(VersionedObjectUtils.resolveVersionKey(objectMetadata, versionId));
      return restored;
    });
  }

  /** {@code null} if the version has no restored copy, or it expired. */
  static Long activeRestoreExpiryDate(VersionedObjectMetadata versionedObjectMetadata) {
    Long expiryDate = versionedObjectMetadata.getRestoreExpiryDate();
    return Objects.nonNull(expiryDate) && expiryDate > System.currentTimeMillis() ? expiryDate : null;
  }

  static long restoreExpiryDate(long now, int days) {
    return Instant.ofEpochMilli(now).plus(days + 1L, ChronoUnit.DAYS).truncatedTo(ChronoUnit.DAYS).toEpochMilli();
  }

}
