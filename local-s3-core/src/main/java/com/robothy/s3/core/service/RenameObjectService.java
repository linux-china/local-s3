package com.robothy.s3.core.service;

import com.robothy.s3.core.assertions.BucketAssertions;
import com.robothy.s3.core.assertions.ObjectAssertions;
import com.robothy.s3.core.assertions.PreconditionAssertions;
import com.robothy.s3.core.event.S3Change;
import com.robothy.s3.core.event.S3ChangeType;
import com.robothy.s3.core.exception.LocalS3RequestException;
import com.robothy.s3.core.exception.ObjectNotExistException;
import com.robothy.s3.core.exception.PreconditionFailedException;
import com.robothy.s3.core.exception.S3ErrorCode;
import com.robothy.s3.core.model.answers.PutObjectAns;
import com.robothy.s3.core.model.internal.BucketMetadata;
import com.robothy.s3.core.model.internal.ObjectMetadata;
import com.robothy.s3.core.model.internal.VersionedObjectMetadata;
import com.robothy.s3.core.model.request.ObjectPreconditions;
import com.robothy.s3.core.model.request.RenameObjectOptions;
import java.util.Locale;
import java.util.Objects;

/**
 * <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_RenameObject.html">RenameObject</a>, which Amazon S3
 * offers for the directory buckets of S3 Express One Zone.
 *
 * <p>An object is renamed atomically, under the write lock of its bucket, and without copying its content: the
 * destination key gets the object with its content, entity tag, metadata, tags and checksum, and the source key no
 * longer holds it. An object that the destination key held is replaced. Like directory buckets, which have no
 * versioning, only a bucket whose versioning was never configured supports it.
 */
public interface RenameObjectService extends LocalS3MetadataApplicable, StorageApplicable {

  /**
   * The prefix of the headers that name the conditions of the object to rename.
   */
  String RENAME_SOURCE_PREFIX = "x-amz-rename-source-";

  /**
   * Rename an object.
   *
   * @param bucketName the bucket name.
   * @param key the new key of the object.
   * @param options the key of the object to rename, and the conditions of the request.
   * @throws LocalS3RequestException {@code InvalidRequest} if the versioning of the bucket was ever configured.
   * @throws ObjectNotExistException if the source key holds no object, or the destination holds none and
   *     {@code If-Match} is given.
   * @throws PreconditionFailedException if a condition doesn't hold.
   */
  default void renameObject(String bucketName, String key, RenameObjectOptions options) {
    ObjectAssertions.assertObjectKeyIsValid(key);
    String sourceKey = options.getSourceKey();
    changeBucket(bucketName, () -> {
      BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucketName);
      if (Objects.nonNull(bucketMetadata.getVersioningEnabled())) {
        throw new LocalS3RequestException(S3ErrorCode.InvalidRequest,
            "RenameObject is only supported by buckets whose versioning was never enabled.");
      }

      ObjectMetadata source = bucketMetadata.getObjectMetadataRef(sourceKey)
          .orElseThrow(() -> new ObjectNotExistException(sourceKey)).get();
      VersionedObjectMetadata renamed = source.getLatest();
      try {
        if (PreconditionAssertions.assertReadPreconditionsHold(options.getSourcePreconditions(), renamed.getEtag(),
            renamed.getCreationDate())) {
          throw notModified(options.getSourcePreconditions());
        }
      } catch (PreconditionFailedException e) {
        throw new PreconditionFailedException(RENAME_SOURCE_PREFIX + e.getCondition().toLowerCase(Locale.ROOT));
      }

      ObjectMetadata destination = bucketMetadata.getObjectMetadataRef(key).map(ref -> ref.get()).orElse(null);
      ObjectPreconditions preconditions = options.getPreconditions();
      PreconditionAssertions.assertWritePreconditionsHold(preconditions, key, destination);
      if (Objects.nonNull(destination) && PreconditionAssertions.assertReadPreconditionsHold(preconditions,
          destination.getLatest().getEtag(), destination.getLatest().getCreationDate())) {
        throw notModified(preconditions);
      }
      if (sourceKey.equals(key)) {
        return;
      }

      // The content now belongs to the destination, so removing the source deletes nothing but its metadata.
      bucketMetadata.removeObjectMetadata(sourceKey);
      PutObjectAns ans = PutObjectService.addVersion(bucketMetadata, storage(), key, renamed);
      publishChange(S3Change.objectDeleted("RenameObject", bucketName, sourceKey, null, false));
      publishChange(S3Change.objectVersion(S3ChangeType.OBJECT_CREATED, "RenameObject", bucketName, key,
          ans.getVersionId(), ans.getSize(), ans.getEtag()));
    });
  }

  /**
   * The failure of a condition that answers a read with {@code 304 Not Modified}, which fails a rename instead.
   */
  private static PreconditionFailedException notModified(ObjectPreconditions preconditions) {
    return new PreconditionFailedException(Objects.nonNull(preconditions.getIfNoneMatch())
        ? PreconditionAssertions.IF_NONE_MATCH : PreconditionAssertions.IF_MODIFIED_SINCE);
  }

}
