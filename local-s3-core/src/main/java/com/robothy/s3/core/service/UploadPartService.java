package com.robothy.s3.core.service;

import com.robothy.s3.core.assertions.BucketAssertions;
import com.robothy.s3.core.assertions.UploadAssertions;
import com.robothy.s3.core.model.answers.UploadPartAns;
import com.robothy.s3.core.model.internal.BucketMetadata;
import com.robothy.s3.core.model.internal.UploadMetadata;
import com.robothy.s3.core.model.internal.UploadPartMetadata;
import com.robothy.s3.core.model.request.UploadPartOptions;
import com.robothy.s3.core.exception.LocalS3RequestException;
import com.robothy.s3.core.exception.S3ErrorCode;
import com.robothy.s3.core.model.internal.ObjectChecksum;
import com.robothy.s3.core.model.request.RequestChecksum;
import com.robothy.s3.core.util.Checksums;
import com.robothy.s3.datatypes.enums.CheckSumAlgorithm;
import java.util.Locale;
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
  default UploadPartAns uploadPart(String bucket, String key, String uploadId, Integer partNumber, UploadPartOptions options) {
    // Reject an invalid part number or a missing upload before storing the data; commitUploadPart checks the
    // upload again under the lock.
    UploadAssertions.assertPartNumberIsValid(partNumber);
    UploadMetadata upload = UploadAssertions.assertUploadExists(
        BucketAssertions.assertBucketExists(localS3Metadata(), bucket), key, uploadId);
    RequestChecksum checksum = partChecksum(upload, options.getChecksum());

    StoredContent data = storeContent(options.getData(), options.getDataFile(),
        Objects.isNull(checksum) ? null : checksum.algorithm());
    Long fileId = data.fileId();
    // Like putObject, a failure to deliver the changes of a committed part doesn't delete its data.
    return deliverChangesAfter(() -> {
      try {
        if (Objects.nonNull(checksum)) {
          Checksums.verify(checksum, data.checksum());
        }
        UploadPartMetadata uploadPartMetadata = UploadPartMetadata.builder()
            .fileId(fileId)
            .lastModified(System.currentTimeMillis())
            // The length of the data that was stored, which the length declared by the request may not match.
            .size(data.size())
            .etag(options.getETag().orElse(data.md5()))
            .contentMd5(data.md5())
            .checksum(Objects.isNull(checksum) ? null
                : ObjectChecksum.fullObject(checksum.algorithm(), Checksums.encode(data.checksum())))
            .build();
        return commitUploadPart(bucket, key, uploadId, partNumber, uploadPartMetadata);
      } catch (Throwable e) {
        discardStoredContent(fileId, e);
        throw e;
      }
    });
  }

  /**
   * The checksum that a part is uploaded with: the one of the request, which must be of the algorithm of the upload
   * if the upload has one, or else a checksum of the algorithm of the upload, which Amazon S3 computes for a part
   * uploaded without one, e.g. by {@code UploadPartCopy}.
   *
   * @param upload the upload that the part is uploaded to.
   * @param requested the checksum of the request; {@code null} if it has none.
   * @return the checksum to upload the part with; {@code null} for none.
   * @throws LocalS3RequestException {@code InvalidRequest} if the checksum of the request is of another algorithm
   *     than the one of the upload.
   */
  private static RequestChecksum partChecksum(UploadMetadata upload, RequestChecksum requested) {
    CheckSumAlgorithm algorithm = upload.getChecksumAlgorithm();
    if (Objects.isNull(requested)) {
      return Objects.isNull(algorithm) ? null : RequestChecksum.of(algorithm, null);
    }
    if (Objects.nonNull(algorithm) && algorithm != requested.algorithm()) {
      throw new LocalS3RequestException(S3ErrorCode.InvalidRequest,
          "Checksum Type mismatch occurred, expected checksum Type: " + algorithm.name().toLowerCase(Locale.ROOT)
              + ", actual checksum Type: " + requested.algorithm().name().toLowerCase(Locale.ROOT));
    }
    return requested;
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
  default UploadPartAns commitUploadPart(String bucket, String key, String uploadId, Integer partNumber,
                                         UploadPartMetadata uploadPartMetadata) {
    return changeBucket(bucket, () -> {
      BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucket);
      UploadMetadata uploadMetadata = UploadAssertions.assertUploadExists(bucketMetadata, key, uploadId);
      UploadPartMetadata replaced = uploadMetadata.getParts().put(partNumber, uploadPartMetadata);
      if (Objects.nonNull(replaced)) {
        storage().delete(replaced.getFileId());
      }
      bucketMetadata.markUploadsChanged(key);
      return UploadPartAns.builder()
          .etag(uploadPartMetadata.getEtag())
          .lastModified(uploadPartMetadata.getLastModified())
          .checksum(uploadPartMetadata.getChecksum())
          .build();
    });
  }

}
