package com.robothy.s3.rest.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.robothy.netty.http.HttpRequest;
import com.robothy.netty.router.Router;
import com.robothy.s3.rest.netty.StreamingHttpResponse;
import com.robothy.s3.rest.service.ServiceFactory;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.core.JacksonException;
import tools.jackson.core.exc.StreamWriteException;
import tools.jackson.dataformat.xml.XmlMapper;

/**
 * The exception handlers of the router answer an exception that isn't a
 * {@linkplain com.robothy.s3.core.exception.LocalS3Exception} with an {@code InternalError} that doesn't reveal it,
 * an {@linkplain IllegalArgumentException} included. A request body that can't be read is the exception: an error of
 * the client, {@code MalformedXML}.
 */
class LocalS3RouterFactoryExceptionHandlerTest {

  private static final Router ROUTER =
      LocalS3RouterFactory.create(Mockito.mock(ServiceFactory.class, Mockito.RETURNS_MOCKS), null, null);

  /**
   * The {@code RequestId} of an error body, which {@linkplain #bodyWithoutTheRequestId} cuts out before the body
   * is searched for what an exception might have revealed.
   */
  private static final Pattern REQUEST_ID = Pattern.compile("<RequestId>[^<]*</RequestId>");

  private static StreamingHttpResponse handle(Exception exception) {
    HttpRequest request = HttpRequest.builder().method(HttpMethod.PUT).path("/bucket/key").build();
    StreamingHttpResponse response = new StreamingHttpResponse();
    ROUTER.findExceptionHandler(exception.getClass()).handle(exception, request, response);
    return response;
  }

  /**
   * The body of an error response without its {@code RequestId}, so that a test can search the whole document
   * for what an exception would have revealed. The ID is 16 random characters of {@code 0-9A-Z}, which contain
   * a given pair of them about once in a hundred responses, e.g. the {@code 42} below; searching a body with
   * the ID in it therefore fails a build now and then for a leak that never happened.
   */
  private static String bodyWithoutTheRequestId(StreamingHttpResponse response) {
    String body = response.getBody().toString(StandardCharsets.UTF_8);
    // The ID has to be there to be cut out; without this, a renamed element would silently be searched again.
    assertTrue(REQUEST_ID.matcher(body).find(), body);
    return REQUEST_ID.matcher(body).replaceAll("");
  }

  @Test
  void illegalArgumentExceptionIsAnInternalErrorThatDoesNotRevealItsMessage() {
    StreamingHttpResponse response = handle(new IllegalArgumentException("Object id='42' not exist."));

    assertEquals(HttpResponseStatus.INTERNAL_SERVER_ERROR, response.getStatus());
    String body = bodyWithoutTheRequestId(response);
    assertTrue(body.contains("<Code>InternalError</Code>"), body);
    assertFalse(body.contains("42"), body);
  }

  @Test
  void aBodyThatIsNotWellFormedIsMalformedXml() {
    StreamingHttpResponse response = handle(readFailure("garbage"));

    assertEquals(HttpResponseStatus.BAD_REQUEST, response.getStatus());
    String body = bodyWithoutTheRequestId(response);
    assertTrue(body.contains("<Code>MalformedXML</Code>"), body);
  }

  @Test
  void aBodyThatDoesNotFitTheModelIsMalformedXml() {
    StreamingHttpResponse response = handle(readFailure("<V><Status>Sideways</Status></V>"));

    assertEquals(HttpResponseStatus.BAD_REQUEST, response.getStatus());
    assertTrue(bodyWithoutTheRequestId(response).contains("<Code>MalformedXML</Code>"));
  }

  /**
   * A Jackson exception that isn't a failure to read a request, e.g. one of writing a response, is a failure of
   * LocalS3, not of the client.
   */
  @Test
  void aJacksonExceptionOfWritingIsAnInternalError() {
    StreamingHttpResponse response = handle(new StreamWriteException(null, "Can't write it."));

    assertEquals(HttpResponseStatus.INTERNAL_SERVER_ERROR, response.getStatus());
    assertTrue(bodyWithoutTheRequestId(response).contains("<Code>InternalError</Code>"));
  }

  /**
   * The exception that reading {@code xml} as a {@link Versioning} throws.
   */
  private static JacksonException readFailure(String xml) {
    return assertThrows(JacksonException.class, () -> new XmlMapper().readValue(xml, Versioning.class));
  }

  record Versioning(@JsonProperty("Status") Status status) {
  }

  enum Status { Enabled, Suspended }

  @Test
  void numberFormatExceptionIsAnInternalError() {
    StreamingHttpResponse response = handle(new NumberFormatException("For input string: \"abc\""));

    assertEquals(HttpResponseStatus.INTERNAL_SERVER_ERROR, response.getStatus());
    String body = bodyWithoutTheRequestId(response);
    assertFalse(body.contains("abc"), body);
  }

}
