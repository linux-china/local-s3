package com.robothy.netty.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.robothy.netty.http.RouterHttpRequest;
import com.robothy.netty.http.RouterHttpResponse;
import com.robothy.netty.router.Router;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;

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
    channel.writeInbound(RouterHttpRequest.builder()
        .method(HttpMethod.GET)
        .uri("/hello")
        .path("/hello")
        .httpVersion(HttpVersion.valueOf(version))
        .headers(headers)
        .params(new HashMap<>())
        .body(Unpooled.EMPTY_BUFFER)
        .build());

    RouterHttpResponse response = channel.readOutbound();
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

    RouterHttpResponse response = channel.readOutbound();
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

    RouterHttpResponse response = channel.readOutbound();
    assertEquals("5", response.getHeaders().get("content-length"));
    response.getBody().release();
    channel.finishAndReleaseAll();
  }

  @ParameterizedTest
  @CsvSource(value = {
      "10, 5, true",
      "3, 5, true",
      "abc, 5, true",
      "5, 5, false",
      "' 5', 5, false",
      "NULL, 5, false",
  }, nullValues = "NULL")
  void contentLengthOfBufferedBodyIsItsLength(String declared, String sent, boolean warned) {
    Router router = Router.router().route(HttpMethod.GET, "/body", (request, response) -> {
      response.write("hello");
      if (declared != null) {
        response.putHeader("Content-Length", declared);
      }
    });
    Logger logger = (Logger) LoggerFactory.getLogger(HttpMessageHandler.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    EmbeddedChannel channel = new EmbeddedChannel(new HttpMessageHandler(router));
    try {
      channel.writeInbound(request("/body"));
      RouterHttpResponse response = channel.readOutbound();
      assertEquals(sent, response.getHeaders().get("content-length"));
      response.getBody().release();
    } finally {
      logger.detachAppender(appender);
      channel.finishAndReleaseAll();
    }
    List<ILoggingEvent> warnings = appender.list.stream()
        .filter(event -> event.getLevel() == Level.WARN)
        .toList();
    assertEquals(warned ? 1 : 0, warnings.size());
    if (warned) {
      assertEquals("The Content-Length " + declared + " of the response to GET /body is not the body length 5, "
          + "sent 5 instead.", warnings.get(0).getFormattedMessage());
    }
  }

  @Test
  void keepContentLengthSetByHandlerOnHead() {
    // The length of the object that a HEAD response describes without its body.
    Router router = Router.router().route(HttpMethod.HEAD, "/object",
        (request, response) -> response.putHeader("Content-Length", 1024));
    EmbeddedChannel channel = new EmbeddedChannel(new HttpMessageHandler(router));
    channel.writeInbound(RouterHttpRequest.builder()
        .method(HttpMethod.HEAD)
        .uri("/object")
        .path("/object")
        .httpVersion(HttpVersion.HTTP_1_1)
        .body(Unpooled.EMPTY_BUFFER)
        .build());

    RouterHttpResponse response = channel.readOutbound();
    assertEquals("1024", response.getHeaders().get("content-length"));
    response.getBody().release();
    channel.finishAndReleaseAll();
  }

  @ParameterizedTest
  @CsvSource(value = {
      // No body and no Content-Length: the length is unknown, not 0.
      "'', NULL",
      // A GET route that HEAD falls back to: the length of its body.
      "hello, 5",
  }, nullValues = "NULL")
  void contentLengthOfHeadResponse(String body, String contentLength) {
    Router router = Router.router().route(HttpMethod.HEAD, "/object", (request, response) -> response.write(body));
    EmbeddedChannel channel = new EmbeddedChannel(new HttpMessageHandler(router));
    channel.writeInbound(RouterHttpRequest.builder()
        .method(HttpMethod.HEAD)
        .uri("/object")
        .path("/object")
        .httpVersion(HttpVersion.HTTP_1_1)
        .body(Unpooled.EMPTY_BUFFER)
        .build());

    RouterHttpResponse response = channel.readOutbound();
    assertEquals(contentLength, response.getHeaders().get("content-length"));
    response.getBody().release();
    channel.finishAndReleaseAll();
  }

  @Test
  void exceptionAnsweredWithClientErrorIsLoggedAtDebug() {
    Router router = Router.router()
        .route(HttpMethod.GET, "/missing", (request, response) -> {
          throw new NoSuchElementException("NoSuchKey");
        })
        .exceptionHandler(NoSuchElementException.class,
            (e, request, response) -> response.status(HttpResponseStatus.NOT_FOUND));

    List<ILoggingEvent> events = logOf(router, "/missing", HttpResponseStatus.NOT_FOUND);
    assertEquals(1, events.size());
    assertEquals(Level.DEBUG, events.get(0).getLevel());
    assertEquals("GET /missing answered 404: java.util.NoSuchElementException: NoSuchKey",
        events.get(0).getFormattedMessage());
    assertNull(events.get(0).getThrowableProxy());
  }

  @ParameterizedTest
  @CsvSource({"false", "true"})
  void exceptionAnsweredWithServerErrorIsLoggedAtError(boolean catchAllHandler) {
    Router router = Router.router().route(HttpMethod.GET, "/fail", (request, response) -> {
      throw new IllegalStateException("boom");
    });
    if (catchAllHandler) {
      // A catch-all handler, like the one of LocalS3 for Exception, still answers with a server error.
      router.exceptionHandler(Exception.class,
          (e, request, response) -> response.status(HttpResponseStatus.INTERNAL_SERVER_ERROR));
    }

    List<ILoggingEvent> events = logOf(router, "/fail", HttpResponseStatus.INTERNAL_SERVER_ERROR);
    assertEquals(1, events.size());
    assertEquals(Level.ERROR, events.get(0).getLevel());
    assertEquals("Failed to handle GET /fail, answered 500.", events.get(0).getFormattedMessage());
    assertEquals("boom", events.get(0).getThrowableProxy().getMessage());
  }

  @Test
  void failedExceptionHandlerIsLoggedWithTheExceptionItHandled() {
    Router router = Router.router()
        .route(HttpMethod.GET, "/fail", (request, response) -> {
          throw new IllegalStateException("boom");
        })
        .exceptionHandler(IllegalStateException.class, (e, request, response) -> {
          throw new IllegalArgumentException("handler failed");
        });

    List<ILoggingEvent> events = logOf(router, "/fail", HttpResponseStatus.INTERNAL_SERVER_ERROR);
    assertEquals(1, events.size());
    assertEquals(Level.ERROR, events.get(0).getLevel());
    IThrowableProxy logged = events.get(0).getThrowableProxy();
    assertEquals("handler failed", logged.getMessage());
    assertEquals("boom", logged.getSuppressed()[0].getMessage());
  }

  /**
   * Handle a GET of {@code path}, and return what the handler logged at debug or above.
   */
  private static List<ILoggingEvent> logOf(Router router, String path, HttpResponseStatus expectedStatus) {
    Logger logger = (Logger) LoggerFactory.getLogger(HttpMessageHandler.class);
    Level previousLevel = logger.getLevel();
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    logger.setLevel(Level.DEBUG);
    EmbeddedChannel channel = new EmbeddedChannel(new HttpMessageHandler(router));
    try {
      appender.list.clear();
      channel.writeInbound(request(path));
      RouterHttpResponse response = channel.readOutbound();
      assertEquals(expectedStatus, response.getStatus());
      response.getBody().release();
    } finally {
      logger.detachAppender(appender);
      logger.setLevel(previousLevel);
      channel.finishAndReleaseAll();
    }
    // The channel, the request and the response are logged at debug too; only the failure is of interest.
    return appender.list.stream()
        .filter(event -> event.getLevel().isGreaterOrEqual(Level.WARN)
            || event.getFormattedMessage().contains(" answered "))
        .toList();
  }

  private static RouterHttpRequest request(String path) {
    return RouterHttpRequest.builder()
        .method(HttpMethod.GET)
        .uri(path)
        .path(path)
        .httpVersion(HttpVersion.HTTP_1_1)
        .body(Unpooled.EMPTY_BUFFER)
        .build();
  }

  @ParameterizedTest
  @CsvSource(value = {
      "authorization, AWS4-HMAC-SHA256 Credential=AKID/20260930/us-east-1/s3/aws4_request, AWS4-HMA***",
      "proxy-authorization, Basic dXNlcjpwYXNz, Basic dX***",
      "cookie, session=secret, session=***",
      "x-amz-security-token, short, ***",
      "host, localhost:8080, localhost:8080",
  })
  void sensitiveHeadersAreMaskedInLog(String name, String value, String logged) {
    assertEquals(logged, HttpMessageHandler.headerValueForLog(name, value));
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

    RouterHttpResponse response = channel.readOutbound();
    assertEquals(500, response.getStatus().code());
    assertFalse(channel.isOpen());
    response.getBody().release();
    channel.finishAndReleaseAll();
  }

}
