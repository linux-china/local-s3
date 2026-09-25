package com.robothy.s3.rest.netty;

import com.robothy.s3.core.exception.S3ErrorCode;

/**
 * The body of a request is rejected while it is received, e.g. because a chunk of an {@code aws-chunked} body doesn't
 * have the signature it carries. The request is answered with {@code errorCode}, like a request whose head is
 * rejected.
 */
final class RequestBodyRejection extends RuntimeException {

  private final S3ErrorCode errorCode;

  RequestBodyRejection(S3ErrorCode errorCode, String message) {
    super(message, null, false, false);
    this.errorCode = errorCode;
  }

  S3ErrorCode errorCode() {
    return errorCode;
  }

}
