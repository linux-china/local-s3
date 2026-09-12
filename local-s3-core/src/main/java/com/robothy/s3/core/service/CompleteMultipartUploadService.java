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
    // Invoked on the proxy, which read locks the bucket while the upload is validated.
    UploadMetadata uploadMetadata = prepareCompleteMultipartUpload(bucket, key, uploadId, completeParts);
    Map<Integer, UploadPartMetadata> uploadedParts = uploadMetadata.getParts();

    List<InputStream> inputStreams = completeParts.stream().map(completePart -> uploadedParts.get(completePart.getPartNumber()))
        .map(uploadPartMetadata -> storage().getInputStream(uploadPartMetadata.getFileId()))
        .collect(Collectors.toList());

    long size = completeParts.stream().map(CompleteMultipartUploadPartOption::getPartNumber)
        .map(uploadedParts::get).map(UploadPartMetadata::getSize).reduce(0L, Long::sum);

    Long fileId;
    DigestInputStream content =
        S3ObjectUtils.md5DigestingStream(new SequenceInputStream(Collections.enumeration(inputStreams)));
    try (InputStream in = content) {
      fileId = storage().put(in);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to concat multipart upload parts.", e);
    }

    try {
      VersionedObjectMetadata versionedObjectMetadata = new VersionedObjectMetadata();
      versionedObjectMetadata.setCreationDate(System.currentTimeMillis());
      versionedObjectMetadata.setContentType(uploadMetadata.getContentType());
      versionedObjectMetadata.setSize(size);
      versionedObjectMetadata.setFileId(fileId);
      versionedObjectMetadata.setEtag(S3ObjectUtils.etag(content.getMessageDigest()));
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
