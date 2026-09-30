package com.robothy.netty.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RouterHttpRequestTest {

  @Test
  void headerNamesAreLowerCase() {
    RouterHttpRequest request = RouterHttpRequest.builder()
        .headers(Map.of("Content-Type", "text/plain", "X-Amz-Meta-Tag", "a"))
        .build();

    assertEquals(Map.of("content-type", "text/plain", "x-amz-meta-tag", "a"), request.getHeaders());
    assertEquals(Optional.of("text/plain"), request.header("Content-Type"));
    assertEquals(Optional.of("text/plain"), request.header("content-type"));
  }

  @Test
  void valuesOfNamesDifferingInCaseAreJoined() {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("X-Amz-Meta-Tag", "first");
    headers.put("x-amz-meta-tag", "second");
    headers.put("Cookie", "a=1");
    headers.put("cookie", "b=2");
    RouterHttpRequest request = RouterHttpRequest.builder().headers(headers).build();

    assertEquals(Optional.of("first,second"), request.header("x-amz-meta-tag"));
    // Cookie values are joined as the decoder joins them.
    assertEquals(Optional.of("a=1; b=2"), request.header("cookie"));
  }

  @Test
  void nullHeaderValuesAreLeftOut() {
    Map<String, String> headers = new HashMap<>();
    headers.put("Host", null);
    RouterHttpRequest request = RouterHttpRequest.builder().headers(headers).build();
    assertFalse(request.getHeaders().containsKey("host"));

    headers.put(null, "value");
    assertThrows(NullPointerException.class, () -> RouterHttpRequest.builder().headers(headers).build());
  }

}
