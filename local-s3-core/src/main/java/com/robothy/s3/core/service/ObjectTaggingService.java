package com.robothy.s3.core.service;

import com.robothy.s3.core.assertions.BucketAssertions;
import com.robothy.s3.core.assertions.ObjectAssertions;
import com.robothy.s3.core.event.S3Change;
import com.robothy.s3.core.event.S3ChangeType;
import com.robothy.s3.core.exception.MethodNotAllowedException;
import com.robothy.s3.core.model.answers.GetObjectTaggingAns;
import com.robothy.s3.core.model.internal.BucketMetadata;
import com.robothy.s3.core.model.internal.ObjectMetadata;
import com.robothy.s3.core.model.internal.VersionedObjectMetadata;
import com.robothy.s3.core.util.VersionedObjectUtils;

/**
 * Object tagging service.
 */
public interface ObjectTaggingService extends LocalS3MetadataApplicable {

  /**
   * Put tagging to the specified versioned object.
   *
   * @param bucketName bucket name.
   * @param key object key.
   * @param versionId version ID.
   * @param tagging new tagging of the versioned object.
   * @return version ID where the new tagging applies to.
   * @throws com.robothy.s3.core.exception.LocalS3RequestException {@code InvalidTag} if the tags exceed the limits
   *     of Amazon S3, in which case the object keeps the tags it has.
   */
  default String putObjectTagging(String bucketName, String key, String versionId, String[][] tagging) {
    ObjectAssertions.assertObjectTaggingIsValid(tagging);
    return changeBucket(bucketName, () -> {
      BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucketName);
      ObjectMetadata objectMetadata = ObjectAssertions.assertObjectExists(bucketMetadata, key);
      VersionedObjectMetadata versionedObjectMetadata = VersionedObjectUtils.getVersionedObjectMetadata(objectMetadata, versionId);
      if (versionedObjectMetadata.isDeleted()) {
        throw new MethodNotAllowedException("Cannot put object tagging to a delete marker.");
      }
      versionedObjectMetadata.setTagging(tagging);
      objectMetadata.markVersionChanged(VersionedObjectUtils.resolveVersionKey(objectMetadata, versionId));
      String returnedVersion = VersionedObjectUtils.resolveReturnedVersion(bucketMetadata, objectMetadata, versionId);
      publishChange(S3Change.objectVersion(S3ChangeType.OBJECT_TAGGING_PUT, "PutObjectTagging", bucketName, key,
          returnedVersion, versionedObjectMetadata.getSize(), versionedObjectMetadata.getEtag()));
      return returnedVersion;
    });
  }

  /**
   * Get tagging from the specified versioned object.
   *
   * @param bucketName bucket name.
   * @param key object key.
   * @param versionId version ID.
   * @return versioned object tagging.
   */
  default GetObjectTaggingAns getObjectTagging(String bucketName, String key, String versionId) {
    return withBucketReadLock(bucketName, () -> {
      BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucketName);
      ObjectMetadata objectMetadata = ObjectAssertions.assertObjectExists(bucketMetadata, key);
      VersionedObjectMetadata versionedObjectMetadata = VersionedObjectUtils.getVersionedObjectMetadata(objectMetadata, versionId);
      if (versionedObjectMetadata.isDeleted()) {
        throw new MethodNotAllowedException("Cannot get object tagging from a delete marker.");
      }
      String[][] tagging = versionedObjectMetadata.getTagging().orElse(new String[0][0]);
      return GetObjectTaggingAns.builder()
          .tagging(tagging)
          .versionId(VersionedObjectUtils.resolveReturnedVersion(bucketMetadata, objectMetadata, versionId))
          .build();
    });
  }

  /**
   * Delete object tagging from the specified versioned object.
   *
   * @param bucketName bucket name.
   * @param key object key.
   * @param versionId version ID.
   * @return version ID of the object where the tagging is deleted from.
   */
  default String deleteObjectTagging(String bucketName, String key, String versionId) {
    return changeBucket(bucketName, () -> {
      BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucketName);
      ObjectMetadata objectMetadata = ObjectAssertions.assertObjectExists(bucketMetadata, key);
      VersionedObjectMetadata versionedObjectMetadata = VersionedObjectUtils.getVersionedObjectMetadata(objectMetadata, versionId);
      if (versionedObjectMetadata.isDeleted()) {
        throw new MethodNotAllowedException("Cannot delete object tagging from a delete marker.");
      }
      versionedObjectMetadata.setTagging(null);
      objectMetadata.markVersionChanged(VersionedObjectUtils.resolveVersionKey(objectMetadata, versionId));
      String returnedVersion = VersionedObjectUtils.resolveReturnedVersion(bucketMetadata, objectMetadata, versionId);
      publishChange(S3Change.objectVersion(S3ChangeType.OBJECT_TAGGING_DELETED, "DeleteObjectTagging", bucketName,
          key, returnedVersion, versionedObjectMetadata.getSize(), versionedObjectMetadata.getEtag()));
      return returnedVersion;
    });
  }

}
