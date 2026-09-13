package com.robothy.s3.core.exception;

/**
 * A request that LocalS3 rejects with an S3 error that has no exception of its own, e.g.
 * {@linkplain S3ErrorCode#MissingContentLength} or {@linkplain S3ErrorCode#InvalidRequest}.
 *
 * <p>The message is sent to the client in the {@code Message} of the error, so it must describe the problem of
 * the request and never reveal internals of LocalS3. An exception that isn't a {@linkplain LocalS3Exception}, e.g.
 * an {@linkplain IllegalArgumentException}, is answered with {@code InternalError} instead, without its message.
 */
public class LocalS3RequestException extends LocalS3Exception {

  /**
   * Create an exception whose message is the description of the error code.
   *
   * @param s3ErrorCode the S3 error that the request is answered with.
   */
  public LocalS3RequestException(S3ErrorCode s3ErrorCode) {
    super(s3ErrorCode);
  }

  /**
   * Create an exception.
   *
   * @param s3ErrorCode the S3 error that the request is answered with.
   * @param message     the message of the error, which the client receives.
   */
  public LocalS3RequestException(S3ErrorCode s3ErrorCode, String message) {
    super(s3ErrorCode, message);
  }

}
