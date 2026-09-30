package com.robothy.netty.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

}
