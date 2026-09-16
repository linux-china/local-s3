package com.robothy.s3.core.exception;

/**
 * Thrown when attempting to get the lifecycle configuration of a bucket that has none.
 */
public class NoSuchLifecycleConfigurationException extends LocalS3Exception {

  /**
   * Construct a {@linkplain NoSuchLifecycleConfigurationException} instance.
   *
   * @param bucketName name of the bucket.
   */
  public NoSuchLifecycleConfigurationException(String bucketName) {
    super(bucketName, S3ErrorCode.NoSuchLifecycleConfiguration);
  }

}
