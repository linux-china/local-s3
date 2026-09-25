package com.robothy.s3.core.service;

import com.robothy.s3.core.assertions.BucketAssertions;
import com.robothy.s3.core.assertions.CustomerEncryptionAssertions;
import com.robothy.s3.core.assertions.ObjectAssertions;
import com.robothy.s3.core.assertions.PreconditionAssertions;
import com.robothy.s3.core.assertions.UploadAssertions;
import com.robothy.s3.core.assertions.VersionedObjectAssertions;
import com.robothy.s3.core.exception.LocalS3Exception;
import com.robothy.s3.core.exception.LocalS3InvalidArgumentException;
import com.robothy.s3.core.exception.LocalS3RequestException;
import com.robothy.s3.core.exception.ObjectNotExistException;
import com.robothy.s3.core.exception.PreconditionFailedException;
import com.robothy.s3.core.exception.S3ErrorCode;
import com.robothy.s3.core.exception.VersionedObjectNotExistException;
import com.robothy.s3.core.model.answers.GetObjectAns;
import com.robothy.s3.core.model.internal.BucketMetadata;
import com.robothy.s3.core.model.internal.CustomerEncryption;
import com.robothy.s3.core.model.internal.ObjectChecksum;
import com.robothy.s3.core.model.internal.ObjectMetadata;
import com.robothy.s3.core.model.internal.ObjectPartMetadata;
import com.robothy.s3.core.model.internal.VersionedObjectMetadata;
import com.robothy.s3.core.model.request.GetObjectOptions;
import com.robothy.s3.core.model.request.ObjectPreconditions;
import com.robothy.s3.core.model.request.Range;
import com.robothy.s3.core.storage.Storage;
import com.robothy.s3.core.util.ObjectContentUtils;
import java.util.List;
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

    return content(storage, latestObject, metadataOnly, options)
        .bucketName(bucketName)
        .key(key)
        .contentType(latestObject.getContentType())
        .systemMetadata(latestObject.getSystemMetadata())
        .lastModified(latestObject.getCreationDate())
        .etag(latestObject.getEtag())
        .userMetadata(latestObject.getUserMetadata())
        .taggingCount(latestObject.getTagging().map(tagging -> tagging.length).orElse(0))
        .tagging(latestObject.getTagging().orElse(null))
        .parts(latestObject.getParts().orElse(null))
        .objectLock(latestObject.getObjectLock())
        .customerEncryption(latestObject.getCustomerEncryption())
        .serverSideEncryption(latestObject.getServerSideEncryption())
        .restoreExpiryDate(RestoreObjectService.activeRestoreExpiryDate(latestObject))
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
        throw ObjectNotExistException.deleteMarker(key, returnedVersionId);
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

      return content(storage, versionedObjectMetadata, metadataOnly, options)
          .bucketName(bucketName)
          .key(key)
          .versionId(returnedVersionId)
          .contentType(versionedObjectMetadata.getContentType())
          .systemMetadata(versionedObjectMetadata.getSystemMetadata())
          .lastModified(versionedObjectMetadata.getCreationDate())
          .etag(versionedObjectMetadata.getEtag())
          .taggingCount(versionedObjectMetadata.getTagging().map(tagging -> tagging.length).orElse(0))
          .tagging(versionedObjectMetadata.getTagging().orElse(null))
          .userMetadata(versionedObjectMetadata.getUserMetadata())
          .parts(versionedObjectMetadata.getParts().orElse(null))
          .objectLock(versionedObjectMetadata.getObjectLock())
          .customerEncryption(versionedObjectMetadata.getCustomerEncryption())
          .serverSideEncryption(versionedObjectMetadata.getServerSideEncryption())
          .restoreExpiryDate(RestoreObjectService.activeRestoreExpiryDate(versionedObjectMetadata))
          .build();
    }
  }

  /**
   * The content of a read of a version: all of it, the {@code Range} of the request, or the part that the
   * {@code partNumber} of the request names. The answer carries the size, the content unless {@code metadataOnly},
   * the {@code Content-Range}, the checksum, and the number of parts of a read of a part.
   *
   * <p>A part is counted from 1 in the order of the content, like the {@code x-amz-mp-parts-count} header that
   * answers it counts them, so that a client that reads parts 1 to the count reads the whole object; the part
   * numbers that the parts were uploaded with don't have to be consecutive. An object that wasn't uploaded in parts
   * has a single part, which is all of its content, like Amazon S3 answers.
   *
   * @throws LocalS3RequestException {@code InvalidRequest} if the request carries both a range and a part number.
   * @throws LocalS3Exception {@code InvalidPartNumber} if the object has no part of the part number.
   */
  private static GetObjectAns.GetObjectAnsBuilder content(Storage storage, VersionedObjectMetadata version,
                                                          boolean metadataOnly, GetObjectOptions options) {
    long fullSize = version.getSize();
    GetObjectAns.GetObjectAnsBuilder answer = GetObjectAns.builder();
    long start;
    long contentLength;
    ObjectChecksum checksum;
    if (options.getPartNumber().isPresent()) {
      if (options.getRange().isPresent()) {
        throw new LocalS3RequestException(S3ErrorCode.InvalidRequest,
            "Cannot specify both Range header and partNumber query parameter");
      }
      int partNumber = UploadAssertions.assertPartNumberIsValid(options.getPartNumber().get());
      List<ObjectPartMetadata> parts = version.getParts().filter(list -> !list.isEmpty()).orElse(null);
      if (Objects.isNull(parts)) {
        if (partNumber != 1) {
          throw new LocalS3RequestException(S3ErrorCode.InvalidPartNumber);
        }
        start = 0;
        contentLength = fullSize;
        checksum = version.getChecksum();
      } else {
        if (partNumber > parts.size()) {
          throw new LocalS3RequestException(S3ErrorCode.InvalidPartNumber);
        }
        start = 0;
        for (int i = 0; i < partNumber - 1; i++) {
          start += parts.get(i).getSize();
        }
        ObjectPartMetadata part = parts.get(partNumber - 1);
        contentLength = part.getSize();
        // The checksum of the part, which is what the content read is, when the object has one of its algorithm.
        // Like Amazon S3, it is reported with the type of the checksum of the object, e.g. COMPOSITE for SHA-256.
        ObjectChecksum objectChecksum = version.getChecksum();
        ObjectChecksum partChecksum = part.getChecksum();
        checksum = Objects.nonNull(objectChecksum) && Objects.nonNull(partChecksum)
            && objectChecksum.getAlgorithm() == partChecksum.getAlgorithm()
            ? new ObjectChecksum(partChecksum.getAlgorithm(), objectChecksum.getType(), partChecksum.getValue()) : null;
        answer.partsCount(parts.size());
      }
      // A read of a part is a partial content, even of the only part of an object; an empty part has no range.
      if (contentLength > 0) {
        answer.contentRange("bytes " + start + "-" + (start + contentLength - 1) + "/" + fullSize);
      }
    } else if (options.getRange().isPresent()) {
      long[] range = options.getRange().get().resolve(fullSize);
      start = range[0];
      contentLength = range[1] - start + 1;
      answer.contentRange("bytes " + start + "-" + range[1] + "/" + fullSize);
      // The checksum is the one of the whole content, not of a range of it.
      checksum = null;
    } else {
      start = 0;
      contentLength = fullSize;
      checksum = version.getChecksum();
    }

    if (!metadataOnly) {
      answer.content(start == 0 && contentLength == fullSize
          ? ObjectContentUtils.open(storage, version)
          : ObjectContentUtils.open(storage, version, start, contentLength));
    }
    return answer.size(contentLength).checksum(checksum);
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
