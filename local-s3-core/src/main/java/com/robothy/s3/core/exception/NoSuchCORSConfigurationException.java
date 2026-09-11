package com.robothy.s3.core.exception;

/**
 * Thrown when attempting to get the CORS configuration of a bucket that has none.
 */
public class NoSuchCORSConfigurationException extends LocalS3Exception {

  /**
   * Construct a {@linkplain NoSuchCORSConfigurationException} instance.
   *
   * @param bucketName name of the bucket.
   */
  public NoSuchCORSConfigurationException(String bucketName) {
    super(S3ErrorCode.NoSuchCORSConfiguration, "The CORS configuration does not exist for bucket: " + bucketName);
  }

}
