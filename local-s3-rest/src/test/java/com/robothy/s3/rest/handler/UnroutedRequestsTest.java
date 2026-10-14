package com.robothy.s3.rest.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import com.robothy.netty.http.RouterHttpRequest;
import com.robothy.netty.http.RouterHttpResponse;
import com.robothy.s3.rest.netty.OperationHandler;
import com.robothy.s3.rest.service.ServiceFactory;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * See {@code docs/apis.md#not-routed-at-all}.
 */
class UnroutedRequestsTest {

  private static LocalS3Router router;

  @BeforeAll
  static void createRouter() {
    router = (LocalS3Router) LocalS3RouterFactory.create(
        Mockito.mock(ServiceFactory.class, Mockito.RETURNS_MOCKS), null, null);
  }

  private static OperationHandler match(String method, String path, String query, Map<String, String> headers) {
    Map<String, List<String>> params = new HashMap<>();
    if (!query.isEmpty()) {
      for (String parameter : query.split("&")) {
        String[] nameValue = parameter.split("=", 2);
        params.put(nameValue[0], List.of(nameValue.length == 2 ? nameValue[1] : ""));
      }
    }
    Map<String, String> allHeaders = new HashMap<>(headers);
    allHeaders.put("Host", "localhost");
    return (OperationHandler) router.match(RouterHttpRequest.builder()
        .method(HttpMethod.valueOf(method))
        .path(path)
        .uri(query.isEmpty() ? path : path + "?" + query)
        .params(params)
        .headers(allHeaders)
        .build());
  }

  /**
   * Each of these would reach the operation of its method and path without parameters, named in the comment.
   */
  @ParameterizedTest
  @CsvSource({
      "GET, /bucket, delete&x=1",             // ListObjects with an unknown parameter beside the subresource.
      "PUT, /bucket, location",               // CreateBucket
      "POST, /bucket, tagging",               // PostObject
      "DELETE, /bucket, acl",                 // DeleteBucket
      "HEAD, /bucket, policy",                // HeadBucket
      "GET, /bucket/key, cors",               // GetObject
      "PUT, /bucket/key, uploadId=1",         // PutObject, without the partNumber of UploadPart
      "DELETE, /bucket/key, acl",             // DeleteObject
      "GET, /bucket/key, attributes",         // GetObject, without the header of GetObjectAttributes
  })
  void aSubresourceThatNoRouteIsForAnswersNotImplemented(String method, String path, String query) {
    assertEquals(LocalS3Router.NOT_FOUND_OPERATION, match(method, path, query, Map.of()).operation());
  }

  @ParameterizedTest
  @CsvSource({
      "GET, /bucket, '', ListObjects",
      "GET, /bucket, v=3&prefix=a, ListObjects",
      "GET, /bucket/key, versionId=1&partNumber=1, GetObject",
      "DELETE, /bucket/key, versionId=1, DeleteObject",
      "OPTIONS, /bucket, acl, BucketCorsPreflight",
      "OPTIONS, /bucket/key, tagging, ObjectCorsPreflight",
      "PUT, /bucket/key, uploadId=1&partNumber=1, UploadPart",
  })
  void parametersThatNameNoOperationAreLeftToTheOperation(String method, String path, String query,
                                                         String operation) {
    assertEquals(operation, match(method, path, query, Map.of()).operation());
  }

  @Test
  void theErrorNamesTheSubresource() throws Exception {
    RouterHttpRequest request = RouterHttpRequest.builder().method(HttpMethod.DELETE).path("/bucket/key")
        .uri("/bucket/key?acl").params(Map.of("acl", List.of(""))).headers(Map.of("Host", "localhost")).build();
    RouterHttpResponse response = mock(RouterHttpResponse.class, Mockito.RETURNS_SELF);
    router.match(request).handle(request, response);

    Mockito.verify(response).status(HttpResponseStatus.NOT_IMPLEMENTED);
    ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
    Mockito.verify(response).write(body.capture());
    assertTrue(body.getValue().contains("DELETE /bucket/key?acl"), body.getValue());
  }

  @ParameterizedTest
  @CsvSource({
      "GET, /v20180820/accesspoint, x-amz-account-id",
      "PUT, /v20180820/bucket/b, x-amz-outpost-id",
      "GET, /v20180820/jobs, Authorization",
  })
  void aRequestOfS3ControlAnswersNotImplemented(String method, String path, String header) {
    String value = "Authorization".equals(header)
        ? "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20260101/us-east-1/s3-control/aws4_request, "
            + "SignedHeaders=host, Signature=0"
        : "123456789012";
    assertEquals(LocalS3Router.S3_CONTROL_OPERATION, match(method, path, "", Map.of(header, value)).operation());
  }

  @Test
  void anObjectOfABucketNamedAfterTheVersionOfS3ControlIsNoS3ControlRequest() {
    assertEquals("GetObject", match("GET", "/v20180820/accesspoint", "", Map.of()).operation());
  }

}
