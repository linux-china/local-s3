package com.robothy.netty.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.robothy.netty.http.HttpRequest;
import com.robothy.netty.http.HttpResponse;
import com.robothy.netty.router.Router;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class HttpMessageHandlerTest {

  @ParameterizedTest
  @CsvSource(value = {
      "HTTP/1.1, NULL, true",
      "HTTP/1.1, close, false",
      "HTTP/1.1, Close, false",
      "HTTP/1.1, 'Upgrade, close', false",
      "HTTP/1.1, keep-alive, true",
      "HTTP/1.0, NULL, false",
      "HTTP/1.0, keep-alive, true",
      "HTTP/1.0, Keep-Alive, true",
      "HTTP/1.0, close, false",
  }, nullValues = "NULL")
  void keepAlive(String version, String connection, boolean keepAlive) {
    Router router = Router.router().route(HttpMethod.GET, "/hello", (request, response) -> response.write("hello"));
    EmbeddedChannel channel = new EmbeddedChannel(new HttpMessageHandler(router));

    Map<String, String> headers = new HashMap<>();
    if (connection != null) {
      headers.put("connection", connection);
    }
    channel.writeInbound(HttpRequest.builder()
        .method(HttpMethod.GET)
        .uri("/hello")
        .path("/hello")
        .httpVersion(HttpVersion.valueOf(version))
        .headers(headers)
        .params(new HashMap<>())
        .body(Unpooled.EMPTY_BUFFER)
        .build());

    HttpResponse response = channel.readOutbound();
    assertEquals(keepAlive ? "keep-alive" : "close", response.getHeaders().get("connection"));
    if (keepAlive) {
      assertTrue(channel.isOpen());
    } else {
      assertFalse(channel.isOpen());
    }
    response.getBody().release();
    channel.finishAndReleaseAll();
  }

  @ParameterizedTest
  @CsvSource(value = {
      "200, 0",
      "204, NULL",
      "304, NULL",
      "100, NULL",
  }, nullValues = "NULL")
  void contentLengthOnlyForResponsesWithContent(int status, String contentLength) {
    Router router = Router.router().route(HttpMethod.GET, "/status",
        (request, response) -> response.status(HttpResponseStatus.valueOf(status)));
    EmbeddedChannel channel = new EmbeddedChannel(new HttpMessageHandler(router));
    channel.writeInbound(request("/status"));

    HttpResponse response = channel.readOutbound();
    assertEquals(contentLength, response.getHeaders().get("content-length"));
    response.getBody().release();
    channel.finishAndReleaseAll();
  }

  @Test
  void keepContentLengthSetByHandlerOnNotModified() {
    // The length of the 200 response that a 304 stands for.
    Router router = Router.router().route(HttpMethod.GET, "/status", (request, response) -> response
        .status(HttpResponseStatus.NOT_MODIFIED)
        .putHeader("Content-Length", 5));
    EmbeddedChannel channel = new EmbeddedChannel(new HttpMessageHandler(router));
    channel.writeInbound(request("/status"));

    HttpResponse response = channel.readOutbound();
    assertEquals("5", response.getHeaders().get("content-length"));
    response.getBody().release();
    channel.finishAndReleaseAll();
  }

  private static HttpRequest request(String path) {
    return HttpRequest.builder()
        .method(HttpMethod.GET)
        .uri(path)
        .path(path)
        .httpVersion(HttpVersion.HTTP_1_1)
        .body(Unpooled.EMPTY_BUFFER)
        .build();
  }

  @Test
  void ioExceptionClosesWithoutResponse() {
    EmbeddedChannel channel = new EmbeddedChannel(new HttpMessageHandler(Router.router()));
    channel.pipeline().fireExceptionCaught(new IOException("Connection reset"));

    assertNull(channel.readOutbound());
    assertFalse(channel.isOpen());
    channel.finishAndReleaseAll();
  }

  @Test
  void otherExceptionAnswersInternalServerError() {
    EmbeddedChannel channel = new EmbeddedChannel(new HttpMessageHandler(Router.router()));
    channel.pipeline().fireExceptionCaught(new IllegalStateException("boom"));

    HttpResponse response = channel.readOutbound();
    assertEquals(500, response.getStatus().code());
    assertFalse(channel.isOpen());
    response.getBody().release();
    channel.finishAndReleaseAll();
  }

}
