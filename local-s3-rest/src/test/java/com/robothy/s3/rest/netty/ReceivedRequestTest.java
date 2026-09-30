package com.robothy.s3.rest.netty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.netty.http.HttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import java.util.HashMap;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * A received request is found by its request only on the thread that handles it, and only while it does.
 */
class ReceivedRequestTest {

  private static HttpRequest request() {
    return HttpRequest.builder().method(HttpMethod.GET).uri("/").path("/").headers(new HashMap<>())
        .params(new HashMap<>()).build();
  }

  @Test
  void isFoundByItsRequestWhileItIsHandled() {
    HttpRequest request = request();
    ReceivedRequest received = new ReceivedRequest(request, ConnectionSchemes.HTTPS, "verified");

    assertSame(received, received.handle(() -> ReceivedRequest.of(request).orElseThrow()));
    assertEquals(Optional.of(ConnectionSchemes.HTTPS), received.handle(() -> ConnectionSchemes.of(request)));
    assertTrue(ReceivedRequest.of(request).isEmpty(), "Nothing is left on the thread.");
  }

  @Test
  void isNotFoundByAnotherRequest() {
    ReceivedRequest received = new ReceivedRequest(request(), ConnectionSchemes.HTTP, null);
    HttpRequest other = request();

    assertTrue(received.handle(() -> ReceivedRequest.of(other)).isEmpty());
    assertTrue(received.handle(() -> ConnectionSchemes.of(other)).isEmpty());
  }

  @Test
  void isNotFoundOnAnotherThread() throws Exception {
    HttpRequest request = request();
    ReceivedRequest received = new ReceivedRequest(request, ConnectionSchemes.HTTP, null);

    Optional<?> onAnotherThread = received.handle(() -> {
      Optional<?>[] found = new Optional<?>[1];
      Thread thread = Thread.ofVirtual().start(() -> found[0] = ReceivedRequest.of(request));
      try {
        thread.join();
      } catch (InterruptedException e) {
        throw new IllegalStateException(e);
      }
      return found[0];
    });
    assertTrue(onAnotherThread.isEmpty());
  }

  @Test
  void restoresTheRequestItWasHandledWithin() {
    HttpRequest outer = request();
    HttpRequest inner = request();
    ReceivedRequest outerReceived = new ReceivedRequest(outer, null, null);
    ReceivedRequest innerReceived = new ReceivedRequest(inner, null, null);

    outerReceived.handle(() -> {
      innerReceived.handle(() -> ReceivedRequest.of(inner).orElseThrow());
      assertSame(outerReceived, ReceivedRequest.of(outer).orElseThrow());
      return null;
    });
  }

  @Test
  void isClearedWhenTheHandlingFails() {
    HttpRequest request = request();
    ReceivedRequest received = new ReceivedRequest(request, null, null);

    assertThrows(IllegalStateException.class, () -> received.handle(() -> {
      throw new IllegalStateException("failed");
    }));
    assertTrue(ReceivedRequest.of(request).isEmpty());
  }

}
