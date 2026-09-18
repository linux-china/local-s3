package com.robothy.s3.rest.utils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.robothy.netty.http.HttpRequest;
import com.robothy.s3.rest.netty.StreamingHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
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
    assertTrue(body.contains("<HostId>" + response.getHeaders().get("x-amz-id-2") + "</HostId>"), body);
  }

  @Test
  void leavesOutTheFieldsThatTheErrorDoesNotCarry() {
    StreamingHttpResponse response = new StreamingHttpResponse();
    ErrorResponses.notImplemented(request(HttpMethod.PATCH, Map.of()), response);

    // Amazon S3 names only what is relevant to an error; LocalS3 wrote every field, empty, through 2.4.
    String body = body(response);
    for (String field : new String[] {"ArgumentName", "ArgumentValue", "BucketName", "Key", "VersionId"}) {
      assertFalse(body.contains("<" + field), body);
    }
  }

  @Test
  void answersAHostIdOfTheShapeOfAmazonS3() {
    StreamingHttpResponse response = new StreamingHttpResponse();
    ErrorResponses.notImplemented(request(HttpMethod.PATCH, Map.of()), response);

    // 56 random bytes, which base64 writes as the 76 characters of an x-amz-id-2 of Amazon S3.
    String hostId = response.getHeaders().get("x-amz-id-2");
    assertEquals(76, hostId.length(), hostId);
    assertDoesNotThrow(() -> Base64.getDecoder().decode(hostId), hostId);
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
