package com.robothy.s3.rest.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.robothy.netty.http.HttpRequest;
import com.robothy.s3.core.exception.LocalS3RequestException;
import com.robothy.s3.core.exception.S3ErrorCode;
import com.robothy.s3.core.model.internal.SystemMetadata;
import com.robothy.s3.datatypes.enums.StorageClass;
import com.robothy.s3.rest.netty.StreamingHttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SystemMetadataHeadersTest {

  private static HttpRequest request(Map<CharSequence, String> headers, Map<String, List<String>> params) {
    return HttpRequest.builder().headers(new HashMap<>(headers)).params(new HashMap<>(params)).build();
  }

  @Test
  void readsTheSystemMetadataOfTheRequest() {
    SystemMetadata systemMetadata = SystemMetadataHeaders.fromRequest(request(Map.of(
        "cache-control", "max-age=60",
        "content-disposition", "inline",
        "content-encoding", "gzip",
        "content-language", "de",
        "expires", "Thu, 01 Dec 2033 16:00:00 GMT",
        "x-amz-storage-class", "GLACIER"), Map.of()));

    assertEquals(new SystemMetadata("max-age=60", "inline", "gzip", "de", "Thu, 01 Dec 2033 16:00:00 GMT",
        StorageClass.GLACIER), systemMetadata);
  }

  @Test
  void theStandardStorageClassIsNotStoredAndAnUnknownOneIsRejected() {
    assertNull(SystemMetadataHeaders.fromRequest(request(Map.of("x-amz-storage-class", "STANDARD"), Map.of())));
    LocalS3RequestException exception = assertThrows(LocalS3RequestException.class,
        () -> SystemMetadataHeaders.fromRequest(request(Map.of("x-amz-storage-class", "COLD"), Map.of())));
    assertEquals(S3ErrorCode.InvalidStorageClass, exception.getS3ErrorCode());
  }

  @Test
  void requestWithoutSystemMetadataHasNone() {
    assertNull(SystemMetadataHeaders.fromRequest(request(Map.of("content-type", "text/plain"), Map.of())));
    // A request signed chunk by chunk has an aws-chunked content encoding only, which isn't stored.
    assertNull(SystemMetadataHeaders.fromRequest(request(Map.of("content-encoding", "aws-chunked"), Map.of())));
  }

  @Test
  void storesTheContentEncodingWithoutAwsChunked() {
    assertEquals("gzip", SystemMetadataHeaders.storedContentEncoding("aws-chunked,gzip"));
    assertEquals("gzip, br", SystemMetadataHeaders.storedContentEncoding("gzip, AWS-CHUNKED, br"));
    assertEquals("deflate, gzip", SystemMetadataHeaders.storedContentEncoding("deflate, gzip"));
    assertNull(SystemMetadataHeaders.storedContentEncoding("aws-chunked"));
    assertNull(SystemMetadataHeaders.storedContentEncoding(null));
  }

  @Test
  void responseParametersOverrideTheHeadersOfTheObject() {
    SystemMetadata systemMetadata = SystemMetadata.builder()
        .cacheControl("max-age=60")
        .contentDisposition("inline")
        .build();
    StreamingHttpResponse response = new StreamingHttpResponse();

    SystemMetadataHeaders.addResponseHeaders(request(Map.of(), Map.of(
        "response-content-type", List.of("application/octet-stream"),
        "response-content-disposition", List.of("attachment; filename=\"a.txt\""),
        "response-expires", List.of("Thu, 01 Dec 2033 16:00:00 GMT"))), response, "text/plain", systemMetadata);

    Map<String, String> headers = response.getHeaders();
    assertEquals("application/octet-stream", headers.get("content-type"));
    assertEquals("attachment; filename=\"a.txt\"", headers.get("content-disposition"));
    assertEquals("Thu, 01 Dec 2033 16:00:00 GMT", headers.get("expires"));
    assertEquals("max-age=60", headers.get("cache-control"));
    assertFalse(headers.containsKey("content-encoding"));
    assertFalse(headers.containsKey("content-language"));
  }

  @Test
  void anObjectStoredWithoutAContentTypeIsServedWithTheDefaultOne() {
    StreamingHttpResponse response = new StreamingHttpResponse();

    SystemMetadataHeaders.addResponseHeaders(request(Map.of(), Map.of()), response, null, null);

    // Amazon S3 always answers a Content-Type, binary/octet-stream for an object stored without one.
    assertEquals("binary/octet-stream", response.getHeaders().get("content-type"));
  }

  @Test
  void theResponseParameterOverridesTheDefaultContentTypeToo() {
    StreamingHttpResponse response = new StreamingHttpResponse();

    SystemMetadataHeaders.addResponseHeaders(
        request(Map.of(), Map.of("response-content-type", List.of("text/csv"))), response, null, null);

    assertEquals("text/csv", response.getHeaders().get("content-type"));
  }

}
