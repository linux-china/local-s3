package com.robothy.s3.core.service;

import com.robothy.s3.core.annotations.BucketChanged;
import com.robothy.s3.core.annotations.BucketReadLock;
import com.robothy.s3.core.annotations.BucketWriteLock;
import com.robothy.s3.core.annotations.CallsThroughProxy;
import com.robothy.s3.core.assertions.BucketAssertions;
import com.robothy.s3.core.assertions.UploadAssertions;
import com.robothy.s3.core.exception.InvalidPartOrderException;
import com.robothy.s3.core.model.answers.CompleteMultipartUploadAns;
import com.robothy.s3.core.model.answers.PutObjectAns;
import com.robothy.s3.core.model.internal.BucketMetadata;
import com.robothy.s3.core.model.internal.UploadMetadata;
import com.robothy.s3.core.model.internal.UploadPartMetadata;
import com.robothy.s3.core.model.internal.VersionedObjectMetadata;
import com.robothy.s3.core.model.request.CompleteMultipartUploadPartOption;
import com.robothy.s3.core.util.S3ObjectUtils;
import com.robothy.s3.core.util.S3ObjectUtils.MeasuredInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.security.DigestInputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Complete a multipart upload.
 */
public interface CompleteMultipartUploadService extends LocalS3MetadataApplicable, StorageApplicable, PutObjectService {

  /**
   * Compete a multipart upload. The parts are concatenated before the bucket is locked, so that completing a
   * large upload doesn't block the other requests to the bucket: the upload is validated under the bucket read
   * lock, its parts are concatenated into the storage without a lock, and only
   * {@linkplain #commitCompleteMultipartUpload} holds the bucket write lock. If the object can't be added, the
   * stored content is deleted.
   *
   * @param bucket the bucket name.
   * @param key the object key.
   * @param uploadId multipart upload ID.
   * @param completeParts multipart upload parts to complete.
   * @return result of the complete multipart operation.
   */
  @CallsThroughProxy
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
  @CallsThroughProxy
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
   *     the part layout of an object off; {@code false} to give it the MD5 digest of the concatenated parts,
   *     which is what LocalS3 gave it before 2.5.
   * @return result of the complete multipart operation.
   */
  @CallsThroughProxy
  default CompleteMultipartUploadAns completeMultipartUpload(String bucket, String key, String uploadId,
                                                             List<CompleteMultipartUploadPartOption> completeParts,
                                                             long minimumPartSize, boolean compositeEtag) {
    // Invoked on the proxy, which read locks the bucket while the upload is validated.
    UploadMetadata uploadMetadata = prepareCompleteMultipartUpload(bucket, key, uploadId, completeParts);
    UploadAssertions.assertPartsAreLargeEnough(uploadMetadata, completeParts, minimumPartSize);
    Map<Integer, UploadPartMetadata> uploadedParts = uploadMetadata.getParts();

    // The MD5 digest of every part is computed while the parts are concatenated, rather than read off the
    // metadata of the parts: the entity tag that the metadata holds is the one that the upload of the part
    // reported, which a request may have supplied instead of the digest of the data it sent, and the parts of
    // an upload that a LocalS3 version before 2.5 stored hold no digest at all. Digesting the data that is
    // concatenated costs no extra read, and makes the entity tag describe the bytes that were actually stored.
    List<DigestInputStream> partStreams = completeParts.stream()
        .map(completePart -> uploadedParts.get(completePart.getPartNumber()))
        .map(uploadPartMetadata -> S3ObjectUtils.digestingStream(storage().getInputStream(uploadPartMetadata.getFileId())))
        .collect(Collectors.toList());

    Long fileId;
    MeasuredInputStream content =
        S3ObjectUtils.measuringStream(new SequenceInputStream(Collections.enumeration(partStreams)));
    try (InputStream in = content) {
      fileId = storage().put(in);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to concat multipart upload parts.", e);
    }

    try {
      VersionedObjectMetadata versionedObjectMetadata = new VersionedObjectMetadata();
      versionedObjectMetadata.setCreationDate(System.currentTimeMillis());
      versionedObjectMetadata.setContentType(uploadMetadata.getContentType());
      // The length of the concatenated parts, which the lengths declared when they were uploaded may not match.
      versionedObjectMetadata.setSize(content.getSize());
      versionedObjectMetadata.setFileId(fileId);
      // The parts were read to their end above, so their digests are complete.
      versionedObjectMetadata.setEtag(compositeEtag ? S3ObjectUtils.compositeEtag(partDigests(partStreams))
          : content.etag());
      uploadMetadata.getTagging().ifPresent(versionedObjectMetadata::setTagging);
      if (Objects.nonNull(uploadMetadata.getUserMetadata())) {
        versionedObjectMetadata.setUserMetadata(uploadMetadata.getUserMetadata());
      }

      return commitCompleteMultipartUpload(bucket, key, uploadId, versionedObjectMetadata);
    } catch (Throwable e) {
      discardStoredContent(fileId, e);
      throw e;
    }
  }

