package com.robothy.s3.core.service;

import com.robothy.s3.core.annotations.BucketChanged;
import com.robothy.s3.core.annotations.BucketWriteLock;
import com.robothy.s3.core.annotations.CallsThroughProxy;
import com.robothy.s3.core.assertions.BucketAssertions;
import com.robothy.s3.core.assertions.PreconditionAssertions;
import com.robothy.s3.core.exception.LocalS3BadDigestException;
import com.robothy.s3.core.model.answers.PutObjectAns;
import com.robothy.s3.core.model.internal.BucketMetadata;
import com.robothy.s3.core.model.internal.ObjectMetadata;
import com.robothy.s3.core.model.internal.VersionedObjectMetadata;
import com.robothy.s3.core.model.request.ObjectPreconditions;
import com.robothy.s3.core.model.request.PutObjectOptions;
import com.robothy.s3.core.storage.Storage;
import com.robothy.s3.core.util.IdUtils;
import com.robothy.s3.core.util.S3ObjectUtils;
import com.robothy.s3.core.util.S3ObjectUtils.MeasuredInputStream;

import java.util.Base64;
import java.util.Objects;
import java.util.Optional;

/**
 *
 * <ul>
 *   <li>
 *     If bucket versioning is enabled, then create a new {@linkplain VersionedObjectMetadata} instance.
 *   </li>
 *   <li>
 *     If bucket versioning isn't enabled, then create a new {@linkplain VersionedObjectMetadata} instance,
 *     remove the virtual version object if exist, and set the version ID of the created
 *     {@linkplain VersionedObjectMetadata} as virtual version.
 *   </li>
 * </ul>
 *
 *
 */
public interface PutObjectService extends LocalS3MetadataApplicable, StorageApplicable {

  /**
   * Put an object. The content is stored before the bucket is locked, so that a large upload doesn't block
   * the other requests to the bucket; only {@linkplain #commitPutObject} holds the bucket write lock. If the
   * object can't be added, the stored content is deleted.
   *
   * @param bucketName the bucket name.
   * @param key the object key.
   * @param options the object content and metadata.
   * @return result of the put object operation.
   */
  @CallsThroughProxy
  default PutObjectAns putObject(String bucketName, String key, PutObjectOptions options) {
    // Reject a missing bucket before storing the content; commitPutObject checks it again under the lock.
    BucketAssertions.assertBucketExists(localS3Metadata(), bucketName);

    MeasuredInputStream content = S3ObjectUtils.measuringStream(options.getContent());
    Long fileId = storage().put(content);
    try {
      VersionedObjectMetadata versionedObjectMetadata = new VersionedObjectMetadata();
      versionedObjectMetadata.setCreationDate(System.currentTimeMillis());
      versionedObjectMetadata.setContentType(options.getContentType());
      // The length of the content that was stored, which the length declared by the request may not match.
      versionedObjectMetadata.setSize(content.getSize());
      if (Objects.nonNull(options.getUserMetadata())) {
        versionedObjectMetadata.setUserMetadata(options.getUserMetadata());
      }
      versionedObjectMetadata.setFileId(fileId);
      versionedObjectMetadata.setEtag(content.etag());
      checkRequestingMd5Header(options, versionedObjectMetadata.getEtag());
      options.getTagging().ifPresent(versionedObjectMetadata::setTagging);

      return commitPutObject(bucketName, key, versionedObjectMetadata, options.getPreconditions());
    } catch (Throwable e) {
      discardStoredContent(fileId, e);
      throw e;
    }
  }

  /**
   * Add a new version of an object whose content is already stored, unconditionally.
   *
   * @param bucketName the bucket name.
   * @param key the object key.
   * @param versionedObjectMetadata the metadata of the new version, referencing the stored content.
   * @return result of the put object operation.
   */
  @BucketChanged
  @BucketWriteLock
  default PutObjectAns commitPutObject(String bucketName, String key, VersionedObjectMetadata versionedObjectMetadata) {
    return commitPutObject(bucketName, key, versionedObjectMetadata, ObjectPreconditions.none());
  }

