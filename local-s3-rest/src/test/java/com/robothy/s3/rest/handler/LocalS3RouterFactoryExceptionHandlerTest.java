package com.robothy.s3.rest.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.netty.http.HttpRequest;
import com.robothy.netty.router.Router;
import com.robothy.s3.rest.netty.StreamingHttpResponse;
import com.robothy.s3.rest.service.ServiceFactory;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * The exception handlers of the router answer an exception that isn't a
 * {@linkplain com.robothy.s3.core.exception.LocalS3Exception} with an {@code InternalError} that doesn't reveal it,
 * an {@linkplain IllegalArgumentException} included.
 */
class LocalS3RouterFactoryExceptionHandlerTest {

  private static final Router ROUTER =
      LocalS3RouterFactory.create(Mockito.mock(ServiceFactory.class, Mockito.RETURNS_MOCKS), null, null);

  private static StreamingHttpResponse handle(Exception exception) {
    HttpRequest request = HttpRequest.builder().method(HttpMethod.PUT).path("/bucket/key").build();
    StreamingHttpResponse response = new StreamingHttpResponse();
    ROUTER.findExceptionHandler(exception.getClass()).handle(exception, request, response);
    return response;
  }

  @Test
  void illegalArgumentExceptionIsAnInternalErrorThatDoesNotRevealItsMessage() {
    StreamingHttpResponse response = handle(new IllegalArgumentException("Object id='42' not exist."));

    assertEquals(HttpResponseStatus.INTERNAL_SERVER_ERROR, response.getStatus());
    String body = response.getBody().toString(StandardCharsets.UTF_8);
    assertTrue(body.contains("<Code>InternalError</Code>"), body);
    assertFalse(body.contains("42"), body);
  }

  @Test
  void numberFormatExceptionIsAnInternalError() {
    StreamingHttpResponse response = handle(new NumberFormatException("For input string: \"abc\""));

    assertEquals(HttpResponseStatus.INTERNAL_SERVER_ERROR, response.getStatus());
    assertFalse(response.getBody().toString(StandardCharsets.UTF_8).contains("abc"));
  }

}
