package com.robothy.s3.core.service;

import com.robothy.s3.core.assertions.BucketAssertions;
import com.robothy.s3.core.assertions.ObjectLockAssertions;
import com.robothy.s3.core.assertions.PreconditionAssertions;
import com.robothy.s3.core.assertions.VersionedObjectAssertions;
import com.robothy.s3.core.event.S3Change;
import com.robothy.s3.core.exception.ObjectNotExistException;
import com.robothy.s3.core.exception.PreconditionFailedException;
import com.robothy.s3.core.model.answers.DeleteObjectAns;
import com.robothy.s3.core.model.internal.BucketMetadata;
import com.robothy.s3.core.model.internal.ObjectMetadata;
import com.robothy.s3.core.model.internal.ObjectMetadataRef;
import com.robothy.s3.core.model.internal.VersionedObjectMetadata;
import com.robothy.s3.core.model.request.ObjectPreconditions;
import com.robothy.s3.core.storage.Storage;
import com.robothy.s3.core.util.ObjectContentUtils;
import com.robothy.s3.core.util.IdUtils;
import java.util.Objects;
import java.util.Optional;

/**
 * Delete object operation.
 */
public interface DeleteObjectService extends LocalS3MetadataApplicable, StorageApplicable {

  default DeleteObjectAns deleteObject(String bucketName, String key) {
    return changeBucket(bucketName, () -> {
      return deleteObject(bucketName, key, null);
    });
  }

  default DeleteObjectAns deleteObject(String bucketName, String key, String versionId) {
    return deleteObject(bucketName, key, versionId, ObjectPreconditions.none());
  }

  /**
   * Delete an object, or a version of it, if the current version of the object satisfies the conditions of the
   * request, which are evaluated under the write lock of the bucket; see
   * {@linkplain PreconditionAssertions#assertDeletePreconditionsHold} for how.
   *
   * @param bucketName the bucket name.
   * @param key the object key.
   * @param versionId the version to delete; {@code null} to delete the object.
   * @param preconditions the {@code If-Match}, {@code x-amz-if-match-last-modified-time} and
   *     {@code x-amz-if-match-size} conditions; {@linkplain ObjectPreconditions#none()} for none.
   * @return result of the delete.
   * @throws PreconditionFailedException if a condition didn't hold.
   * @throws ObjectNotExistException if {@code If-Match} was given and the key holds no version.
   */
  default DeleteObjectAns deleteObject(String bucketName, String key, String versionId,
                                       ObjectPreconditions preconditions) {
    return deleteObject(bucketName, key, versionId, preconditions, false);
  }

