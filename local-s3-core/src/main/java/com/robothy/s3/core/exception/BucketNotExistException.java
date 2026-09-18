package com.robothy.s3.core.exception;

/**
 * Bucket not exist exception. Client side exception.
 *
 * <p>The message is the one of Amazon S3, {@code The specified bucket does not exist.}; the bucket is reported
 * by the {@code <BucketName>} of the error, where Amazon S3 reports it.
 */
public class BucketNotExistException extends LocalS3Exception {

  /**
   * Construct a {@linkplain BucketNotExistException} instance.
   *
   * @param bucketName inputted bucket name that is invalid.
   */
  public BucketNotExistException(String bucketName) {
    super(bucketName, S3ErrorCode.NoSuchBucket);
  }

}
