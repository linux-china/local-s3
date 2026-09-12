package com.robothy.s3.core.service;

import com.robothy.s3.core.annotations.BucketReadLock;
import com.robothy.s3.core.assertions.BucketAssertions;
import com.robothy.s3.core.assertions.ObjectAssertions;
import com.robothy.s3.core.assertions.PreconditionAssertions;
import com.robothy.s3.core.assertions.VersionedObjectAssertions;
import com.robothy.s3.core.exception.LocalS3InvalidArgumentException;
import com.robothy.s3.core.exception.ObjectNotExistException;
import com.robothy.s3.core.exception.VersionedObjectNotExistException;
import com.robothy.s3.core.model.answers.GetObjectAns;
import com.robothy.s3.core.model.internal.BucketMetadata;
import com.robothy.s3.core.model.internal.ObjectMetadata;
import com.robothy.s3.core.model.internal.VersionedObjectMetadata;
import com.robothy.s3.core.model.request.GetObjectOptions;
import com.robothy.s3.core.storage.Storage;
import com.robothy.s3.core.util.RangeUtils;
import java.io.InputStream;
import java.util.Objects;
import java.util.Optional;

public interface GetObjectService extends StorageApplicable, LocalS3MetadataApplicable {

  /**
   * Get object.
   */
  @BucketReadLock
  default GetObjectAns getObject(String bucketName, String key, GetObjectOptions options) {
    BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucketName);
    if (Objects.isNull(bucketMetadata.getVersioningEnabled())) {
      return getObjectFromUnVersionedBucket(bucketMetadata, storage(), bucketName, key, false, options);
    }
    return getObject(bucketMetadata, storage(), bucketName, key, false, options);
  }

  static GetObjectAns getObjectFromUnVersionedBucket(BucketMetadata bucketMetadata, Storage storage,
                                                     String bucketName, String key, boolean metadataOnly, GetObjectOptions options) {
    ObjectMetadata objectMetadata = ObjectAssertions.assertObjectExists(bucketMetadata, key);
    if (options.getVersionId().isPresent() && !ObjectMetadata.NULL_VERSION.equals(options.getVersionId().get())) {
      throw new LocalS3InvalidArgumentException("versionId", options.getVersionId().get());
    }

    VersionedObjectMetadata latestObject = objectMetadata.getLatest();
    if (PreconditionAssertions.assertReadPreconditionsHold(options.getPreconditions(),
        latestObject.getEtag(), latestObject.getCreationDate())) {
      return notModified(bucketName, key, null, latestObject);
    }

    long fullSize = latestObject.getSize();
    long contentLength = fullSize;
    String contentRange = null;
    InputStream content = null;

    if (options.getRange().isPresent()) {
      long[] range = options.getRange().get().resolve(fullSize);
      long start = range[0], end = range[1];
      contentLength = end - start + 1;
      contentRange = "bytes " + start + "-" + end + "/" + fullSize;
      if (!metadataOnly) {
        content = RangeUtils.applyRange(storage.getInputStream(latestObject.getFileId()), start, contentLength);
      }
    } else if (!metadataOnly) {
      content = storage.getInputStream(latestObject.getFileId());
    }

    return GetObjectAns.builder()
        .bucketName(bucketName)
        .key(key)
        .contentType(latestObject.getContentType())
        .lastModified(latestObject.getCreationDate())
        .size(contentLength)
        .content(content)
        .etag(latestObject.getEtag())
        .contentRange(contentRange)
        .userMetadata(latestObject.getUserMetadata())
        .taggingCount(latestObject.getTagging().map(tagging -> tagging.length).orElse(0))
        .tagging(latestObject.getTagging().orElse(null))
        .build();
  }

  // Using static to make the target compatible with Java8
  static GetObjectAns getObject(BucketMetadata bucketMetadata, Storage storage,
                                String bucketName, String key, boolean metadataOnly, GetObjectOptions options) {

    ObjectMetadata objectMetadata = ObjectAssertions.assertObjectExists(bucketMetadata, key);
    Optional<String> versionIdOpt = options.getVersionId();
    VersionedObjectMetadata versionedObjectMetadata;

    String returnedVersionId;
    if (versionIdOpt.isPresent()) {
      if (ObjectMetadata.NULL_VERSION.equals(versionIdOpt.get())) {
        versionedObjectMetadata = VersionedObjectAssertions.assertVirtualVersionExist(objectMetadata);
      } else {
        // Cannot access an object with virtual version.
        if (objectMetadata.getVirtualVersion().map(versionIdOpt.get()::equals).orElse(false)) {
          throw new VersionedObjectNotExistException(key, versionIdOpt.get());
        }
        versionedObjectMetadata = VersionedObjectAssertions
            .assertVersionedObjectExist(objectMetadata, versionIdOpt.get());
      }
      returnedVersionId = versionIdOpt.get();
    } else {
      versionedObjectMetadata = objectMetadata.getLatest();

      // If the latest version is virtual version, then map the virtual version to "null".
      if (objectMetadata.getVirtualVersion()
          .map(virtualVersion -> objectMetadata.getLatestVersion().equals(virtualVersion)).orElse(false)) {
        returnedVersionId = ObjectMetadata.NULL_VERSION;
      } else {
        returnedVersionId = objectMetadata.getLatestVersion();
      }
    }

    if (versionedObjectMetadata.isDeleted()) {

      // The version ID is not specified and the latest version is a delete-marker.
      if (!versionIdOpt.isPresent()) {
        throw new ObjectNotExistException(key);
      }

      return GetObjectAns.builder()
          .bucketName(bucketName)
          .key(key)
          .deleteMarker(true)
          .versionId(returnedVersionId)
          .lastModified(versionedObjectMetadata.getCreationDate())
          .build();
    } else {
      if (PreconditionAssertions.assertReadPreconditionsHold(options.getPreconditions(),
          versionedObjectMetadata.getEtag(), versionedObjectMetadata.getCreationDate())) {
        return notModified(bucketName, key, returnedVersionId, versionedObjectMetadata);
      }

      long fullSize = versionedObjectMetadata.getSize();
      long contentLength = fullSize;
      String contentRange = null;
      InputStream content = null;

      if (options.getRange().isPresent()) {
        long[] range = options.getRange().get().resolve(fullSize);
        long start = range[0], end = range[1];
        contentLength = end - start + 1;
        contentRange = "bytes " + start + "-" + end + "/" + fullSize;
        if (!metadataOnly) {
          content = RangeUtils.applyRange(storage.getInputStream(versionedObjectMetadata.getFileId()), start, contentLength);
        }
      } else if (!metadataOnly) {
        content = storage.getInputStream(versionedObjectMetadata.getFileId());
      }

      return GetObjectAns.builder()
          .bucketName(bucketName)
          .key(key)
          .versionId(returnedVersionId)
          .contentType(versionedObjectMetadata.getContentType())
          .lastModified(versionedObjectMetadata.getCreationDate())
          .size(contentLength)
          .content(content)
          .etag(versionedObjectMetadata.getEtag())
          .contentRange(contentRange)
          .taggingCount(versionedObjectMetadata.getTagging().map(tagging -> tagging.length).orElse(0))
          .tagging(versionedObjectMetadata.getTagging().orElse(null))
          .userMetadata(versionedObjectMetadata.getUserMetadata())
          .build();
    }
  }

  /**
   * The answer of a read that the client already holds the object of: no content, but the metadata that
   * identifies the version it holds, which the {@code ETag} and {@code Last-Modified} headers of a
   * {@code 304 Not Modified} response carry, like RFC 9110 requires of a response that omits the content.
   *
   * <p>The preconditions of a read are evaluated before the content is opened, so that an object that the
   * client already holds is never read from the storage.
   */
  private static GetObjectAns notModified(String bucketName, String key, String versionId,
                                          VersionedObjectMetadata versionedObjectMetadata) {
    return GetObjectAns.builder()
        .bucketName(bucketName)
        .key(key)
        .versionId(versionId)
        .notModified(true)
        .contentType(versionedObjectMetadata.getContentType())
        .lastModified(versionedObjectMetadata.getCreationDate())
        .size(versionedObjectMetadata.getSize())
        .etag(versionedObjectMetadata.getEtag())
        .build();
  }

  /**
   * Get metadata of the specified object.
   *
   * @param bucketName the bucket name.
   * @param key the object key.
   * @param options options.
   * @return versioned object with metadata only.
   */
  @BucketReadLock
  default GetObjectAns headObject(String bucketName, String key, GetObjectOptions options) {
    BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucketName);
    if (Objects.isNull(bucketMetadata.getVersioningEnabled())) {
      return getObjectFromUnVersionedBucket(bucketMetadata, storage(), bucketName, key, true, options);
    }

    return getObject(bucketMetadata, storage(), bucketName, key, true, options);
  }

}
