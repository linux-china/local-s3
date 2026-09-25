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
 * <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_RestoreObject.html">RestoreObject</a> of an archived
 * object, i.e. one of the {@code GLACIER} or {@code DEEP_ARCHIVE} storage class.
 *
 * <p>LocalS3 never archives the content of an object, so a restore only <b>records</b> that the object has a restored
 * copy, and until when: it completes at once, and {@code HeadObject} and {@code GetObject} answer it with the
 * {@code x-amz-restore} header, so that code that restores cold data before it reads it runs against LocalS3.
 */
public interface RestoreObjectService extends LocalS3MetadataApplicable {

  /**
   * The storage classes whose objects must be restored before Amazon S3 serves them.
   */
  Set<StorageClass> ARCHIVED_STORAGE_CLASSES = Set.of(StorageClass.GLACIER, StorageClass.DEEP_ARCHIVE);

  /**
   * Restore an archived object for a number of days, or extend the restored copy that it has.
   *
   * @param bucketName the bucket name.
   * @param key the object key.
   * @param versionId the version ID; {@code null} for the latest version.
   * @param days the number of days that the restored copy is kept for.
   * @return {@code true} if the object already had a restored copy, whose expiry date is then extended, like
   *     Amazon S3 answers {@code 200 OK} rather than {@code 202 Accepted}.
   * @throws LocalS3RequestException with {@linkplain S3ErrorCode#InvalidObjectState} if the object isn't archived,
   *     and with {@linkplain S3ErrorCode#InvalidArgument} if {@code days} isn't positive.
   */
  default boolean restoreObject(String bucketName, String key, String versionId, int days) {
    if (days < 1) {
      throw new LocalS3RequestException(S3ErrorCode.InvalidArgument, "The Days of a restore must be positive.");
    }
    return changeBucket(bucketName, () -> {
      BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucketName);
      ObjectMetadata objectMetadata = ObjectAssertions.assertObjectExists(bucketMetadata, key);
      VersionedObjectMetadata versionedObjectMetadata =
          VersionedObjectUtils.getVersionedObjectMetadata(objectMetadata, versionId);
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

  /**
   * When the restored copy of a version expires.
   *
   * @param versionedObjectMetadata the version.
   * @return the expiry date in epoch milliseconds; {@code null} if the version has no restored copy, or it expired.
   */
  static Long activeRestoreExpiryDate(VersionedObjectMetadata versionedObjectMetadata) {
    Long expiryDate = versionedObjectMetadata.getRestoreExpiryDate();
    return Objects.nonNull(expiryDate) && expiryDate > System.currentTimeMillis() ? expiryDate : null;
  }

  /**
   * The expiry date of a copy restored for a number of days: midnight UTC after the days elapse, like Amazon S3
   * rounds it.
   */
  static long restoreExpiryDate(long now, int days) {
    return Instant.ofEpochMilli(now).plus(days + 1L, ChronoUnit.DAYS).truncatedTo(ChronoUnit.DAYS).toEpochMilli();
  }

}
