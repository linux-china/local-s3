package com.robothy.s3.core.exception;

/**
 * Thrown when a CORS preflight request is rejected, e.g. because no CORS rule of the bucket allows it.
 */
public class InvalidCORSRequestException extends LocalS3Exception {

  /**
   * Construct an {@linkplain InvalidCORSRequestException} instance.
   *
   * @param s3ErrorCode the error code, e.g. {@code AccessForbidden}.
   * @param message why the request is rejected.
   */
  public InvalidCORSRequestException(S3ErrorCode s3ErrorCode, String message) {
    super(s3ErrorCode, message);
  }

}
