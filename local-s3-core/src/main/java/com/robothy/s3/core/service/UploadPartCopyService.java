package com.robothy.s3.core.service;

import com.robothy.s3.core.exception.S3ErrorCode;
import com.robothy.s3.core.exception.LocalS3RequestException;
import com.robothy.s3.core.assertions.BucketAssertions;
import com.robothy.s3.core.assertions.UploadAssertions;
import com.robothy.s3.core.model.answers.GetObjectAns;
import com.robothy.s3.core.model.answers.UploadPartAns;
import com.robothy.s3.core.model.answers.UploadPartCopyAns;
import com.robothy.s3.core.model.request.Range;
import com.robothy.s3.core.model.request.UploadPartCopyOptions;
import com.robothy.s3.core.model.request.UploadPartOptions;
import java.io.IOException;
import java.io.InputStream;

/**
 * <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_UploadPartCopy.html">UploadPartCopy</a>,
 * which uploads a part of a multipart upload by copying a range of an object that is already stored.
 */
public interface UploadPartCopyService extends GetObjectService, UploadPartService {

  /**
   * Upload a part by copying data from an existing object. Neither bucket is locked while the content is
   * copied, so that copying a large range doesn't block the other requests to them: the source object is
   * resolved under the read lock of the source bucket, its content is copied without a lock, and only the
   * commit of {@linkplain #uploadPart} holds the write lock of the destination bucket. This is the same
   * sequence that {@linkplain CopyObjectService#copyObject} uses.
   *
   * @param bucket the bucket of the multipart upload.
   * @param key the object key of the multipart upload.
   * @param uploadId the upload ID generated when initializing the upload.
   * @param partNumber the part number.
   * @param options copy options.
   * @return result of the copy.
   */
  default UploadPartCopyAns uploadPartCopy(String bucket, String key, String uploadId, Integer partNumber,
                                           UploadPartCopyOptions options) {
    // Reject an invalid part number or a missing upload before the source is opened, so that a request that
    // fails anyway doesn't read the source at all; uploadPart checks both again once the data is stored.
    UploadAssertions.assertPartNumberIsValid(partNumber);
    UploadAssertions.assertUploadExists(BucketAssertions.assertBucketExists(localS3Metadata(), bucket), key, uploadId);

    // getCopySource read locks the source bucket while the source object is resolved and its conditions are
    // evaluated; the content is read without the lock.
    GetObjectAns source = getCopySource(options.getSourceBucket(), options.getSourceKey(),
        options.getSourceVersion().orElse(null), options.getCopySourceRange().orElse(null),
        options.getSourcePreconditions(), options.getSourceCustomerEncryption());

    if (source.isDeleteMarker()) {
      throw new LocalS3RequestException(S3ErrorCode.InvalidRequest,
          "The source of a copy request may not specifically refer to a delete marker by version id.");
    }

    // A read clamps a range that goes beyond the end of the object, while Amazon S3 rejects such a copy range.
    Long rangeLength = options.getCopySourceRange().flatMap(Range::length).orElse(null);
    if (rangeLength != null && source.getSize() != rangeLength) {
      LocalS3RequestException invalidRange = new LocalS3RequestException(S3ErrorCode.InvalidCopySourceRange);
      closeQuietly(source.getContent(), invalidRange);
      throw invalidRange;
    }

    UploadPartAns part;
    try {
      // uploadPart stores the content before commitUploadPart write locks the destination bucket. The ETag
      // of the part is the one of the copied bytes, which uploadPart computes while it stores them.
      part = uploadPart(bucket, key, uploadId, partNumber, UploadPartOptions.builder()
          .contentLength(source.getSize())
          .data(source.getContent())
          .customerEncryption(options.getCustomerEncryption())
          .build());
    } catch (Throwable e) {
      // The content is closed once it is stored; close it if it never got that far, e.g. because the upload
      // was aborted after the assertion above.
      closeQuietly(source.getContent(), e);
      throw e;
    }

    return UploadPartCopyAns.builder()
        .etag(part.getEtag())
        .lastModified(part.getLastModified())
        .sourceVersionId(source.getVersionId())
        .size(source.getSize())
        .checksum(part.getChecksum())
        .serverSideEncryption(part.getServerSideEncryption())
        .build();
  }

  private static void closeQuietly(InputStream content, Throwable cause) {
    if (content == null) {
      return;
    }

    try {
      content.close();
    } catch (IOException e) {
      cause.addSuppressed(e);
    }
  }

}
