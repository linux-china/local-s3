package com.robothy.s3.core.service;

import com.robothy.s3.core.assertions.PreconditionAssertions;
import com.robothy.s3.core.event.S3Change;
import com.robothy.s3.core.event.S3ChangeType;
import com.robothy.s3.core.exception.ObjectNotExistException;
import com.robothy.s3.core.exception.PreconditionFailedException;
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
import com.robothy.s3.core.exception.LocalS3BadDigestException;
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
 * Complete a multipart upload.
 */
public interface CompleteMultipartUploadService extends LocalS3MetadataApplicable, StorageApplicable, PutObjectService {

  /**
   * Compete a multipart upload. The parts aren't concatenated: the object references the stored content of the parts
   * that complete the upload, so that completing an upload takes neither the time nor the disk space of copying its
   * content. The upload is validated under the bucket read lock, and only {@linkplain #commitCompleteMultipartUpload}
   * holds the bucket write lock; a part whose content has to be read to compute the entity tag of the object is read
   * without a lock.
   *
   * @param bucket the bucket name.
   * @param key the object key.
   * @param uploadId multipart upload ID.
   * @param completeParts multipart upload parts to complete.
   * @return result of the complete multipart operation.
   */
  default CompleteMultipartUploadAns completeMultipartUpload(String bucket, String key, String uploadId,
                                                             List<CompleteMultipartUploadPartOption> completeParts) {
    return completeMultipartUpload(bucket, key, uploadId, completeParts, 0);
  }

  /**
   * Complete a multipart upload, requiring every part but the last one to be at least {@code minimumPartSize}
   * bytes. Amazon S3 always requires {@linkplain UploadAssertions#MIN_PART_SIZE}; LocalS3 only does when it is
   * configured to, so that tests that upload small parts keep working.
   *
   * @param bucket the bucket name.
   * @param key the object key.
   * @param uploadId multipart upload ID.
   * @param completeParts multipart upload parts to complete.
   * @param minimumPartSize the smallest size of a part that isn't the last one; {@code 0} to check nothing.
   * @return result of the complete multipart operation.
   */
  default CompleteMultipartUploadAns completeMultipartUpload(String bucket, String key, String uploadId,
                                                             List<CompleteMultipartUploadPartOption> completeParts,
                                                             long minimumPartSize) {
    return completeMultipartUpload(bucket, key, uploadId, completeParts, minimumPartSize, true);
  }

  /**
   * Complete a multipart upload, giving the object the entity tag that Amazon S3 gives an object uploaded in
   * parts, or the MD5 digest of its whole content.
   *
   * @param bucket the bucket name.
   * @param key the object key.
   * @param uploadId multipart upload ID.
   * @param completeParts multipart upload parts to complete.
   * @param minimumPartSize the smallest size of a part that isn't the last one; {@code 0} to check nothing.
   * @param compositeEtag {@code true} to give the object the
   *     {@linkplain S3ObjectUtils#compositeEtag(List) entity tag of Amazon S3}, which is what a client reads
   *     the part layout of an object off; {@code false} to give it the MD5 digest of its whole content,
   *     which is what LocalS3 gave it before 2.5, and which reads the content of all parts.
   * @return result of the complete multipart operation.
   */
  default CompleteMultipartUploadAns completeMultipartUpload(String bucket, String key, String uploadId,
                                                             List<CompleteMultipartUploadPartOption> completeParts,
                                                             long minimumPartSize, boolean compositeEtag) {
    return completeMultipartUpload(bucket, key, uploadId, completeParts, minimumPartSize, compositeEtag,
        ObjectPreconditions.none());
  }

