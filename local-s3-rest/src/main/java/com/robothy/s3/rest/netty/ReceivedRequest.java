package com.robothy.s3.rest.netty;

import com.robothy.netty.http.HttpRequest;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * A request that {@linkplain LocalS3HttpRequestDecoder} received, with what the connection knows about it and
 * {@linkplain HttpRequest} can't carry, since it carries nothing but what the client sent: the scheme it arrived by and
 * what {@linkplain RequestHeadVerifier} verified of it before its body was received.
 *
 * <p>The decoder hands it to {@linkplain LocalS3HttpMessageHandler}, which binds it to the thread that routes and
 * handles the request, for as long as that takes; the router and the controllers read it with {@linkplain #of}. So
 * nothing about a request is kept anywhere else: no map by request, no lock that the requests of a service, or the
 * services of a JVM, share, and nothing left for the garbage collector to drop.
 *
 * @param request the request.
 * @param scheme {@code http} or {@code https} if the request arrived on a port that answers both, see
 *     {@linkplain ConnectionSchemes}; {@code null} if the port serves one of them.
 * @param verification what the verifier verified of the head of the request, see
 *     {@linkplain RequestHeadVerifier#requestReceived}; {@code null} if it verified nothing before the body.
 */
public record ReceivedRequest(HttpRequest request, String scheme, Object verification) {

  private static final ThreadLocal<ReceivedRequest> CURRENT = new ThreadLocal<>();

  public ReceivedRequest {
    Objects.requireNonNull(request, "request");
  }

  /**
   * The received request that the current thread handles, if it is {@code request}.
   *
   * @param request the request, compared by identity, since {@linkplain HttpRequest} doesn't override {@code equals}.
   * @return the received request; empty if the thread handles another request or none, e.g. a request that a test
   *     built and routed itself.
   */
  public static Optional<ReceivedRequest> of(HttpRequest request) {
    ReceivedRequest current = CURRENT.get();
    return current != null && current.request == request ? Optional.of(current) : Optional.empty();
  }

  /**
   * Run {@code action} with this request bound to the current thread, e.g. while it is routed and handled.
   *
   * @param action the action.
   * @return what the action answers.
   */
  public <T> T handle(Supplier<T> action) {
    ReceivedRequest previous = CURRENT.get();
    CURRENT.set(this);
    try {
      return action.get();
    } finally {
      if (previous == null) {
        CURRENT.remove();
      } else {
        CURRENT.set(previous);
      }
    }
  }

}
