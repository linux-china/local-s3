package com.robothy.s3.rest.netty;

import com.robothy.netty.http.RouterHttpRequest;
import com.robothy.s3.core.exception.S3ErrorCode;

/**
 * Verifies the head of a request, i.e. its request line and headers, before its body is received, so that a
 * request that fails anyway doesn't get to upload its body.
 *
 * <p>A verifier keeps nothing about the requests it verifies: what it verified in a head is its {@linkplain Accepted
 * state}, which the decoder of the connection keeps with the request, hands back to the other methods, and hands on
 * with the complete request as its {@linkplain ReceivedRequest#verification() verification}.
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
   * @return why the request is rejected, or what was verified of an accepted one; {@code null} to receive its body
   *     with nothing to keep.
   */
  Outcome verifyHead(RouterHttpRequest head);

  /**
   * Called once the body of a request whose head was accepted is received, with the complete request, before the
   * request is handed on. What it answers is handed on with the request, so that the verification of the complete
   * request doesn't repeat what the head verified. The default answers the state of the head as it is.
   *
   * @param head the head that {@linkplain #verifyHead} accepted.
   * @param request the complete request, with the headers, the parameters and the URI of the head, and its body.
   * @param state the state that {@linkplain #verifyHead} accepted the head with; {@code null} for none.
   * @return the verification of the request, see {@linkplain ReceivedRequest#verification()}; {@code null} for none.
   */
  default Object requestReceived(RouterHttpRequest head, RouterHttpRequest request, Object state) {
    return state;
  }

  /**
   * How the chunk signatures of the {@code aws-chunked} body of a request whose head was accepted are verified while
   * the body is received, if the body is large enough to be buffered in a file. The file then holds the decoded body,
   * see {@linkplain RequestBodies#awsChunkedTrailer}, whose chunk signatures the verifier needn't verify again. The
   * default verifies none of them.
   *
   * @param head the head that {@linkplain #verifyHead} accepted.
   * @param state the state that {@linkplain #verifyHead} accepted the head with; {@code null} for none.
   * @return verifies the chunk signatures of the body; {@code null} to buffer the body as it is received, e.g. for a
   *     verifier that verifies it once it is received.
   */
  default ChunkSignatures chunkSignatures(RouterHttpRequest head, Object state) {
    return ChunkSignatures.UNVERIFIED;
  }

  /**
   * What {@linkplain #verifyHead} decided about a head.
   */
  sealed interface Outcome permits Rejection, Accepted {
  }

  /**
   * Why a request is rejected.
   *
   * @param errorCode the S3 error to respond with.
   * @param message the error message.
   */
  record Rejection(S3ErrorCode errorCode, String message) implements Outcome {
  }

  /**
   * A head that is accepted, with what was verified of it.
   *
   * @param state what was verified, handed back to {@linkplain #chunkSignatures} and {@linkplain #requestReceived}.
   */
  record Accepted(Object state) implements Outcome {
  }

}