  /**
   * Add a new version of an object whose content is already stored, if the object that the key holds
   * satisfies the preconditions of the request. Called by {@linkplain #putObject}.
   *
   * <p>The preconditions are evaluated here rather than before the content is stored, i.e. under the write
   * lock of the bucket that the version is added under. That is what makes an {@code If-Match} put a
   * compare-and-swap and an {@code If-None-Match: *} put a create: no other request can store the object
   * between the evaluation of the condition and the addition of the version. A conditional put that is
   * rejected has stored its content already, which {@linkplain #putObject} then discards.
   *
   * @param bucketName the bucket name.
   * @param key the object key.
   * @param versionedObjectMetadata the metadata of the new version, referencing the stored content.
   * @param preconditions the conditions that the object the key holds must satisfy;
   *     {@linkplain ObjectPreconditions#none()} to add the version unconditionally.
   * @return result of the put object operation.
   */
  @BucketChanged
  @BucketWriteLock
  default PutObjectAns commitPutObject(String bucketName, String key,
                                       VersionedObjectMetadata versionedObjectMetadata,
                                       ObjectPreconditions preconditions) {
    BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucketName);
    PreconditionAssertions.assertWritePreconditionsHold(preconditions, key,
        bucketMetadata.getObjectMetadata(key).orElse(null));
    return addVersion(bucketMetadata, storage(), key, versionedObjectMetadata);
  }

  /**
   * Add a new version of an object whose content is already stored to the metadata of a bucket. The caller
   * holds the write lock of the bucket, e.g. {@linkplain #commitPutObject}, or
   * {@linkplain CompleteMultipartUploadService#commitCompleteMultipartUpload}, which adds the version and
   * removes the completed upload under the same lock.
   *
   * @param bucketMetadata the metadata of the bucket that the object belongs to.
   * @param storage the storage that holds the content of the version.
   * @param key the object key.
   * @param versionedObjectMetadata the metadata of the new version, referencing the stored content.
   * @return result of the put object operation.
   */
  static PutObjectAns addVersion(BucketMetadata bucketMetadata, Storage storage, String key,
                                 VersionedObjectMetadata versionedObjectMetadata) {
    String versionId = IdUtils.defaultGenerator().nextStrId();
    ObjectMetadata objectMetadata;
    if (bucketMetadata.getObjectMetadata(key).isPresent()) {
      objectMetadata = bucketMetadata.getObjectMetadata(key).get();
      objectMetadata.putVersionedObjectMetadata(versionId, versionedObjectMetadata);
    } else {
      objectMetadata = new ObjectMetadata(versionId, versionedObjectMetadata);
      bucketMetadata.putObjectMetadata(key, objectMetadata);
    }

    String returnedVersionId = versionId;
    if (!Boolean.TRUE.equals(bucketMetadata.getVersioningEnabled())) {
      returnedVersionId = Objects.isNull(bucketMetadata.getVersioningEnabled()) ? null : ObjectMetadata.NULL_VERSION;

      Optional<String> virtualVersionOpt = objectMetadata.getVirtualVersion();
      if (virtualVersionOpt.isPresent()) {
        String lastVirtualVersion = virtualVersionOpt.get();
        VersionedObjectMetadata previousVersion = objectMetadata.getVersionedObjectMap().remove(lastVirtualVersion);
        if (Objects.nonNull(previousVersion.getFileId())) { // Not a delete marker.
          storage.delete(previousVersion.getFileId());
        }

        objectMetadata.setVirtualVersion(versionId);
      } else {
        objectMetadata.setVirtualVersion(versionId);
      }
    }

    return PutObjectAns.builder()
        .key(key)
        .versionId(returnedVersionId)
        .creationDate(versionedObjectMetadata.getCreationDate())
        .etag(versionedObjectMetadata.getEtag())
        .size(versionedObjectMetadata.getSize())
        .build();
  }

  private void checkRequestingMd5Header(PutObjectOptions options, String etag) {
    // Validate Content-MD5 header if present.
    if (Objects.nonNull(options.getContentMd5())) {
      try {
        byte[] md5Bytes = org.apache.commons.codec.binary.Hex.decodeHex(etag);
        String computedBase64 = Base64.getEncoder().encodeToString(md5Bytes);
        if (!computedBase64.equals(options.getContentMd5())) {
          throw new LocalS3BadDigestException("The Content-MD5 you specified did not match what we received.");
        }
      } catch (org.apache.commons.codec.DecoderException e) {
        throw new LocalS3BadDigestException("Invalid Content-MD5 header.");
      }
    }
  }

}
