package com.robothy.s3.core.service;

import com.robothy.s3.core.assertions.BucketAssertions;
import com.robothy.s3.core.assertions.CustomerEncryptionAssertions;
import com.robothy.s3.core.assertions.ObjectLockAssertions;
import com.robothy.s3.core.assertions.PreconditionAssertions;
import com.robothy.s3.core.event.S3Change;
import com.robothy.s3.core.event.S3ChangeType;
import com.robothy.s3.core.exception.LocalS3BadDigestException;
import com.robothy.s3.core.exception.LocalS3InvalidArgumentException;
import com.robothy.s3.core.exception.LocalS3RequestException;
import com.robothy.s3.core.exception.S3ErrorCode;
import com.robothy.s3.core.model.answers.PutObjectAns;
import com.robothy.s3.core.model.internal.BucketMetadata;
import com.robothy.s3.core.model.internal.ObjectChecksum;
import com.robothy.s3.core.model.internal.ObjectMetadata;
import com.robothy.s3.core.model.internal.VersionedObjectMetadata;
import com.robothy.s3.core.model.request.ObjectPreconditions;
import com.robothy.s3.core.model.request.PutObjectOptions;
import com.robothy.s3.core.model.request.RequestChecksum;
import com.robothy.s3.core.storage.Storage;
import com.robothy.s3.core.util.Checksums;
import com.robothy.s3.core.util.ObjectContentUtils;
import com.robothy.s3.core.util.IdUtils;

