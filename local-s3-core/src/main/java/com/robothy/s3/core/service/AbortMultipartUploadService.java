package com.robothy.s3.core.service;

import com.robothy.s3.core.assertions.BucketAssertions;
import com.robothy.s3.core.assertions.ObjectAssertions;
import com.robothy.s3.core.event.S3Change;
import com.robothy.s3.core.exception.UploadNotExistException;
import com.robothy.s3.core.model.internal.BucketMetadata;
import com.robothy.s3.core.model.internal.LocalS3Metadata;
import com.robothy.s3.core.model.internal.UploadMetadata;
import java.util.NavigableMap;

/**
 * <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_AbortMultipartUpload.html">AbortMultipartUpload</a>
 */
public interface AbortMultipartUploadService extends LocalS3MetadataApplicable, StorageApplicable {

  /**
   * Abort the multipart upload and release the storage space. Like Amazon S3, an upload ID that is not found,
   * or no longer found as the upload was aborted or completed, is {@code 404 NoSuchUpload}.
   *
   * @param bucketName bucket name.
   * @param objectKey object key.
   * @param uploadId upload ID.
   */
  default void abortMultipartUpload(String bucketName, String objectKey, String uploadId) {
    changeBucket(bucketName, () -> {
      LocalS3Metadata s3Metadata = localS3Metadata();
      BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(s3Metadata, bucketName);
      ObjectAssertions.assertObjectKeyIsValid(objectKey);
      NavigableMap<String, NavigableMap<String, UploadMetadata>> uploads = bucketMetadata.getUploads();
      if (!uploads.containsKey(objectKey) || !uploads.get(objectKey).containsKey(uploadId)) {
        throw new UploadNotExistException(objectKey, uploadId);
      }

      UploadMetadata uploadMetadata = uploads.get(objectKey).remove(uploadId);
      uploadMetadata.getParts().forEach((uploadNumber, part) -> {
        storage().delete(part.getFileId());
      });

      if (uploads.get(objectKey).isEmpty()) {
        uploads.remove(objectKey);
      }

      bucketMetadata.markUploadsChanged(objectKey);
      // help GC.
      uploadMetadata.getParts().clear();
      publishChange(S3Change.multipartUploadAborted("AbortMultipartUpload", bucketName, objectKey, uploadId));
    });
  }

}
