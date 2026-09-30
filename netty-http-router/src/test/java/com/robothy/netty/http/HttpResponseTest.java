package com.robothy.netty.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import java.util.List;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class HttpResponseTest {

  @Test
  void manyWritesAreNotConsolidated() {
    HttpResponse response = new HttpResponse();
    int writes = 100;
    StringBuilder expected = new StringBuilder();
    for (int i = 0; i < writes; i++) {
      response.write("w" + i + ";");
      expected.append("w").append(i).append(';');
    }
    // Every write stays a component: consolidating them would copy the body over and over.
    assertEquals(writes, response.getBody().numComponents());
    assertEquals(expected.toString(), response.getBody().toString(StandardCharsets.UTF_8));
    response.getBody().release();
  }

  @Test
  void headerNamesAreCaseInsensitive() {
    HttpResponse response = new HttpResponse();
    response.putHeader("Content-Length", 10);
    response.getHeaders().putIfAbsent("content-length", "20");
    assertEquals(1, response.getHeaders().size());
    assertEquals("10", response.getHeaders().get("CONTENT-LENGTH"));
    response.getBody().release();
  }

  @Test
  void addHeaderKeepsEveryValue() {
    HttpResponse response = new HttpResponse();
    response.addHeader("Set-Cookie", "a=1").addHeader("set-cookie", "b=2");
    assertEquals(List.of("a=1", "b=2"), response.getHeaderValues("SET-COOKIE"));
    // The single-value view shows the last value.
    assertEquals("b=2", response.getHeaders().get("Set-Cookie"));
    assertEquals(1, response.getHeaders().size());

    // putHeader replaces every value.
    response.putHeader("Set-Cookie", "c=3");
    assertEquals(List.of("c=3"), response.getHeaderValues("Set-Cookie"));

    response.getHeaders().remove("set-cookie");
    assertEquals(List.of(), response.getHeaderValues("Set-Cookie"));
    assertNull(response.getHeaders().get("Set-Cookie"));
    response.getBody().release();
  }

}