import com.robothy.s3.core.util.S3ObjectUtils;
import com.robothy.s3.datatypes.enums.CheckSumAlgorithm;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.Base64;
import java.util.HexFormat;
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
   * object can't be added, the stored content is deleted. The change is delivered to the listeners once the object is
   * committed and this cleanup is out of the way, so that a listener that fails can't have the content of a committed
   * object deleted.
   *
   * @param bucketName the bucket name.
   * @param key the object key.
   * @param options the object content and metadata.
   * @return result of the put object operation.
   */
  default PutObjectAns putObject(String bucketName, String key, PutObjectOptions options) {
    // Reject a missing bucket before storing the content; commitPutObject checks it again under the lock.
    BucketAssertions.assertBucketExists(localS3Metadata(), bucketName);
    ObjectLockAssertions.assertRequestedObjectLockIsValid(options.getObjectLock(), System.currentTimeMillis());
    if (Objects.nonNull(options.getWriteOffsetBytes())) {
      return appendObject(bucketName, key, options);
    }

    RequestChecksum checksum = options.getChecksum();
    StoredContent content = storeContent(options.getContent(), options.getContentFile(), options.getHeapContent(),
        Objects.isNull(checksum) ? null : checksum.algorithm());
    Long fileId = content.fileId();
    return deliverChangesAfter(() -> {
      try {
        VersionedObjectMetadata versionedObjectMetadata = new VersionedObjectMetadata();
        versionedObjectMetadata.setCreationDate(System.currentTimeMillis());
        versionedObjectMetadata.setContentType(options.getContentType());
        versionedObjectMetadata.setSystemMetadata(options.getSystemMetadata());
        // The length of the content that was stored, which the length declared by the request may not match.
        versionedObjectMetadata.setSize(content.size());
        if (Objects.nonNull(options.getUserMetadata())) {
          versionedObjectMetadata.setUserMetadata(options.getUserMetadata());
        }
        versionedObjectMetadata.setFileId(fileId);
        versionedObjectMetadata.setEtag(content.md5());
        checkRequestingMd5Header(options, versionedObjectMetadata.getEtag());
        if (Objects.nonNull(checksum)) {
          Checksums.verify(checksum, content.checksum());
          versionedObjectMetadata.setChecksum(
              ObjectChecksum.fullObject(checksum.algorithm(), Checksums.encode(content.checksum())));
        }
        options.getTagging().ifPresent(versionedObjectMetadata::setTagging);
        versionedObjectMetadata.setObjectLock(options.getObjectLock());
        versionedObjectMetadata.setCustomerEncryption(options.getCustomerEncryption());
        versionedObjectMetadata.setServerSideEncryption(options.getServerSideEncryption());

        // Its change is delivered after this block, so a listener that fails doesn't get here.
        return commitPutObject(bucketName, key, versionedObjectMetadata, options.getPreconditions(),
            options.getOperation());
      } catch (Throwable e) {
        discardStoredContent(fileId, e);
        throw e;
      }
    });
  }

  /**
   * Append content to an object, like the {@code PutObject} of an S3 Express One Zone directory bucket that sends
   * {@code x-amz-write-offset-bytes}: the offset must be the size of the object the key holds, or {@code 0} if it holds
   * none, which creates the object.
   *
   * <p>The object isn't changed in place: a new version is stored whose content is the content of the object followed
   * by the content of the request, so that a reader of the object never sees half an append, and whose metadata, e.g.
   * the content type, is the one of the object. Its entity tag is the MD5 digest of the whole content, and its
   * checksum, if the request or the object has an algorithm, the full object checksum of the whole content. A
   * {@code Content-MD5} or a checksum of the request is verified against the appended content. The object is read, and
   * the new content stored, without a lock; if another request changed the object in the meantime, the append fails
   * with {@code InvalidWriteOffset} rather than losing that change.
   *
   * @param bucketName the bucket name.
   * @param key the object key.
   * @param options the content to append, and the offset to append it at.
   * @return result of the put object operation, whose size is the size of the whole object.
   * @throws LocalS3RequestException {@code InvalidWriteOffset} if the offset isn't the size of the object.
   */
  private PutObjectAns appendObject(String bucketName, String key, PutObjectOptions options) {
    long offset = options.getWriteOffsetBytes();
    if (offset < 0) {
      throw new LocalS3InvalidArgumentException("x-amz-write-offset-bytes", String.valueOf(offset),
          "The write offset must not be negative.");
    }
    // The object that is appended to, with its content open unless there is none: read under the read lock, like the
    // source of a copy, and read without it.
    record AppendTarget(VersionedObjectMetadata version, InputStream content) {
    }
    AppendTarget target = withBucketReadLock(bucketName, () -> {
      BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucketName);
      VersionedObjectMetadata current = currentVersion(bucketMetadata, key);
      if (offset != (Objects.isNull(current) ? 0 : current.getSize())) {
        throw new LocalS3RequestException(S3ErrorCode.InvalidWriteOffset);
      }
      if (Objects.isNull(current)) {
        return new AppendTarget(null, null);
      }
      CustomerEncryptionAssertions.assertKeyProvided(current.getCustomerEncryption(), options.getCustomerEncryption());
      return new AppendTarget(current, ObjectContentUtils.open(storage(), current));
    });
    VersionedObjectMetadata appendedTo = target.version();

    RequestChecksum requestChecksum = options.getChecksum();
    Checksums.Calculator appendedChecksum = Objects.isNull(requestChecksum) ? null
        : Checksums.calculator(requestChecksum.algorithm());
    S3ObjectUtils.MeasuredInputStream appended;
    try {
      InputStream content = Objects.nonNull(options.getContent()) ? options.getContent()
          : Objects.nonNull(options.getContentFile()) ? Files.newInputStream(options.getContentFile())
          : options.getHeapContent().newInputStream();
      appended = S3ObjectUtils.measuringStream(Objects.isNull(appendedChecksum) ? content
          : Checksums.checksumStream(content, appendedChecksum));
    } catch (IOException e) {
      UncheckedIOException failure = new UncheckedIOException("Failed to read the content to append.", e);
      if (Objects.nonNull(target.content())) {
        try {
          target.content().close();
        } catch (IOException closeFailure) {
          failure.addSuppressed(closeFailure);
        }
      }
      throw failure;
    }
    CheckSumAlgorithm algorithm = Objects.nonNull(requestChecksum) ? requestChecksum.algorithm()
        : Optional.ofNullable(appendedTo).map(VersionedObjectMetadata::getChecksum).map(ObjectChecksum::getAlgorithm)
        .orElse(null);
    StoredContent content = storeContent(Objects.isNull(target.content()) ? appended
        : new SequenceInputStream(target.content(), appended), null, algorithm);
    Long fileId = content.fileId();
    return deliverChangesAfter(() -> {
      try {
        checkRequestingMd5Header(options, appended.etag());
        if (Objects.nonNull(requestChecksum)) {
          Checksums.verify(requestChecksum, appendedChecksum.digest());
        }
        VersionedObjectMetadata version = new VersionedObjectMetadata();
        version.setCreationDate(System.currentTimeMillis());
        if (Objects.isNull(appendedTo)) {
          version.setContentType(options.getContentType());
          version.setSystemMetadata(options.getSystemMetadata());
          if (Objects.nonNull(options.getUserMetadata())) {
            version.setUserMetadata(options.getUserMetadata());
          }
          options.getTagging().ifPresent(version::setTagging);
          version.setCustomerEncryption(options.getCustomerEncryption());
          version.setServerSideEncryption(options.getServerSideEncryption());
        } else {
          version.setContentType(appendedTo.getContentType());
          version.setSystemMetadata(appendedTo.getSystemMetadata());
          version.setUserMetadata(appendedTo.getUserMetadata());
          appendedTo.getTagging().ifPresent(version::setTagging);
          version.setCustomerEncryption(appendedTo.getCustomerEncryption());
          version.setServerSideEncryption(appendedTo.getServerSideEncryption());
        }
        version.setObjectLock(options.getObjectLock());
        version.setSize(content.size());
        version.setFileId(fileId);
        version.setEtag(content.md5());
        if (Objects.nonNull(algorithm)) {
          version.setChecksum(ObjectChecksum.fullObject(algorithm, Checksums.encode(content.checksum())));
        }

        return changeBucket(bucketName, () -> {
          BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucketName);
          if (!isSameVersion(currentVersion(bucketMetadata, key), appendedTo)) {
            throw new LocalS3RequestException(S3ErrorCode.InvalidWriteOffset);
          }
          PreconditionAssertions.assertWritePreconditionsHold(options.getPreconditions(), key,
              bucketMetadata.getObjectMetadata(key).orElse(null));
          PutObjectAns ans = addVersion(bucketMetadata, storage(), key, version);
          publishChange(S3Change.objectVersion(S3ChangeType.OBJECT_CREATED, options.getOperation(), bucketName, key,
              ans.getVersionId(), ans.getSize(), ans.getEtag()));
          return ans;
        });
      } catch (Throwable e) {
        discardStoredContent(fileId, e);
        throw e;
      }
    });
  }

  /**
   * The current version of an object.
   *
   * @return the current version; {@code null} if the key holds no object, or its current version is a delete marker.
   */
  private static VersionedObjectMetadata currentVersion(BucketMetadata bucketMetadata, String key) {
    return bucketMetadata.getObjectMetadataRef(key)
        .map(ref -> ref.get().getLatest())
        .filter(version -> !version.isDeleted())
        .orElse(null);
  }

  /**
   * Whether two versions are the same one, which may have been read into two instances.
   */
  private static boolean isSameVersion(VersionedObjectMetadata left, VersionedObjectMetadata right) {
    if (Objects.isNull(left) || Objects.isNull(right)) {
      return left == right;
    }
    return left.getCreationDate() == right.getCreationDate() && left.getSize() == right.getSize()
        && Objects.equals(left.getEtag(), right.getEtag());
  }

  /**
   * Add a new version of an object whose content is already stored, unconditionally.
   *
   * @param bucketName the bucket name.
   * @param key the object key.
   * @param versionedObjectMetadata the metadata of the new version, referencing the stored content.
   * @return result of the put object operation.
   */
  default PutObjectAns commitPutObject(String bucketName, String key, VersionedObjectMetadata versionedObjectMetadata) {
    return changeBucket(bucketName, () -> {
      return commitPutObject(bucketName, key, versionedObjectMetadata, ObjectPreconditions.none());
    });
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
  default PutObjectAns commitPutObject(String bucketName, String key,
                                       VersionedObjectMetadata versionedObjectMetadata,
                                       ObjectPreconditions preconditions) {
    return commitPutObject(bucketName, key, versionedObjectMetadata, preconditions, PutObjectOptions.PUT_OBJECT);
  }

  /**
   * Add a new version of an object whose content is already stored, like
   * {@linkplain #commitPutObject(String, String, VersionedObjectMetadata, ObjectPreconditions)}, and name the change it
   * publishes after the operation that stored the object.
   *
   * @param bucketName the bucket name.
   * @param key the object key.
   * @param versionedObjectMetadata the metadata of the new version, referencing the stored content.
   * @param preconditions the conditions that the object the key holds must satisfy.
   * @param operation the S3 operation that stores the object, e.g. {@code PutObject} or {@code PostObject}.
   * @return result of the put object operation.
   */
  default PutObjectAns commitPutObject(String bucketName, String key,
                                       VersionedObjectMetadata versionedObjectMetadata,
                                       ObjectPreconditions preconditions, String operation) {
    return changeBucket(bucketName, () -> {
      BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucketName);
      PreconditionAssertions.assertWritePreconditionsHold(preconditions, key,
          bucketMetadata.getObjectMetadata(key).orElse(null));
      PutObjectAns ans = addVersion(bucketMetadata, storage(), key, versionedObjectMetadata);
      publishChange(S3Change.objectVersion(S3ChangeType.OBJECT_CREATED, operation, bucketName, key,
          ans.getVersionId(), ans.getSize(), ans.getEtag()));
      return ans;
    });
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
    // Rejects Object Lock settings for a bucket without Object Lock before anything changes.
    ObjectLockAssertions.applyBucketObjectLock(bucketMetadata, versionedObjectMetadata);
    applyBucketDefaultEncryption(bucketMetadata, versionedObjectMetadata);
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
        VersionedObjectMetadata previousVersion = objectMetadata.removeVersionedObjectMetadata(lastVirtualVersion);
        // Nothing is deleted for a delete marker.
        ObjectContentUtils.delete(storage, previousVersion);

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
        .checksum(versionedObjectMetadata.getChecksum())
        .serverSideEncryption(versionedObjectMetadata.getServerSideEncryption())
        .build();
  }

  /**
   * Give a version that is stored without an encryption the default encryption of its bucket, like Amazon S3 does. A
   * version stored with a customer-provided key keeps that key only.
   */
  private static void applyBucketDefaultEncryption(BucketMetadata bucketMetadata, VersionedObjectMetadata version) {
    if (Objects.isNull(version.getServerSideEncryption()) && Objects.isNull(version.getCustomerEncryption())) {
      version.setServerSideEncryption(bucketMetadata.getDefaultEncryption());
    }
  }

  private static void checkRequestingMd5Header(PutObjectOptions options, String etag) {
    // Validate Content-MD5 header if present.
    if (Objects.nonNull(options.getContentMd5())) {
      byte[] md5Bytes;
      try {
        md5Bytes = HexFormat.of().parseHex(etag);
      } catch (IllegalArgumentException e) {
        throw new LocalS3BadDigestException("Invalid Content-MD5 header.");
      }
      String computedBase64 = Base64.getEncoder().encodeToString(md5Bytes);
      if (!computedBase64.equals(options.getContentMd5())) {
        throw new LocalS3BadDigestException("The Content-MD5 you specified did not match what we received.");
      }
    }
  }

}
