package com.robothy.s3.core.exception;

/**
 * Thrown when a CORS configuration to put is invalid.
 */
public class InvalidCORSConfigurationException extends LocalS3Exception {

  /**
   * Construct an {@linkplain InvalidCORSConfigurationException} instance.
   *
   * @param message what is invalid.
   */
  public InvalidCORSConfigurationException(String message) {
    super(S3ErrorCode.InvalidRequest, message);
  }

}
