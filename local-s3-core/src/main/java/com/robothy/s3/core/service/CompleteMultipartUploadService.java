package com.robothy.s3.core.service;

import com.robothy.s3.core.assertions.PreconditionAssertions;
import com.robothy.s3.core.event.S3Change;
import com.robothy.s3.core.event.S3ChangeType;
import com.robothy.s3.core.exception.UploadNotExistException;
import com.robothy.s3.core.exception.S3ErrorCode;
import com.robothy.s3.core.exception.LocalS3RequestException;
import com.robothy.s3.core.assertions.BucketAssertions;
import com.robothy.s3.core.assertions.UploadAssertions;
import com.robothy.s3.core.exception.InvalidPartException;
import com.robothy.s3.core.exception.InvalidPartOrderException;
import com.robothy.s3.core.model.answers.CompleteMultipartUploadAns;
import com.robothy.s3.core.model.answers.PutObjectAns;
import com.robothy.s3.core.model.internal.BucketMetadata;
import com.robothy.s3.core.model.internal.ObjectMetadata;
import com.robothy.s3.core.model.internal.ObjectPartMetadata;
import com.robothy.s3.core.model.internal.UploadMetadata;
import com.robothy.s3.core.model.internal.UploadPartMetadata;
import com.robothy.s3.core.model.internal.VersionedObjectMetadata;
import com.robothy.s3.core.model.request.CompleteMultipartUploadPartOption;
import com.robothy.s3.core.model.request.ObjectPreconditions;
import com.robothy.s3.core.util.S3ObjectUtils;
import com.robothy.s3.core.model.internal.ObjectChecksum;
import com.robothy.s3.core.model.request.RequestChecksum;
import com.robothy.s3.core.util.Checksums;
import com.robothy.s3.datatypes.enums.CheckSumAlgorithm;
import com.robothy.s3.datatypes.enums.ChecksumType;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * See {@code docs/semantics.md#completing-multipart-uploads}.
 */
public interface CompleteMultipartUploadService extends LocalS3MetadataApplicable, StorageApplicable, PutObjectService {

  /**
   * The upload is validated under the read lock, and only {@linkplain #commitCompleteMultipartUpload} holds the
   * write lock; a part whose content is read for an entity tag or a checksum is read without a lock.
   */
  default CompleteMultipartUploadAns completeMultipartUpload(String bucket, String key, String uploadId,
                                                             List<CompleteMultipartUploadPartOption> completeParts) {
    return completeMultipartUpload(bucket, key, uploadId, completeParts, 0);
  }

  /**
   * @param minimumPartSize {@code 0} to check nothing, so that tests that upload small parts keep working.
   */
  default CompleteMultipartUploadAns completeMultipartUpload(String bucket, String key, String uploadId,
                                                             List<CompleteMultipartUploadPartOption> completeParts,
                                                             long minimumPartSize) {
    return completeMultipartUpload(bucket, key, uploadId, completeParts, minimumPartSize, true);
  }

  /**
   * @param compositeEtag {@code false} for the MD5 of the whole content, which reads the content of all parts.
   */
  default CompleteMultipartUploadAns completeMultipartUpload(String bucket, String key, String uploadId,
                                                             List<CompleteMultipartUploadPartOption> completeParts,
                                                             long minimumPartSize, boolean compositeEtag) {
    return completeMultipartUpload(bucket, key, uploadId, completeParts, minimumPartSize, compositeEtag,
        ObjectPreconditions.none());
  }

  default CompleteMultipartUploadAns completeMultipartUpload(String bucket, String key, String uploadId,
                                                             List<CompleteMultipartUploadPartOption> completeParts,
                                                             long minimumPartSize, boolean compositeEtag,
                                                             ObjectPreconditions preconditions) {
    return completeMultipartUpload(bucket, key, uploadId, completeParts, minimumPartSize, compositeEtag,
        preconditions, null, null);
  }

  default CompleteMultipartUploadAns completeMultipartUpload(String bucket, String key, String uploadId,
                                                             List<CompleteMultipartUploadPartOption> completeParts,
                                                             long minimumPartSize, boolean compositeEtag,
                                                             ObjectPreconditions preconditions,
                                                             RequestChecksum expectedChecksum,
                                                             ChecksumType expectedChecksumType) {
    return completeMultipartUpload(bucket, key, uploadId, completeParts, minimumPartSize, compositeEtag, preconditions,
        expectedChecksum, expectedChecksumType, null);
  }

  default CompleteMultipartUploadAns completeMultipartUpload(String bucket, String key, String uploadId,
                                                             List<CompleteMultipartUploadPartOption> completeParts,
                                                             long minimumPartSize, boolean compositeEtag,
                                                             ObjectPreconditions preconditions,
                                                             RequestChecksum expectedChecksum,
                                                             ChecksumType expectedChecksumType,
                                                             Long expectedObjectSize) {
    // prepareCompleteMultipartUpload read locks the bucket while the upload is validated. The parts of the returned
    // upload are a snapshot of the ones that complete it, so a part uploaded again from here on isn't mixed in.
    UploadMetadata uploadMetadata;
    try {
      uploadMetadata = prepareCompleteMultipartUpload(bucket, key, uploadId, completeParts);
    } catch (UploadNotExistException e) {
      // A client retries a CompleteMultipartUpload whose response it didn't receive.
      return completedMultipartUpload(bucket, key, uploadId).orElseThrow(() -> e);
    }
    UploadAssertions.assertPartsAreLargeEnough(uploadMetadata, completeParts, minimumPartSize);
    NavigableMap<Integer, UploadPartMetadata> partsToComplete = uploadMetadata.getParts();

    // The layout records the bytes that are actually stored for every part, which the length declared when a part
    // was uploaded may not match; the content of the object is read from the parts by these sizes.
    List<ObjectPartMetadata> layout = new ArrayList<>(partsToComplete.size());
    long size = 0;
    for (Map.Entry<Integer, UploadPartMetadata> part : partsToComplete.entrySet()) {
      long partSize = partSize(part.getKey(), part.getValue());
      layout.add(ObjectPartMetadata.builder()
          .partNumber(part.getKey())
          .size(partSize)
          .fileId(part.getValue().getFileId())
          .checksum(part.getValue().getChecksum())
          .build());
      size += partSize;
    }
    if (expectedObjectSize != null && expectedObjectSize != size) {
      throw new LocalS3RequestException(S3ErrorCode.InvalidRequest, "The provided 'x-amz-mp-object-size' header "
          + "value " + expectedObjectSize + " does not match what was computed: " + size);
    }
    ObjectChecksum checksum = objectChecksum(uploadMetadata, completeParts, layout, expectedChecksum,
        expectedChecksumType);

    VersionedObjectMetadata versionedObjectMetadata = new VersionedObjectMetadata();
    versionedObjectMetadata.setCreationDate(System.currentTimeMillis());
    versionedObjectMetadata.setContentType(uploadMetadata.getContentType());
    versionedObjectMetadata.setSystemMetadata(uploadMetadata.getSystemMetadata());
    versionedObjectMetadata.setSize(size);
    versionedObjectMetadata.setEtag(compositeEtag ? S3ObjectUtils.compositeEtag(partDigests(partsToComplete))
        : contentDigest(partsToComplete));
    // The content of the object is the content of the parts. The layout is kept after the upload is removed, so
    // that GetObjectAttributes can answer the part layout of the object as well.
    versionedObjectMetadata.setParts(layout);
    versionedObjectMetadata.setChecksum(checksum);
    uploadMetadata.getTagging().ifPresent(versionedObjectMetadata::setTagging);
    // The default retention of the bucket, if the upload wasn't created with a retention, is applied when the version
    // is added.
    versionedObjectMetadata.setObjectLock(uploadMetadata.getObjectLock());
    versionedObjectMetadata.setCustomerEncryption(uploadMetadata.getCustomerEncryption());
    versionedObjectMetadata.setServerSideEncryption(uploadMetadata.getServerSideEncryption());
    versionedObjectMetadata.setUploadId(uploadId);
    if (Objects.nonNull(uploadMetadata.getUserMetadata())) {
      versionedObjectMetadata.setUserMetadata(uploadMetadata.getUserMetadata());
    }

    try {
      return commitCompleteMultipartUpload(bucket, key, uploadId, versionedObjectMetadata, partsToComplete,
          preconditions);
    } catch (UploadNotExistException e) {
      // A concurrent request with the same upload completed it first, e.g. the retry of a client that timed out.
      return completedMultipartUpload(bucket, key, uploadId).orElseThrow(() -> e);
    }
  }

  /**
   * Read off the version that the upload stored; nothing changes, and no change is published.
   */
  default Optional<CompleteMultipartUploadAns> completedMultipartUpload(String bucket, String key, String uploadId) {
    return withBucketReadLock(bucket, () -> {
      BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucket);
      return bucketMetadata.getObjectMetadata(key).flatMap(object -> object.getVersionedObjectMap().entrySet()
          .stream()
          .filter(version -> !version.getValue().isDeleted() && uploadId.equals(version.getValue().getUploadId()))
          .findFirst()
          .map(version -> CompleteMultipartUploadAns.builder()
              .location("/" + bucket + "/" + key)
              .versionId(answeredVersionId(bucketMetadata, object, version.getKey()))
              .etag(version.getValue().getEtag())
              .size(version.getValue().getSize())
              .checksum(version.getValue().getChecksum())
              .serverSideEncryption(version.getValue().getServerSideEncryption())
              .build()));
    });
  }

  /**
   * The version ID that the completion of an upload answered, which {@linkplain PutObjectService#addVersion} decides:
   * the version itself in a bucket with versioning, and the null version otherwise.
   */
  private static String answeredVersionId(BucketMetadata bucketMetadata, ObjectMetadata object, String versionId) {
    if (!versionId.equals(object.getVirtualVersion().orElse(null))) {
      return versionId;
    }
    return Objects.isNull(bucketMetadata.getVersioningEnabled()) ? null : ObjectMetadata.NULL_VERSION;
  }

  private ObjectChecksum objectChecksum(UploadMetadata upload, List<CompleteMultipartUploadPartOption> completeParts,
                                        List<ObjectPartMetadata> layout, RequestChecksum expected,
                                        ChecksumType expectedType) {
    CheckSumAlgorithm algorithm = upload.getChecksumAlgorithm();
    if (Objects.isNull(algorithm)) {
      return null;
    }
    ChecksumType type = Objects.requireNonNullElseGet(upload.getChecksumType(),
        () -> Checksums.defaultMultipartType(algorithm));
    if (Objects.nonNull(expectedType) && expectedType != type) {
      throw new LocalS3RequestException(S3ErrorCode.InvalidRequest, "The upload was created using the " + type
          + " checksum mode. The complete request must use the same checksum mode.");
    }
    if (Objects.nonNull(expected) && expected.algorithm() != algorithm) {
      throw new LocalS3RequestException(S3ErrorCode.InvalidRequest, "Checksum Type mismatch occurred, expected "
          + "checksum Type: " + algorithm.name().toLowerCase(Locale.ROOT) + ", actual checksum Type: "
          + expected.algorithm().name().toLowerCase(Locale.ROOT));
    }

    Map<Integer, CompleteMultipartUploadPartOption> requestedParts = new HashMap<>();
    completeParts.forEach(part -> requestedParts.put(part.getPartNumber(), part));
    List<byte[]> partChecksums = new ArrayList<>(layout.size());
    List<Long> partSizes = new ArrayList<>(layout.size());
    for (ObjectPartMetadata part : layout) {
      byte[] partChecksum = partChecksum(algorithm, part);
      CompleteMultipartUploadPartOption requested = requestedParts.get(part.getPartNumber());
      String requestedChecksum = Objects.isNull(requested) || Objects.isNull(requested.getChecksums()) ? null
          : requested.getChecksums().get(algorithm);
      if (Objects.nonNull(requestedChecksum)
          && !MessageDigest.isEqual(Checksums.decode(algorithm, requestedChecksum), partChecksum)) {
        throw InvalidPartException.checksumMismatch(part.getPartNumber(), algorithm.name());
      }
      partChecksums.add(partChecksum);
      partSizes.add(part.getSize());
    }

    if (type == ChecksumType.COMPOSITE) {
      String composite = Checksums.composite(algorithm, partChecksums);
      String sent = Objects.isNull(expected) ? null : expected.expected().get();
      if (Objects.nonNull(sent)) {
        // A client may send the composite checksum with or without the number of parts.
        Checksums.verify(RequestChecksum.of(algorithm, sent.trim().replaceFirst("-\\d+$", "")),
            Checksums.decode(algorithm, composite.substring(0, composite.lastIndexOf('-'))));
      }
      return new ObjectChecksum(algorithm, ChecksumType.COMPOSITE, composite);
    }

    byte[] fullObject = Checksums.combine(algorithm, partChecksums, partSizes);
    if (Objects.nonNull(expected)) {
      Checksums.verify(expected, fullObject);
    }
    return ObjectChecksum.fullObject(algorithm, Checksums.encode(fullObject));
  }

  private byte[] partChecksum(CheckSumAlgorithm algorithm, ObjectPartMetadata part) {
    ObjectChecksum checksum = part.getChecksum();
    if (Objects.nonNull(checksum) && checksum.getAlgorithm() == algorithm) {
      return Checksums.decode(algorithm, checksum.getValue());
    }

    InputStream content;
    try {
      content = storage().getInputStream(part.getFileId());
    } catch (IllegalArgumentException e) {
      throw partGone(part.getPartNumber(), e);
    }
    byte[] computed;
    try (InputStream in = content) {
      computed = Checksums.compute(algorithm, in);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read part " + part.getPartNumber() + ".", e);
    }
    part.setChecksum(ObjectChecksum.fullObject(algorithm, Checksums.encode(computed)));
    return computed;
  }

  private long partSize(int partNumber, UploadPartMetadata part) {
    try {
      return storage().size(part.getFileId());
    } catch (IllegalArgumentException e) {
      throw partGone(partNumber, e);
    }
  }

  /**
   * The digest computed when the part was stored rather than its entity tag, which a request may have supplied
   * instead of the digest of what it sent. A part stored before 2.5 holds no digest, and its content is read.
   */
  private List<byte[]> partDigests(NavigableMap<Integer, UploadPartMetadata> partsToComplete) {
    List<byte[]> digests = new ArrayList<>(partsToComplete.size());
    for (Map.Entry<Integer, UploadPartMetadata> part : partsToComplete.entrySet()) {
      String contentMd5 = part.getValue().getContentMd5();
      if (Objects.nonNull(contentMd5)) {
        try {
          digests.add(HexFormat.of().parseHex(contentMd5));
          continue;
        } catch (IllegalArgumentException _) {
          // Not a digest; compute it from the content.
        }
      }
      MessageDigest md5 = S3ObjectUtils.md5();
      digestPart(part.getKey(), part.getValue(), md5);
      digests.add(md5.digest());
    }
    return digests;
  }

  private String contentDigest(NavigableMap<Integer, UploadPartMetadata> partsToComplete) {
    MessageDigest md5 = S3ObjectUtils.md5();
    partsToComplete.forEach((partNumber, part) -> digestPart(partNumber, part, md5));
    return S3ObjectUtils.etag(md5);
  }

  private void digestPart(int partNumber, UploadPartMetadata part, MessageDigest md5) {
    InputStream content;
    try {
      content = storage().getInputStream(part.getFileId());
    } catch (IllegalArgumentException e) {
      throw partGone(partNumber, e);
    }
    try (InputStream in = content) {
      S3ObjectUtils.updateDigest(md5, in);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read part " + partNumber + ".", e);
    }
  }

  /**
   * The storage reports the content of a part that is gone with an {@linkplain IllegalArgumentException}, which
   * means that the part was uploaded again since it was validated.
   */
  private static InvalidPartException partGone(int partNumber, IllegalArgumentException cause) {
    InvalidPartException replaced = InvalidPartException.replaced(partNumber);
    replaced.addSuppressed(cause);
    return replaced;
  }

  /**
   * @return a copy of the upload whose parts are the ones that complete it, as they are now: the parts of the upload
   *     itself change when a part is uploaded again. {@linkplain #commitCompleteMultipartUpload} validates them again
   *     under the write lock.
   */
  default UploadMetadata prepareCompleteMultipartUpload(String bucket, String key, String uploadId,
                                                        List<CompleteMultipartUploadPartOption> completeParts) {
    return withBucketReadLock(bucket, () -> {
      BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucket);
      UploadMetadata uploadMetadata = UploadAssertions.assertUploadExists(bucketMetadata, key, uploadId);

      if (completeParts.isEmpty()) {
        throw new LocalS3RequestException(S3ErrorCode.MalformedXML);
      }

      int pre = -1;
      NavigableMap<Integer, UploadPartMetadata> partsToComplete = new TreeMap<>();
      for (CompleteMultipartUploadPartOption partOption : completeParts) {
        if (partOption.getPartNumber() <= pre) {
          throw new InvalidPartOrderException();
        }
        pre = partOption.getPartNumber();
        partsToComplete.put(partOption.getPartNumber(), UploadAssertions.assertPartMatches(uploadMetadata, partOption));
      }

      return UploadMetadata.builder()
          .createDate(uploadMetadata.getCreateDate())
          .contentType(uploadMetadata.getContentType())
          .systemMetadata(uploadMetadata.getSystemMetadata())
          .tagging(uploadMetadata.getTagging().orElse(null))
          .userMetadata(uploadMetadata.getUserMetadata())
          .checksumAlgorithm(uploadMetadata.getChecksumAlgorithm())
          .checksumType(uploadMetadata.getChecksumType())
          .objectLock(uploadMetadata.getObjectLock())
          .customerEncryption(uploadMetadata.getCustomerEncryption())
          .serverSideEncryption(uploadMetadata.getServerSideEncryption())
          .parts(partsToComplete)
          .build();
    });
  }

  /**
   * @throws InvalidPartException if a part was uploaded again since it was validated, so that the version would
   *     reference content that isn't the content of the upload.
   */
  default CompleteMultipartUploadAns commitCompleteMultipartUpload(String bucket, String key, String uploadId,
                                                                   VersionedObjectMetadata versionedObjectMetadata,
                                                                   NavigableMap<Integer, UploadPartMetadata> partsToComplete) {
    return commitCompleteMultipartUpload(bucket, key, uploadId, versionedObjectMetadata, partsToComplete,
        ObjectPreconditions.none());
  }

  default CompleteMultipartUploadAns commitCompleteMultipartUpload(String bucket, String key, String uploadId,
                                                                   VersionedObjectMetadata versionedObjectMetadata,
                                                                   NavigableMap<Integer, UploadPartMetadata> partsToComplete,
                                                                   ObjectPreconditions preconditions) {
    return changeBucket(bucket, () -> {
      BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucket);
      UploadMetadata uploadMetadata = UploadAssertions.assertUploadExists(bucketMetadata, key, uploadId);
      PreconditionAssertions.assertWritePreconditionsHold(preconditions, key,
          bucketMetadata.getObjectMetadata(key).orElse(null));
      // The data of a part is stored under a new ID whenever the part is uploaded, so an unchanged ID is an unchanged part.
      partsToComplete.forEach((partNumber, completing) -> {
        UploadPartMetadata current = uploadMetadata.getParts().get(partNumber);
        if (current == null || current.getFileId() != completing.getFileId()) {
          throw InvalidPartException.replaced(partNumber);
        }
      });
      PutObjectAns putObjectAns = PutObjectService.addVersion(bucketMetadata, storage(), key, versionedObjectMetadata);

      // The content of the parts that complete the upload now belongs to the object; only the other parts are deleted.
      uploadMetadata.getParts().forEach((partNumber, part) -> {
        if (!partsToComplete.containsKey(partNumber)) {
          storage().delete(part.getFileId());
        }
      });
      Map<String, NavigableMap<String, UploadMetadata>> uploads = bucketMetadata.getUploads();
      uploads.get(key).remove(uploadId);
      if (uploads.get(key).isEmpty()) {
        uploads.remove(key);
      }
      bucketMetadata.markUploadsChanged(key);

      publishChange(S3Change.objectVersion(S3ChangeType.OBJECT_CREATED, "CompleteMultipartUpload", bucket, key,
          putObjectAns.getVersionId(), putObjectAns.getSize(), putObjectAns.getEtag(),
          versionedObjectMetadata.getContentType(), versionedObjectMetadata.getUserMetadata()));
      return CompleteMultipartUploadAns.builder()
          .location("/" + bucket + "/" + key)
          .versionId(putObjectAns.getVersionId())
          .etag(putObjectAns.getEtag())
          .size(putObjectAns.getSize())
          .checksum(putObjectAns.getChecksum())
          .serverSideEncryption(putObjectAns.getServerSideEncryption())
          .build();
    });
  }

}