  /**
   * Complete a multipart upload if the object that the key holds satisfies the {@code If-Match} and
   * {@code If-None-Match} conditions of the request, which are evaluated like the ones of {@code PutObject}, under
   * the write lock of the bucket that the object is added under: of the uploads that race to complete the same key
   * with {@code If-None-Match: *}, exactly one succeeds. An upload whose condition fails is kept, with its parts, so
   * that it can be completed again or aborted.
   *
   * @param bucket the bucket name.
   * @param key the object key.
   * @param uploadId multipart upload ID.
   * @param completeParts multipart upload parts to complete.
   * @param minimumPartSize the smallest size of a part that isn't the last one; {@code 0} to check nothing.
   * @param compositeEtag whether the object gets the entity tag of Amazon S3, see
   *     {@linkplain #completeMultipartUpload(String, String, String, List, long, boolean)}.
   * @param preconditions the conditions of the object that the key holds; {@linkplain ObjectPreconditions#none()}
   *     for none.
   * @return result of the complete multipart operation.
   * @throws PreconditionFailedException if a condition didn't hold.
   * @throws ObjectNotExistException if {@code If-Match} was given and the key holds no object.
   */
  default CompleteMultipartUploadAns completeMultipartUpload(String bucket, String key, String uploadId,
                                                             List<CompleteMultipartUploadPartOption> completeParts,
                                                             long minimumPartSize, boolean compositeEtag,
                                                             ObjectPreconditions preconditions) {
    return completeMultipartUpload(bucket, key, uploadId, completeParts, minimumPartSize, compositeEtag,
        preconditions, null, null);
  }

  /**
   * Complete a multipart upload like
   * {@linkplain #completeMultipartUpload(String, String, String, List, long, boolean, ObjectPreconditions)}, giving
   * the object the checksum of the algorithm and type that the upload was created with, if it was created with one.
   *
   * <p>A {@linkplain ChecksumType#COMPOSITE} checksum is the checksum of the checksums of the parts; a
   * {@linkplain ChecksumType#FULL_OBJECT} one is combined from the CRCs of the parts, without reading their content.
   * The content of a part is only read if the part was stored without a checksum of the algorithm.
   *
   * @param expectedChecksum the checksum of the whole object that the request sent, which the checksum of the object
   *     must match; {@code null} if it sent none.
   * @param expectedChecksumType the type of checksum that the request names, which must be the one of the upload;
   *     {@code null} if it names none.
   * @return result of the complete multipart operation.
   * @throws InvalidPartException if the request names a checksum of a part that the part wasn't uploaded with.
   * @throws LocalS3BadDigestException if the checksum of the object doesn't match {@code expectedChecksum}.
   */
  default CompleteMultipartUploadAns completeMultipartUpload(String bucket, String key, String uploadId,
                                                             List<CompleteMultipartUploadPartOption> completeParts,
                                                             long minimumPartSize, boolean compositeEtag,
                                                             ObjectPreconditions preconditions,
                                                             RequestChecksum expectedChecksum,
                                                             ChecksumType expectedChecksumType) {
    return completeMultipartUpload(bucket, key, uploadId, completeParts, minimumPartSize, compositeEtag, preconditions,
        expectedChecksum, expectedChecksumType, null);
  }

  /**
   * Complete a multipart upload like
   * {@linkplain #completeMultipartUpload(String, String, String, List, long, boolean, ObjectPreconditions,
   * RequestChecksum, ChecksumType)}, if the object is of the size that the request expects, the
   * {@code x-amz-mp-object-size} header, like Amazon S3 checks it. A client that lost a part, or counted one twice,
   * is told before the object is stored; the upload is kept, so that it can be completed again or aborted.
   *
   * @param expectedObjectSize the size of the object, in bytes, that the request expects; {@code null} if it expects
   *     none.
   * @return result of the complete multipart operation.
   * @throws LocalS3RequestException {@code 400 InvalidRequest} if the size of the object isn't
   *     {@code expectedObjectSize}.
   */
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
   * The answer of an upload that was completed already, which Amazon S3 gives again to a client that retries the
   * {@code CompleteMultipartUpload} of the upload, e.g. after its first request timed out. The answer is read off the
   * version that the upload stored, for as long as the key keeps it: once the version is overwritten in a bucket
   * without versioning, or deleted, a retry fails with {@code NoSuchUpload}, like it does on Amazon S3. Nothing
   * changes, and no change is published.
   *
   * @param bucket the bucket name.
   * @param key the object key.
   * @param uploadId the ID of the completed upload.
   * @return the answer of the completed upload; empty if the key holds no version that the upload stored.
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

  /**
   * The checksum of the object that an upload stores.
   *
   * @param upload the upload, whose parts are the ones that complete it.
   * @param completeParts the parts that the request completes the upload with.
   * @param layout the parts of the object, in the order of the content, which the checksums of the parts are read from.
   * @param expected the checksum of the whole object that the request sent; {@code null} for none.
   * @param expectedType the type of checksum that the request names; {@code null} for none.
   * @return the checksum; {@code null} if the upload was created without a checksum algorithm.
   */
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