  /**
   * Delete an object, or a version of it, like {@linkplain #deleteObject(String, String, String, ObjectPreconditions)}.
   * A version that Object Lock protects, i.e. that has a legal hold on or a retention that hasn't expired, can't be
   * deleted permanently; deleting the object without a version ID still adds a delete marker.
   *
   * @param bypassGovernanceRetention whether the request sends {@code x-amz-bypass-governance-retention: true}, which
   *     deletes a version whose retention is in {@code GOVERNANCE} mode.
   * @throws com.robothy.s3.core.exception.LocalS3RequestException {@code AccessDenied} if Object Lock protects the
   *     version to delete.
   */
  default DeleteObjectAns deleteObject(String bucketName, String key, String versionId,
                                       ObjectPreconditions preconditions, boolean bypassGovernanceRetention) {
    return changeBucket(bucketName, () -> {
      BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucketName);
      PreconditionAssertions.assertDeletePreconditionsHold(preconditions, key,
          bucketMetadata.getObjectMetadata(key).orElse(null));
      DeleteObjectAns ans;
      if (Objects.isNull(bucketMetadata.getVersioningEnabled())) {
        ans = deleteObjectFromUnVersionedBucket(bucketMetadata, storage(), key, versionId);
      } else if (Objects.nonNull(versionId)) {
        Optional<VersionedObjectMetadata> version = bucketMetadata.getObjectMetadataRef(key).map(ObjectMetadataRef::get)
            .flatMap(objectMetadata -> versionToDelete(objectMetadata, versionId));
        long now = System.currentTimeMillis();
        version.ifPresent(v -> ObjectLockAssertions.assertVersionIsDeletable(v, now, bypassGovernanceRetention));
        ans = deleteWithVersionId(storage(), bucketMetadata, key, versionId);
        if (version.isEmpty()) {
          // The version is already gone: the delete succeeds, and changes nothing to publish.
          return ans;
        }
      } else {
        ans = deleteWithoutVersionId(storage(), bucketMetadata, key);
      }
      publishChange(S3Change.objectDeleted("DeleteObject", bucketName, key, ans.getVersionId(), ans.isDeleteMarker()));
      return ans;
    });
  }

  /**
   * The version that a delete with a version ID removes. Unlike a read, a delete never fails on its version ID, not even
   * a malformed one, which deletes nothing; neither does the ID that the null version is held by inside LocalS3, which
   * is never answered to a client.
   *
   * @param objectMetadata the object that the key holds.
   * @param versionId the version ID of the request.
   * @return the version to delete; empty if there is none.
   */
  private static Optional<VersionedObjectMetadata> versionToDelete(ObjectMetadata objectMetadata, String versionId) {
    if (ObjectMetadata.NULL_VERSION.equals(versionId)) {
      return objectMetadata.getVirtualVersion().flatMap(objectMetadata::getVersionedObjectMetadata);
    }
    if (objectMetadata.getVirtualVersion().map(versionId::equals).orElse(false)) {
      return Optional.empty();
    }
    return objectMetadata.getVersionedObjectMetadata(versionId);
  }

  static DeleteObjectAns deleteObjectFromUnVersionedBucket(BucketMetadata bucketMetadata, Storage storage, String key, String versionId) {
    if (Objects.nonNull(versionId) && !ObjectMetadata.NULL_VERSION.equals(versionId)) {
      throw VersionedObjectAssertions.invalidVersionId(versionId);
    }

    ObjectMetadata removedObject = bucketMetadata.removeObjectMetadata(key);
    if (Objects.nonNull(removedObject)) { // the object exists
      VersionedObjectMetadata removedVersion = removedObject.getVersionedObjectMap().firstEntry().getValue();
      ObjectContentUtils.delete(storage, removedVersion);
    }
    return DeleteObjectAns.builder().build();
  }

  /**
   * Delete without version ID.
   * <ul>
   *   <li><b>If the key exists</b></li>
   *
   *     <li>If bucket versioning enabled, create a delete marker with version ID.</li>
   *     <li>If bucket versioning disabled, create a delete marker with virtual version.</li>
   *
   *   <li><b>If the key not exists.</b></li>
   *
   *     <li>If bucket versioning enabled, create the object with a deleted marker with version ID.</li>
   *     <li>If bucket versioning suspended, create the object with a deleted marker with virtual version ID.</li>
   *
   * </ul>
   */
   static DeleteObjectAns deleteWithoutVersionId(Storage storage, BucketMetadata bucketMetadata, String key) {
    Optional<ObjectMetadata> objectMetadataOpt = bucketMetadata.getObjectMetadata(key);
    String returnedVersionId;
    if (objectMetadataOpt.isPresent()) { // key exists
      ObjectMetadata objectMetadata = objectMetadataOpt.get();
      VersionedObjectMetadata deleteMarker = createDeleteMarker();
      String versionId = IdUtils.defaultGenerator().nextStrId();
      if (Boolean.TRUE.equals(bucketMetadata.getVersioningEnabled())) { // versioning enabled
        objectMetadata.putVersionedObjectMetadata(versionId, deleteMarker);
        returnedVersionId = versionId;
      } else { // versioning disabled.
        objectMetadata.putVersionedObjectMetadata(versionId, deleteMarker);
        if (objectMetadata.getVirtualVersion().isPresent()) {
          VersionedObjectMetadata removed =
              objectMetadata.removeVersionedObjectMetadata(objectMetadata.getVirtualVersion().get());
          if (!removed.isDeleted()) {
            ObjectContentUtils.delete(storage, removed);
          }
        }
        objectMetadata.setVirtualVersion(versionId);
        returnedVersionId = ObjectMetadata.NULL_VERSION;
      }
    } else { // key not exists.
      VersionedObjectMetadata deleteMarker = createDeleteMarker();
      String versionId = returnedVersionId = IdUtils.defaultGenerator().nextStrId();
      ObjectMetadata objectMetadata = new ObjectMetadata(versionId, deleteMarker);
      if (!Boolean.TRUE.equals(bucketMetadata.getVersioningEnabled())) {
        objectMetadata.setVirtualVersion(versionId);
        returnedVersionId = ObjectMetadata.NULL_VERSION;
      }
      bucketMetadata.putObjectMetadata(key, objectMetadata);
    }

    return DeleteObjectAns.builder()
        .isDeleteMarker(true)
        .versionId(returnedVersionId)
        .build();
  }

  static VersionedObjectMetadata createDeleteMarker() {
    VersionedObjectMetadata versionedObjectMetadata = new VersionedObjectMetadata();
    versionedObjectMetadata.setDeleted(true);
    versionedObjectMetadata.setCreationDate(System.currentTimeMillis());
    return versionedObjectMetadata;
  }

  /**
   * Delete with version ID. Like Amazon S3, it is idempotent: deleting a version that doesn't exist, or a version of a
   * key that holds none, succeeds, so that the retries and the concurrent cleanups of a client, e.g. the
   * {@code DeleteObjects} of Iceberg, Delta Lake or DuckLake, don't fail on a version that is already gone. The
   * preconditions of the request, e.g. {@code If-Match}, are evaluated before, and still fail on a missing key.
   * <ul>
   *    <li>If the key holds no version, do nothing and return the given version ID.</li>
   *    <li>If the version ID is 'null', try to find the virtual version object and remove.</li>
   *    <li>If the version ID is exists, find the versioned object and remove.</li>
   *    <li>If the version ID is not exists, do nothing and return the given version ID.</li>
   * </ul>
   */
  static DeleteObjectAns deleteWithVersionId(Storage storage, BucketMetadata bucketMetadata, String key, String versionId) {
    Optional<ObjectMetadata> objectMetadataOpt = bucketMetadata.getObjectMetadata(key);
    if (objectMetadataOpt.isEmpty()) {
      return DeleteObjectAns.builder()
          .isDeleteMarker(false)
          .versionId(versionId)
          .build();
    }
    ObjectMetadata objectMetadata = objectMetadataOpt.get();
    boolean isDeleteMarker = false;
    if (ObjectMetadata.NULL_VERSION.equals(versionId)) {
      Optional<String> virtualVersionOpt = objectMetadata.getVirtualVersion();
      if (virtualVersionOpt.isPresent()) {
        VersionedObjectMetadata toRemove = objectMetadata.removeVersionedObjectMetadata(virtualVersionOpt.get());
        isDeleteMarker = toRemove.isDeleted();
        if (!isDeleteMarker) {
          ObjectContentUtils.delete(storage, toRemove);
        }
        objectMetadata.setVirtualVersion(null);
      }
    } else {
      Optional<VersionedObjectMetadata> versionedObjectMetadataOpt = objectMetadata.getVersionedObjectMetadata(versionId);
      if (versionedObjectMetadataOpt.isPresent()
          && !objectMetadata.getVirtualVersion().map(versionId::equals).orElse(false)) {
        VersionedObjectMetadata removed = objectMetadata.removeVersionedObjectMetadata(versionId);
        isDeleteMarker = removed.isDeleted();
        if (!isDeleteMarker) {
          ObjectContentUtils.delete(storage, removed);
        }
      }
    }

    if (objectMetadata.getVersionedObjectMap().isEmpty()) {
      bucketMetadata.removeObjectMetadata(key);
    }

    return DeleteObjectAns.builder()
        .isDeleteMarker(isDeleteMarker)
        .versionId(versionId)
        .build();
  }

}
