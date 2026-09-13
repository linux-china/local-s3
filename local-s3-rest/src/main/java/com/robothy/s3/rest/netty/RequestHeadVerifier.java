package com.robothy.s3.rest.netty;

import com.robothy.netty.http.HttpRequest;
import com.robothy.s3.core.exception.S3ErrorCode;

/**
 * Verifies the head of a request, i.e. its request line and headers, before its body is received, so that a
 * request that fails anyway doesn't get to upload its body.
 */
@FunctionalInterface
public interface RequestHeadVerifier {

  /**
   * Accepts all requests.
   */
  RequestHeadVerifier ACCEPT_ALL = head -> null;

  /**
   * Verify the head of a request.
   *
   * @param head the request without its body.
   * @return why the request is rejected; {@code null} to receive its body.
   */
  Rejection verifyHead(HttpRequest head);

  /**
   * Called once the body of a request whose head was accepted is received, with the complete request, before the
   * request is handed on. A verifier that keeps what it verified in the head can hand it on to the request here, so
   * that the verification of the complete request doesn't repeat it. The default does nothing.
   *
   * @param head the head that {@linkplain #verifyHead} accepted.
   * @param request the complete request, with the headers, the parameters and the URI of the head, and its body.
   */
  default void requestReceived(HttpRequest head, HttpRequest request) {
  }

  /**
   * Why a request is rejected.
   *
   * @param errorCode the S3 error to respond with.
   * @param message the error message.
   */
  record Rejection(S3ErrorCode errorCode, String message) {
  }

}
