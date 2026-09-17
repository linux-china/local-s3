package com.robothy.s3.core.service;

import com.robothy.s3.core.assertions.BucketAssertions;
import com.robothy.s3.core.assertions.CustomerEncryptionAssertions;
import com.robothy.s3.core.assertions.ObjectAssertions;
import com.robothy.s3.core.assertions.PreconditionAssertions;
import com.robothy.s3.core.assertions.VersionedObjectAssertions;
import com.robothy.s3.core.exception.LocalS3InvalidArgumentException;
import com.robothy.s3.core.exception.ObjectNotExistException;
import com.robothy.s3.core.exception.PreconditionFailedException;
import com.robothy.s3.core.exception.VersionedObjectNotExistException;
import com.robothy.s3.core.model.answers.GetObjectAns;
import com.robothy.s3.core.model.internal.BucketMetadata;
import com.robothy.s3.core.model.internal.CustomerEncryption;
import com.robothy.s3.core.model.internal.ObjectMetadata;
import com.robothy.s3.core.model.internal.VersionedObjectMetadata;
import com.robothy.s3.core.model.request.GetObjectOptions;
import com.robothy.s3.core.model.request.ObjectPreconditions;
import com.robothy.s3.core.model.request.Range;
import com.robothy.s3.core.storage.Storage;
import com.robothy.s3.core.util.ObjectContentUtils;
import java.io.InputStream;
import java.util.Objects;
import java.util.Optional;

public interface GetObjectService extends StorageApplicable, LocalS3MetadataApplicable {

