package com.robothy.s3.rest.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.robothy.netty.http.HttpRequest;
import com.robothy.s3.rest.netty.StreamingHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ErrorResponsesTest {

  @Test
  void writesS3ErrorDocuments() {
    StreamingHttpResponse response = new StreamingHttpResponse();
    ErrorResponses.notImplemented(request(HttpMethod.PATCH, Map.of()), response);

    assertEquals(HttpResponseStatus.NOT_IMPLEMENTED, response.getStatus());
    assertEquals("application/xml", response.getHeaders().get("content-type"));
    String body = body(response);
    assertTrue(body.contains("<Code>NotImplemented</Code>"), body);
    assertTrue(body.contains("PATCH /bucket/key"), body);
    assertTrue(body.contains("<RequestId>" + response.getHeaders().get("x-amz-request-id") + "</RequestId>"), body);
  }

  @Test
  void omitsTheBodyForHeadRequests() {
    StreamingHttpResponse response = new StreamingHttpResponse();
    ErrorResponses.internalError(request(HttpMethod.HEAD, Map.of()), response);

    assertEquals(HttpResponseStatus.INTERNAL_SERVER_ERROR, response.getStatus());
    assertEquals(0, response.getBody().readableBytes());
  }

  @Test
  void writesJsonErrorsForS3VectorsRequests() {
    StreamingHttpResponse response = new StreamingHttpResponse();
    ErrorResponses.internalError(request(HttpMethod.POST, Map.of("content-type", "application/json")), response);

    assertEquals(HttpResponseStatus.INTERNAL_SERVER_ERROR, response.getStatus());
    assertEquals("InternalServerException", response.getHeaders().get("x-amzn-errortype"));
    assertEquals("application/json", response.getHeaders().get("content-type"));
    assertEquals("{\"message\":\"We encountered an internal error. Please try again.\"}", body(response));
  }

  private static HttpRequest request(HttpMethod method, Map<String, String> headers) {
    return HttpRequest.builder()
        .method(method)
        .uri("/bucket/key")
        .path("/bucket/key")
        .httpVersion(HttpVersion.HTTP_1_1)
        .headers(new HashMap<CharSequence, String>(headers))
        .params(new HashMap<>())
        .build();
  }

  private static String body(StreamingHttpResponse response) {
    return response.getBody().toString(StandardCharsets.UTF_8);
  }

}
