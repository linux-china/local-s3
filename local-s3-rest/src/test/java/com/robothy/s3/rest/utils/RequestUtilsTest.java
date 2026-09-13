package com.robothy.s3.rest.utils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.robothy.netty.http.HttpRequest;
import com.robothy.s3.core.exception.LocalS3InvalidArgumentException;
import com.robothy.s3.rest.constants.AmzHeaderNames;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RequestUtilsTest {

  @Test
  void testExtractTagging() {
    HttpRequest request = requestWithTagging("key1=value1&key2=value2");

    String[][] tagArray = RequestUtils.extractTagging(request).orElseThrow();

    assertArrayEquals(new String[][] {{"key1", "value1"}, {"key2", "value2"}}, tagArray);
  }

  @Test
  void testExtractTaggingDecodesComponentsAndPreservesEmptyValues() {
    HttpRequest request = requestWithTagging(
        "key%201=hello%20world&key2=&key3=value=with=equals&key4=value%2Bplus");

    String[][] tagArray = RequestUtils.extractTagging(request).orElseThrow();

    assertArrayEquals(new String[][] {
        {"key 1", "hello world"},
        {"key2", ""},
        {"key3", "value=with=equals"},
        {"key4", "value+plus"}
    }, tagArray);
  }

  @Test
  void testExtractTaggingRejectsInvalidFormatAndEncoding() {
    assertThrows(LocalS3InvalidArgumentException.class,
        () -> RequestUtils.extractTagging(requestWithTagging("invalid")));
    assertThrows(LocalS3InvalidArgumentException.class,
        () -> RequestUtils.extractTagging(requestWithTagging("=value")));
    assertThrows(LocalS3InvalidArgumentException.class,
        () -> RequestUtils.extractTagging(requestWithTagging("key=value&")));
    assertThrows(LocalS3InvalidArgumentException.class,
        () -> RequestUtils.extractTagging(requestWithTagging("key=%invalid")));
  }

  @Test
  void testExtractTaggingEnforcesCountAndLengthLimits() {
    String tenTags = "k0=v&k1=v&k2=v&k3=v&k4=v&k5=v&k6=v&k7=v&k8=v&k9=v";
    assertEquals(10, RequestUtils.extractTagging(requestWithTagging(tenTags)).orElseThrow().length);
    assertThrows(LocalS3InvalidArgumentException.class,
        () -> RequestUtils.extractTagging(requestWithTagging(tenTags + "&k10=v")));

    String maxLengthTag = "k".repeat(128) + "=" + "v".repeat(256);
    assertDoesNotThrow(() -> RequestUtils.extractTagging(requestWithTagging(maxLengthTag)));
    assertThrows(LocalS3InvalidArgumentException.class,
        () -> RequestUtils.extractTagging(requestWithTagging("k".repeat(129) + "=value")));
    assertThrows(LocalS3InvalidArgumentException.class,
        () -> RequestUtils.extractTagging(requestWithTagging("key=" + "v".repeat(257))));
  }

  private static HttpRequest requestWithTagging(String tagging) {
    HttpRequest request = mock(HttpRequest.class);
    when(request.header(AmzHeaderNames.X_AMZ_TAGGING)).thenReturn(Optional.of(tagging));
    return request;
  }
}
