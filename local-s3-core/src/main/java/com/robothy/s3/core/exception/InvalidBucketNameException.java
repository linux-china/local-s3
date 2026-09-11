package com.robothy.s3.core.exception;

/**
 * Invalid bucket name exception. Client side error.
 */
public class InvalidBucketNameException extends LocalS3Exception {

  /**
   * Construct an instance.
   *
   * @param bucketName invalid bucket name.
   */
  public InvalidBucketNameException(String bucketName) {
    super(S3ErrorCode.InvalidBucketName, "The bucket name '" + bucketName + "' is invalid.");
  }

  /**
   * Construct an instance that names the rule the bucket name breaks.
   *
   * @param bucketName invalid bucket name.
   * @param reason the naming rule that the bucket name breaks.
   */
  public InvalidBucketNameException(String bucketName, String reason) {
    super(S3ErrorCode.InvalidBucketName, "The bucket name '" + bucketName + "' is invalid. " + reason);
  }

}
