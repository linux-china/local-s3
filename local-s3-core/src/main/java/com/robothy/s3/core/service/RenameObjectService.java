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
 * See {@code docs/semantics.md#appends-and-renames}. Renamed atomically, under the write lock of the bucket, without
 * copying the content.
 */
public interface RenameObjectService extends LocalS3MetadataApplicable, StorageApplicable {

  String RENAME_SOURCE_PREFIX = "x-amz-rename-source-";

  default void renameObject(String bucketName, String key, RenameObjectOptions options) {
    ObjectAssertions.assertObjectKeyIsWritable(key);
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
          ans.getVersionId(), ans.getSize(), ans.getEtag(), renamed.getContentType(), renamed.getUserMetadata()));
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