  /**
   * The MD5 digests of the parts that were concatenated, in the order they were concatenated in.
   *
   * @param partStreams the streams that the parts were read through, read to their end.
   * @return the digest of every part.
   */
  private static List<byte[]> partDigests(List<DigestInputStream> partStreams) {
    return partStreams.stream()
        .map(partStream -> partStream.getMessageDigest().digest())
        .collect(Collectors.toList());
  }

  /**
   * Validate an upload to complete and the parts that complete it. Called by
   * {@linkplain #completeMultipartUpload}, which concatenates the parts after the bucket is unlocked;
   * {@linkplain #commitCompleteMultipartUpload} validates the upload again under the write lock.
   *
   * @param bucket the bucket name.
   * @param key the object key.
   * @param uploadId multipart upload ID.
   * @param completeParts multipart upload parts to complete.
   * @return the metadata of the upload to complete.
   */
  @BucketReadLock
  default UploadMetadata prepareCompleteMultipartUpload(String bucket, String key, String uploadId,
                                                        List<CompleteMultipartUploadPartOption> completeParts) {
    BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucket);
    UploadMetadata uploadMetadata = UploadAssertions.assertUploadExists(bucketMetadata, key, uploadId);

    if (completeParts.isEmpty()) {
      throw new IllegalArgumentException("You must specify at least 1 multipart upload part.");
    }

    int pre = -1;
    // Check part numbers.
    for (CompleteMultipartUploadPartOption partOption : completeParts) {
      if (partOption.getPartNumber() <= pre) {
        throw new InvalidPartOrderException();
      }
      pre = partOption.getPartNumber();
      UploadAssertions.assertPartNumberExists(uploadMetadata, partOption.getPartNumber());
    }

    return uploadMetadata;
  }

  /**
   * Add the object of a completed upload, whose content is already stored, and remove the upload with the data
   * of its parts. Called by {@linkplain #completeMultipartUpload}.
   *
   * @param bucket the bucket name.
   * @param key the object key.
   * @param uploadId multipart upload ID.
   * @param versionedObjectMetadata the metadata of the new version, referencing the concatenated content.
   * @return result of the complete multipart operation.
   */
  @BucketChanged
  @BucketWriteLock
  default CompleteMultipartUploadAns commitCompleteMultipartUpload(String bucket, String key, String uploadId,
                                                                   VersionedObjectMetadata versionedObjectMetadata) {
    BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucket);
    UploadMetadata uploadMetadata = UploadAssertions.assertUploadExists(bucketMetadata, key, uploadId);
    PutObjectAns putObjectAns = PutObjectService.addVersion(bucketMetadata, storage(), key, versionedObjectMetadata);

    // Cleanup
    uploadMetadata.getParts().values().forEach(part -> storage().delete(part.getFileId()));
    Map<String, NavigableMap<String, UploadMetadata>> uploads = bucketMetadata.getUploads();
    uploads.get(key).remove(uploadId);
    if (uploads.get(key).isEmpty()) {
      uploads.remove(key);
    }

    return CompleteMultipartUploadAns.builder()
        .location("/" + bucket + "/" + key)
        .versionId(putObjectAns.getVersionId())
        .etag(putObjectAns.getEtag())
        .size(putObjectAns.getSize())
        .build();
  }

}
