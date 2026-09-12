package com.robothy.s3.core.service;

import com.robothy.s3.core.annotations.BucketChanged;
import com.robothy.s3.core.annotations.BucketWriteLock;
import com.robothy.s3.core.annotations.CallsThroughProxy;
import com.robothy.s3.core.assertions.BucketAssertions;
import com.robothy.s3.core.assertions.UploadAssertions;
import com.robothy.s3.core.model.answers.UploadPartAns;
import com.robothy.s3.core.model.internal.BucketMetadata;
import com.robothy.s3.core.model.internal.UploadMetadata;
import com.robothy.s3.core.model.internal.UploadPartMetadata;
import com.robothy.s3.core.model.request.UploadPartOptions;
import com.robothy.s3.core.util.S3ObjectUtils;
import com.robothy.s3.core.util.S3ObjectUtils.MeasuredInputStream;
import java.util.Objects;

/**
 * Uploads a part in a multipart upload.
 */
public interface UploadPartService extends LocalS3MetadataApplicable, StorageApplicable {

  /**
   * Upload part for an initialized upload. The data is stored before the bucket is locked, so that a large
   * part doesn't block the other requests to the bucket; only {@linkplain #commitUploadPart} holds the bucket
   * write lock. If the part can't be added, the stored data is deleted.
   *
   * @param bucket the bucket name.
   * @param key the object key of the upload.
   * @param uploadId the upload ID generated when initializing the upload.
   * @param partNumber the part number.
   * @param options options of upload the upload part operation.
   * @return result of the upload part.
   */
  @CallsThroughProxy
  default UploadPartAns uploadPart(String bucket, String key, String uploadId, Integer partNumber, UploadPartOptions options) {
    // Reject an invalid part number or a missing upload before storing the data; commitUploadPart checks the
    // upload again under the lock.
    UploadAssertions.assertPartNumberIsValid(partNumber);
    UploadAssertions.assertUploadExists(BucketAssertions.assertBucketExists(localS3Metadata(), bucket), key, uploadId);

    MeasuredInputStream data = S3ObjectUtils.measuringStream(options.getData());
    Long fileId = storage().put(data);
    try {
      UploadPartMetadata uploadPartMetadata = UploadPartMetadata.builder()
          .fileId(fileId)
          .lastModified(System.currentTimeMillis())
          // The length of the data that was stored, which the length declared by the request may not match.
          .size(data.getSize())
          .etag(options.getETag().orElseGet(data::etag))
          .build();
      return commitUploadPart(bucket, key, uploadId, partNumber, uploadPartMetadata);
    } catch (Throwable e) {
      discardStoredContent(fileId, e);
      throw e;
    }
  }

  /**
   * Add a part whose data is already stored to an upload, replacing the part with the same number.
   * Called by {@linkplain #uploadPart}.
   *
   * @param bucket the bucket name.
   * @param key the object key of the upload.
   * @param uploadId the upload ID generated when initializing the upload.
   * @param partNumber the part number.
   * @param uploadPartMetadata the metadata of the part, referencing the stored data.
   * @return result of the upload part.
   */
  @BucketChanged
  @BucketWriteLock
  default UploadPartAns commitUploadPart(String bucket, String key, String uploadId, Integer partNumber,
                                         UploadPartMetadata uploadPartMetadata) {
    BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucket);
    UploadMetadata uploadMetadata = UploadAssertions.assertUploadExists(bucketMetadata, key, uploadId);
    UploadPartMetadata replaced = uploadMetadata.getParts().put(partNumber, uploadPartMetadata);
    if (Objects.nonNull(replaced)) {
      storage().delete(replaced.getFileId());
    }
    return UploadPartAns.builder()
        .etag(uploadPartMetadata.getEtag())
        .lastModified(uploadPartMetadata.getLastModified())
        .build();
  }

}
