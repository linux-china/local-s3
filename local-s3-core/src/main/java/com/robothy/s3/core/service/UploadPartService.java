package com.robothy.s3.core.service;

import com.robothy.s3.core.assertions.BucketAssertions;
import com.robothy.s3.core.assertions.CustomerEncryptionAssertions;
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

public interface UploadPartService extends LocalS3MetadataApplicable, StorageApplicable {

  /**
   * The data is stored before the bucket is locked, so a large part doesn't block the bucket; only
   * {@linkplain #commitUploadPart} holds the write lock.
   */
  default UploadPartAns uploadPart(String bucket, String key, String uploadId, Integer partNumber, UploadPartOptions options) {
    // Reject an invalid part number or a missing upload before storing the data; commitUploadPart checks the
    // upload again under the lock.
    UploadAssertions.assertPartNumberIsValid(partNumber);
    UploadMetadata upload = UploadAssertions.assertUploadExists(
        BucketAssertions.assertBucketExists(localS3Metadata(), bucket), key, uploadId);
    CustomerEncryptionAssertions.assertKeyProvided(upload.getCustomerEncryption(), options.getCustomerEncryption());
    RequestChecksum checksum = partChecksum(upload, options.getChecksum());

    StoredContent data = storeContent(options.getData(), options.getDataFile(), options.getHeapData(),
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

  // A part uploaded without a checksum, e.g. by UploadPartCopy, gets one of the algorithm of its upload, like Amazon S3.
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
          // A part is answered with the encryption of its upload, which the request of the part doesn't name.
          .serverSideEncryption(uploadMetadata.getServerSideEncryption())
          .build();
    });
  }

}
