package com.robothy.s3.core.assertions;

import com.robothy.s3.core.exception.EntityTooSmallException;
import com.robothy.s3.core.exception.LocalS3InvalidArgumentException;
import com.robothy.s3.core.exception.UploadNotExistException;
import com.robothy.s3.core.model.request.CompleteMultipartUploadPartOption;
import com.robothy.s3.core.model.internal.BucketMetadata;
import com.robothy.s3.core.model.internal.UploadMetadata;
import com.robothy.s3.core.model.internal.UploadPartMetadata;
import java.util.List;
import java.util.NavigableMap;

/**
 * Multipart upload related assertions.
 */
public class UploadAssertions {

  /**
   * The smallest part number of a multipart upload.
   */
  public static final int MIN_PART_NUMBER = 1;

  /**
   * The largest part number of a multipart upload, so an upload holds at most 10000 parts.
   */
  public static final int MAX_PART_NUMBER = 10000;

  /**
   * The smallest size of a part that isn't the last one of an upload. Amazon S3 rejects a smaller part when
   * the upload is completed, rather than when the part is uploaded, because only then is it known which part
   * is the last one. LocalS3 only applies it when it is configured to.
   */
  public static final long MIN_PART_SIZE = 5L * 1024 * 1024;

  /**
   * Assert that a part number is one that a multipart upload can hold.
   *
   * @param partNumber the part number to validate.
   * @return the valid part number.
   * @throws LocalS3InvalidArgumentException if the part number is outside
   *     {@linkplain #MIN_PART_NUMBER}..{@linkplain #MAX_PART_NUMBER}.
   */
  public static int assertPartNumberIsValid(Integer partNumber) {
    if (partNumber == null || partNumber < MIN_PART_NUMBER || partNumber > MAX_PART_NUMBER) {
      throw new LocalS3InvalidArgumentException("partNumber", String.valueOf(partNumber),
          "Part number must be an integer between " + MIN_PART_NUMBER + " and " + MAX_PART_NUMBER + ", inclusive.");
    }
    return partNumber;
  }

  /**
   * Assert that every part of an upload but the last one is at least {@code minimumPartSize} bytes, which
   * Amazon S3 requires of a multipart upload. The last part, and an upload of a single part, may be smaller.
   *
   * @param uploadMetadata the upload to complete.
   * @param completeParts the parts that complete the upload, in ascending order of their part number.
   * @param minimumPartSize the smallest size of a part that isn't the last one; {@code 0} to check nothing.
   * @throws EntityTooSmallException if a part that isn't the last one is smaller.
   */
  public static void assertPartsAreLargeEnough(UploadMetadata uploadMetadata,
                                               List<CompleteMultipartUploadPartOption> completeParts,
                                               long minimumPartSize) {
    if (minimumPartSize <= 0) {
      return;
    }

    // The last part may be smaller, so it isn't checked.
    for (int i = 0; i < completeParts.size() - 1; i++) {
      int partNumber = completeParts.get(i).getPartNumber();
      UploadPartMetadata part = uploadMetadata.getParts().get(partNumber);
      if (part != null && part.getSize() < minimumPartSize) {
        throw new EntityTooSmallException(partNumber, part.getSize(), minimumPartSize);
      }
    }
  }

  /**
   * Assert the object key is exists.
   *
   * @param bucketMetadata the bucket metadata.
   * @param key the object key.
   * @return the {@linkplain UploadMetadata} map of the specified key.
   */
  public static NavigableMap<String, UploadMetadata> assertKeyExists(BucketMetadata bucketMetadata, String key, String uploadId) {
    if (!bucketMetadata.getUploads().containsKey(key)) {
      throw new UploadNotExistException(key, uploadId);
    }
    return bucketMetadata.getUploads().get(key);
  }

  /**
   * Assert that the give upload exists.
   *
   * @param bucketMetadata the bucket metadata.
   * @param key the object key of the specified upload ID.
   * @param uploadId the generated upload ID when creating multipart upload.
   * @return the upload metadata of specified upload ID.
   */
  public static UploadMetadata assertUploadExists(BucketMetadata bucketMetadata, String key, String uploadId) {
    NavigableMap<String, UploadMetadata> uploadMetadataMap = assertKeyExists(bucketMetadata, key, uploadId);
    if (!uploadMetadataMap.containsKey(uploadId)) {
      throw new UploadNotExistException(key, uploadId);
    }
    return uploadMetadataMap.get(uploadId);
  }

  /**
   * Assert that the specified part number is exists in the {@code uploadMetadata}.
   *
   * @param uploadMetadata the upload metadata.
   * @param partNumber part number to verify.
   * @return the {@linkplain UploadPartMetadata} of the specified part number.
   */
  public static UploadPartMetadata assertPartNumberExists(UploadMetadata uploadMetadata, Integer partNumber) {
    if (!uploadMetadata.getParts().containsKey(partNumber)) {
      throw new IllegalArgumentException("Part number " + partNumber + " not exists.");
    }
    return uploadMetadata.getParts().get(partNumber);
  }

}