  /**
   * The checksum of a part: the one it was uploaded with, or, for a part that was uploaded without one of the
   * algorithm, the one of its content, which is read.
   */
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

  /**
   * The number of bytes that are stored for a part.
   *
   * @throws InvalidPartException if the part was uploaded again since it was validated, which deleted its content.
   */
  private long partSize(int partNumber, UploadPartMetadata part) {
    try {
      return storage().size(part.getFileId());
    } catch (IllegalArgumentException e) {
      throw partGone(partNumber, e);
    }
  }

  /**
   * The MD5 digests of the parts that complete an upload, in the order of their part numbers.
   *
   * <p>The digest is the one that was computed when the content of the part was stored, rather than the entity tag
   * of the part: the entity tag is the one that the upload of the part answered, which a request may have supplied
   * instead of the digest of the data it sent. A part that a LocalS3 before 2.5 stored holds no digest, and its
   * content is read to compute it.
   */
  private List<byte[]> partDigests(NavigableMap<Integer, UploadPartMetadata> partsToComplete) {
    List<byte[]> digests = new ArrayList<>(partsToComplete.size());
    for (Map.Entry<Integer, UploadPartMetadata> part : partsToComplete.entrySet()) {
      String contentMd5 = part.getValue().getContentMd5();
      if (Objects.nonNull(contentMd5)) {
        try {
          digests.add(HexFormat.of().parseHex(contentMd5));
          continue;
        } catch (IllegalArgumentException e) {
          // Not a digest; compute it from the content.
        }
      }
      MessageDigest md5 = S3ObjectUtils.md5();
      digestPart(part.getKey(), part.getValue(), md5);
      digests.add(md5.digest());
    }
    return digests;
  }

  /**
   * The MD5 digest of the whole content of the object that the parts complete, which is read from the parts.
   */
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
   * Validate an upload to complete and the parts that complete it. Called by
   * {@linkplain #completeMultipartUpload}, which measures the parts after the bucket is unlocked;
   * {@linkplain #commitCompleteMultipartUpload} validates the upload again under the write lock.
   *
   * <p>Every part must have been uploaded, and the entity tag that completes it must be the one that its upload
   * answered, like Amazon S3 requires.
   *
   * @param bucket the bucket name.
   * @param key the object key.
   * @param uploadId multipart upload ID.
   * @param completeParts multipart upload parts to complete.
   * @return a copy of the upload to complete, whose parts are the ones that complete it, as they are now; the
   *     parts of the upload itself change when a part is uploaded again.
   * @throws InvalidPartOrderException if the part numbers aren't in ascending order.
   * @throws InvalidPartException if a part wasn't uploaded, or was uploaded with another entity tag.
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
   * Add the object of a completed upload, whose content is the content of the parts that complete the upload, and
   * remove the upload with the content of its other parts. Called by {@linkplain #completeMultipartUpload}.
   *
   * @param bucket the bucket name.
   * @param key the object key.
   * @param uploadId multipart upload ID.
   * @param versionedObjectMetadata the metadata of the new version, referencing the content of the parts.
   * @param partsToComplete the parts that complete the upload, as {@linkplain #prepareCompleteMultipartUpload}
   *     validated them.
   * @return result of the complete multipart operation.
   * @throws InvalidPartException if a part was uploaded again since it was validated, so that the version would
   *     reference content that isn't the content of the upload.
   */
  default CompleteMultipartUploadAns commitCompleteMultipartUpload(String bucket, String key, String uploadId,
                                                                   VersionedObjectMetadata versionedObjectMetadata,
                                                                   NavigableMap<Integer, UploadPartMetadata> partsToComplete) {
    return commitCompleteMultipartUpload(bucket, key, uploadId, versionedObjectMetadata, partsToComplete,
        ObjectPreconditions.none());
  }

  /**
   * Add the object of a completed upload like
   * {@linkplain #commitCompleteMultipartUpload(String, String, String, VersionedObjectMetadata, NavigableMap)}, if the
   * object that the key holds satisfies the conditions of the request. They are evaluated before anything changes, so
   * an upload whose condition fails is kept.
   *
   * @param preconditions the {@code If-Match} and {@code If-None-Match} conditions of the object that the key holds;
   *     {@linkplain ObjectPreconditions#none()} for none.
   * @return result of the complete multipart operation.
   */
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
          putObjectAns.getVersionId(), putObjectAns.getSize(), putObjectAns.getEtag()));
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
