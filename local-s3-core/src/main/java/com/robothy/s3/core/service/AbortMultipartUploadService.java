package com.robothy.s3.core.service;

import com.robothy.s3.core.assertions.BucketAssertions;
import com.robothy.s3.core.assertions.ObjectAssertions;
import com.robothy.s3.core.event.S3Change;
import com.robothy.s3.core.exception.UploadNotExistException;
import com.robothy.s3.core.model.internal.BucketMetadata;
import com.robothy.s3.core.model.internal.LocalS3Metadata;
import com.robothy.s3.core.model.internal.UploadMetadata;
import java.util.NavigableMap;

public interface AbortMultipartUploadService extends LocalS3MetadataApplicable, StorageApplicable {

  default void abortMultipartUpload(String bucketName, String objectKey, String uploadId) {
    changeBucket(bucketName, () -> {
      LocalS3Metadata s3Metadata = localS3Metadata();
      BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(s3Metadata, bucketName);
      ObjectAssertions.assertObjectKeyIsValid(objectKey);
      NavigableMap<String, NavigableMap<String, UploadMetadata>> uploads = bucketMetadata.getUploads();
      if (!uploads.containsKey(objectKey) || !uploads.get(objectKey).containsKey(uploadId)) {
        throw new UploadNotExistException(objectKey, uploadId);
      }

      // The parts are deleted before the upload is removed, so that an IN_MEMORY service, which has no metadata to
      // reload, still references the parts left if a deletion fails.
      UploadMetadata uploadMetadata = uploads.get(objectKey).get(uploadId);
      uploadMetadata.getParts().forEach((uploadNumber, part) -> {
        storage().delete(part.getFileId());
      });
      uploads.get(objectKey).remove(uploadId);

      if (uploads.get(objectKey).isEmpty()) {
        uploads.remove(objectKey);
      }

      bucketMetadata.markUploadsChanged(objectKey);
      uploadMetadata.getParts().clear();
      publishChange(S3Change.multipartUploadAborted("AbortMultipartUpload", bucketName, objectKey, uploadId));
    });
  }

}
