package com.robothy.s3.core.service;

import com.robothy.s3.core.assertions.BucketAssertions;
import com.robothy.s3.core.assertions.CustomerEncryptionAssertions;
import com.robothy.s3.core.assertions.ObjectAssertions;
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
 * Stores objects: a put adds a version to the key, which replaces the virtual version of a bucket whose versioning was
 * never enabled; see {@code docs/semantics.md#versioning}.
 */
public interface PutObjectService extends LocalS3MetadataApplicable, StorageApplicable {

  /**
   * The content is stored before the bucket is locked, so a large upload doesn't block the bucket; only
   * {@linkplain #commitPutObject} holds the write lock. The change is delivered after the cleanup of a failed commit,
   * so a listener that fails can't have the content of a committed object deleted.
   */
  default PutObjectAns putObject(String bucketName, String key, PutObjectOptions options) {
    // Reject a missing bucket before storing the content; commitPutObject checks it again under the lock.
    BucketAssertions.assertBucketExists(localS3Metadata(), bucketName);
    ObjectAssertions.assertObjectKeyIsWritable(key);
    ObjectAssertions.assertUserMetadataIsValid(options.getUserMetadata());
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
   * See {@code docs/semantics.md#appends-and-renames}. A new version is stored rather than the object changed in
   * place, so a reader never sees half an append; the object is read and the content stored without a lock, and a
   * concurrent change fails the append rather than being lost.
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
              ans.getVersionId(), ans.getSize(), ans.getEtag(), version.getContentType(), version.getUserMetadata()));
          return ans;
        });
      } catch (Throwable e) {
        discardStoredContent(fileId, e);
        throw e;
      }
    });
  }

  /** {@code null} for no object, or a delete marker. */
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

  default PutObjectAns commitPutObject(String bucketName, String key, VersionedObjectMetadata versionedObjectMetadata) {
    return changeBucket(bucketName, () -> {
      return commitPutObject(bucketName, key, versionedObjectMetadata, ObjectPreconditions.none());
    });
  }

  /**
   * The preconditions are evaluated here, under the write lock, rather than before the content is stored: that is
   * what makes an {@code If-Match} put a compare-and-swap and an {@code If-None-Match: *} put a create.
   */
  default PutObjectAns commitPutObject(String bucketName, String key,
                                       VersionedObjectMetadata versionedObjectMetadata,
                                       ObjectPreconditions preconditions) {
    return commitPutObject(bucketName, key, versionedObjectMetadata, preconditions, PutObjectOptions.PUT_OBJECT);
  }

  /**
   * @param operation the S3 operation that the change is published under, e.g. {@code PostObject}.
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
          ans.getVersionId(), ans.getSize(), ans.getEtag(), versionedObjectMetadata.getContentType(),
          versionedObjectMetadata.getUserMetadata()));
      return ans;
    });
  }

  // The caller holds the write lock, e.g. CompleteMultipartUploadService, which removes the upload under the same lock.
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

  private static void applyBucketDefaultEncryption(BucketMetadata bucketMetadata, VersionedObjectMetadata version) {
    if (Objects.isNull(version.getServerSideEncryption()) && Objects.isNull(version.getCustomerEncryption())) {
      version.setServerSideEncryption(bucketMetadata.getDefaultEncryption());
    }
  }

  private static void checkRequestingMd5Header(PutObjectOptions options, String etag) {
    if (Objects.nonNull(options.getContentMd5())) {
      byte[] md5Bytes;
      try {
        md5Bytes = HexFormat.of().parseHex(etag);
      } catch (IllegalArgumentException _) {
        throw new LocalS3BadDigestException("Invalid Content-MD5 header.");
      }
      String computedBase64 = Base64.getEncoder().encodeToString(md5Bytes);
      if (!computedBase64.equals(options.getContentMd5())) {
        throw new LocalS3BadDigestException("The Content-MD5 you specified did not match what we received.");
      }
    }
  }

}