  /**
   * Get object.
   */
  default GetObjectAns getObject(String bucketName, String key, GetObjectOptions options) {
    return withBucketReadLock(bucketName, () -> {
      BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucketName);
      if (Objects.isNull(bucketMetadata.getVersioningEnabled())) {
        return getObjectFromUnVersionedBucket(bucketMetadata, storage(), bucketName, key, false, options);
      }
      return getObject(bucketMetadata, storage(), bucketName, key, false, options);
    });
  }

  /**
   * Resolve the source object of a copy, i.e. of {@code CopyObject} or {@code UploadPartCopy}, under the read lock of
   * its bucket, and open its content, which the caller reads without the lock. The conditions of the source object
   * are evaluated before its content is opened; see
   * {@linkplain PreconditionAssertions#copySourceConditionFailed(String)} for how they differ from the ones of a read.
   *
   * @param bucketName the bucket of the source object.
   * @param key the key of the source object.
   * @param versionId the version to copy; {@code null} for the current one.
   * @param range the range of the source object to copy; {@code null} for all of it.
   * @param preconditions the {@code x-amz-copy-source-if-*} conditions; {@code null} or
   *     {@linkplain ObjectPreconditions#none()} for none.
   * @return the source object, with its content open unless it is a delete marker.
   * @throws PreconditionFailedException if a condition of the source object didn't hold.
   */
  default GetObjectAns getCopySource(String bucketName, String key, String versionId, Range range,
                                     ObjectPreconditions preconditions) {
    return getCopySource(bucketName, key, versionId, range, preconditions, null);
  }

  /**
   * Resolve the source object of a copy like {@linkplain #getCopySource(String, String, String, Range,
   * ObjectPreconditions)}, providing the customer key that the source object was stored with.
   *
   * @param sourceCustomerEncryption the {@code x-amz-copy-source-server-side-encryption-customer-*} key of the request;
   *     {@code null} for none.
   * @return the source object, with its content open unless it is a delete marker.
   */
  default GetObjectAns getCopySource(String bucketName, String key, String versionId, Range range,
                                     ObjectPreconditions preconditions, CustomerEncryption sourceCustomerEncryption) {
    ObjectPreconditions conditions = Objects.requireNonNullElseGet(preconditions, ObjectPreconditions::none);
    GetObjectAns source;
    try {
      source = getObject(bucketName, key, GetObjectOptions.builder()
          .versionId(versionId)
          .range(range)
          .preconditions(conditions)
          .customerEncryption(sourceCustomerEncryption)
          .build());
    } catch (PreconditionFailedException e) {
      throw PreconditionAssertions.copySourceConditionFailed(e.getCondition());
    }
    if (source.isNotModified()) {
      // A not modified source has no content open.
      throw PreconditionAssertions.copySourceConditionFailed(Objects.nonNull(conditions.getIfNoneMatch())
          ? PreconditionAssertions.IF_NONE_MATCH : PreconditionAssertions.IF_MODIFIED_SINCE);
    }
    return source;
  }

  static GetObjectAns getObjectFromUnVersionedBucket(BucketMetadata bucketMetadata, Storage storage,
                                                     String bucketName, String key, boolean metadataOnly, GetObjectOptions options) {
    ObjectMetadata objectMetadata = ObjectAssertions.assertObjectExists(bucketMetadata, key);
    if (options.getVersionId().isPresent() && !ObjectMetadata.NULL_VERSION.equals(options.getVersionId().get())) {
      throw new LocalS3InvalidArgumentException("versionId", options.getVersionId().get());
    }

    VersionedObjectMetadata latestObject = objectMetadata.getLatest();
    CustomerEncryptionAssertions.assertKeyProvided(latestObject.getCustomerEncryption(),
        options.getCustomerEncryption());
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
        content = ObjectContentUtils.open(storage, latestObject, start, contentLength);
      }
    } else if (!metadataOnly) {
      content = ObjectContentUtils.open(storage, latestObject);
    }

    return GetObjectAns.builder()
        .bucketName(bucketName)
        .key(key)
        .contentType(latestObject.getContentType())
        .systemMetadata(latestObject.getSystemMetadata())
        .lastModified(latestObject.getCreationDate())
        .size(contentLength)
        .content(content)
        .etag(latestObject.getEtag())
        .contentRange(contentRange)
        .userMetadata(latestObject.getUserMetadata())
        .taggingCount(latestObject.getTagging().map(tagging -> tagging.length).orElse(0))
        .tagging(latestObject.getTagging().orElse(null))
        .parts(latestObject.getParts().orElse(null))
        // The checksum is the one of the whole content, not of a range of it.
        .checksum(Objects.isNull(contentRange) ? latestObject.getChecksum() : null)
        .objectLock(latestObject.getObjectLock())
        .customerEncryption(latestObject.getCustomerEncryption())
        .build();
  }

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
      CustomerEncryptionAssertions.assertKeyProvided(versionedObjectMetadata.getCustomerEncryption(),
          options.getCustomerEncryption());
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
          content = ObjectContentUtils.open(storage, versionedObjectMetadata, start, contentLength);
        }
      } else if (!metadataOnly) {
        content = ObjectContentUtils.open(storage, versionedObjectMetadata);
      }

      return GetObjectAns.builder()
          .bucketName(bucketName)
          .key(key)
          .versionId(returnedVersionId)
          .contentType(versionedObjectMetadata.getContentType())
          .systemMetadata(versionedObjectMetadata.getSystemMetadata())
          .lastModified(versionedObjectMetadata.getCreationDate())
          .size(contentLength)
          .content(content)
          .etag(versionedObjectMetadata.getEtag())
          .contentRange(contentRange)
          .taggingCount(versionedObjectMetadata.getTagging().map(tagging -> tagging.length).orElse(0))
          .tagging(versionedObjectMetadata.getTagging().orElse(null))
          .userMetadata(versionedObjectMetadata.getUserMetadata())
          .parts(versionedObjectMetadata.getParts().orElse(null))
          // The checksum is the one of the whole content, not of a range of it.
          .checksum(Objects.isNull(contentRange) ? versionedObjectMetadata.getChecksum() : null)
          .objectLock(versionedObjectMetadata.getObjectLock())
          .customerEncryption(versionedObjectMetadata.getCustomerEncryption())
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
  default GetObjectAns headObject(String bucketName, String key, GetObjectOptions options) {
    return withBucketReadLock(bucketName, () -> {
      BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucketName);
      if (Objects.isNull(bucketMetadata.getVersioningEnabled())) {
        return getObjectFromUnVersionedBucket(bucketMetadata, storage(), bucketName, key, true, options);
      }

      return getObject(bucketMetadata, storage(), bucketName, key, true, options);
    });
  }

}
