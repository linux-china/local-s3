package com.robothy.s3.rest.netty;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.robothy.netty.http.HttpRequestHandler;
import com.robothy.netty.router.ExceptionHandler;
import com.robothy.netty.router.Router;
import com.robothy.s3.core.exception.S3ErrorCode;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.stream.ChunkedWriteHandler;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.SocketException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class LocalS3HttpPipelineTest {

  private static final long MAX_REQUEST_BODY_SIZE = 16;

  private static EmbeddedChannel channel(Router router) {
    return new EmbeddedChannel(new ChunkedWriteHandler(),
        new LocalS3HttpRequestDecoder(MAX_REQUEST_BODY_SIZE, new XmlMapper()),
        new LocalS3HttpResponseEncoder(),
        new LocalS3HttpMessageHandler(router));
  }

  private static Router router(HttpRequestHandler handler) {
    Router router = mock(Router.class);
    when(router.match(any())).thenReturn(handler);
    return router;
  }

  private static DefaultHttpRequest request(HttpMethod method, long contentLength) {
    DefaultHttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, method, "/bucket/key");
    request.headers().set(HttpHeaderNames.CONTENT_LENGTH, contentLength);
    return request;
  }

  private static ByteBuf pooled(byte[] data) {
    return PooledByteBufAllocator.DEFAULT.buffer().writeBytes(data);
  }

  private static InputStream closeTracking(byte[] data, AtomicBoolean closed) {
    return new ByteArrayInputStream(data) {
      @Override
      public void close() throws IOException {
        closed.set(true);
        super.close();
      }
    };
  }

  @Test
  void releasesRequestBodyAfterHandling() {
    AtomicReference<String> receivedBody = new AtomicReference<>();
    EmbeddedChannel channel = channel(router((request, response) -> {
      receivedBody.set(request.getBody().toString(StandardCharsets.UTF_8));
      response.write("ok");
    }));

    ByteBuf content = pooled("hello".getBytes(StandardCharsets.UTF_8));
    channel.writeInbound(request(HttpMethod.PUT, 5), new DefaultLastHttpContent(content));

    assertEquals("hello", receivedBody.get());
    assertEquals(0, content.refCnt());
    FullHttpResponse response = channel.readOutbound();
    assertEquals(HttpResponseStatus.OK, response.status());
    assertEquals("ok", response.content().toString(StandardCharsets.UTF_8));
    assertEquals("2", response.headers().get(HttpHeaderNames.CONTENT_LENGTH));
    response.release();
    assertFalse(channel.finishAndReleaseAll());
  }

  private static FullHttpResponse exchange(EmbeddedChannel channel, HttpVersion version, String connection) {
    DefaultHttpRequest request = new DefaultHttpRequest(version, HttpMethod.GET, "/bucket/key");
    if (connection != null) {
      request.headers().set(HttpHeaderNames.CONNECTION, connection);
    }
    channel.writeInbound(request, LastHttpContent.EMPTY_LAST_CONTENT);
    return channel.readOutbound();
  }

  @Test
  void keepsHttp11ConnectionAliveByDefault() {
    EmbeddedChannel channel = channel(router((request, response) -> response.write("ok")));

    FullHttpResponse response = exchange(channel, HttpVersion.HTTP_1_1, null);

    assertEquals(HttpHeaderValues.KEEP_ALIVE.toString(), response.headers().get(HttpHeaderNames.CONNECTION));
    response.release();
    assertTrue(channel.isOpen());
    assertFalse(channel.finishAndReleaseAll());
  }

  @Test
  void closesHttp11ConnectionWhenClientAsksToClose() {
    EmbeddedChannel channel = channel(router((request, response) -> response.write("ok")));

    FullHttpResponse response = exchange(channel, HttpVersion.HTTP_1_1, "Close");

    assertEquals(HttpHeaderValues.CLOSE.toString(), response.headers().get(HttpHeaderNames.CONNECTION));
    response.release();
    assertFalse(channel.isOpen());
  }

  @Test
  void closesConnectionWhenCloseIsOneOfTheConnectionTokens() {
    EmbeddedChannel channel = channel(router((request, response) -> response.write("ok")));

    FullHttpResponse response = exchange(channel, HttpVersion.HTTP_1_1, "keep-alive, close");

    assertEquals(HttpHeaderValues.CLOSE.toString(), response.headers().get(HttpHeaderNames.CONNECTION));
    response.release();
    assertFalse(channel.isOpen());
  }

  @Test
  void keepsHttp10ConnectionAliveOnlyWhenAsked() {
    EmbeddedChannel keepAlive = channel(router((request, response) -> response.write("ok")));
    FullHttpResponse keepAliveResponse = exchange(keepAlive, HttpVersion.HTTP_1_0, "Keep-Alive");
    assertEquals(HttpHeaderValues.KEEP_ALIVE.toString(), keepAliveResponse.headers().get(HttpHeaderNames.CONNECTION));
    keepAliveResponse.release();
    assertTrue(keepAlive.isOpen());
    assertFalse(keepAlive.finishAndReleaseAll());

    EmbeddedChannel close = channel(router((request, response) -> response.write("ok")));
    FullHttpResponse closeResponse = exchange(close, HttpVersion.HTTP_1_0, null);
    assertEquals(HttpHeaderValues.CLOSE.toString(), closeResponse.headers().get(HttpHeaderNames.CONNECTION));
    closeResponse.release();
    assertFalse(close.isOpen());
  }

  @Test
  void streamsResponseBodyInChunks() throws IOException {
    byte[] data = new byte[200_000];
    new Random(1).nextBytes(data);
    AtomicBoolean closed = new AtomicBoolean();
    EmbeddedChannel channel = channel(router((request, response) ->
        ((StreamingHttpResponse) response).stream(closeTracking(data, closed))
            .putHeader(HttpHeaderNames.CONTENT_LENGTH.toString(), data.length)));

    channel.writeInbound(request(HttpMethod.GET, 0), LastHttpContent.EMPTY_LAST_CONTENT);

    Object head = channel.readOutbound();
    assertInstanceOf(HttpResponse.class, head);
    assertFalse(head instanceof FullHttpResponse);
    assertEquals(String.valueOf(data.length), ((HttpResponse) head).headers().get(HttpHeaderNames.CONTENT_LENGTH));

    ByteArrayOutputStream received = new ByteArrayOutputStream();
    int chunks = 0;
    boolean last = false;
    Object message;
    while ((message = channel.readOutbound()) != null) {
      HttpContent content = (HttpContent) message;
      content.content().readBytes(received, content.content().readableBytes());
      last = content instanceof LastHttpContent;
      content.release();
      chunks++;
    }

    assertTrue(last);
    assertTrue(chunks > 1);
    assertArrayEquals(data, received.toByteArray());
    assertTrue(closed.get());
    channel.finishAndReleaseAll();
  }

  @Test
  void rejectsDeclaredOversizedBodyWithoutContinue() {
    AtomicBoolean handled = new AtomicBoolean();
    EmbeddedChannel channel = channel(router((request, response) -> handled.set(true)));

    DefaultHttpRequest request = request(HttpMethod.PUT, MAX_REQUEST_BODY_SIZE + 1);
    request.headers().set(HttpHeaderNames.EXPECT, HttpHeaderValues.CONTINUE);
    channel.writeInbound(request);

    FullHttpResponse response = channel.readOutbound();
    assertEquals(HttpResponseStatus.BAD_REQUEST, response.status());
    assertTrue(response.content().toString(StandardCharsets.UTF_8).contains("EntityTooLarge"));
    response.release();
    assertNull(channel.readOutbound());
    assertFalse(channel.isOpen());
    assertFalse(handled.get());
  }

  @Test
  void rejectsChunkedBodyExceedingLimitAndReleasesBufferedContent() {
    AtomicBoolean handled = new AtomicBoolean();
    EmbeddedChannel channel = channel(router((request, response) -> handled.set(true)));

    DefaultHttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.PUT, "/bucket/key");
    HttpUtil.setTransferEncodingChunked(request, true);
    ByteBuf first = pooled(new byte[10]);
    ByteBuf second = pooled(new byte[10]);
    channel.writeInbound(request, new DefaultHttpContent(first), new DefaultLastHttpContent(second));

    FullHttpResponse response = channel.readOutbound();
    assertEquals(HttpResponseStatus.BAD_REQUEST, response.status());
    response.release();
    assertEquals(0, first.refCnt());
    assertEquals(0, second.refCnt());
    assertFalse(channel.isOpen());
    assertFalse(handled.get());
  }

  @Test
  void sendsContinueForBodyWithinLimit() {
    EmbeddedChannel channel = channel(router((request, response) ->
        response.write(request.getBody().toString(StandardCharsets.UTF_8))));

    DefaultHttpRequest request = request(HttpMethod.PUT, 4);
    request.headers().set(HttpHeaderNames.EXPECT, HttpHeaderValues.CONTINUE);
    channel.writeInbound(request);
    FullHttpResponse continueResponse = channel.readOutbound();
    assertEquals(HttpResponseStatus.CONTINUE, continueResponse.status());
    continueResponse.release();

    channel.writeInbound(new DefaultLastHttpContent(Unpooled.copiedBuffer("abcd", StandardCharsets.UTF_8)));
    FullHttpResponse response = channel.readOutbound();
    assertEquals("abcd", response.content().toString(StandardCharsets.UTF_8));
    response.release();
    assertFalse(channel.finishAndReleaseAll());
  }

  @Test
  void closesAttachedStreamWhenHandlerFails() {
    AtomicBoolean closed = new AtomicBoolean();
    Router router = router((request, response) -> {
      ((StreamingHttpResponse) response).stream(closeTracking(new byte[8], closed));
      throw new IllegalStateException("boom");
    });
    ExceptionHandler<Throwable> exceptionHandler = (e, request, response) ->
        response.status(HttpResponseStatus.INTERNAL_SERVER_ERROR).write(e.getMessage());
    when(router.findExceptionHandler(any())).thenReturn(exceptionHandler);
    EmbeddedChannel channel = channel(router);

    channel.writeInbound(request(HttpMethod.GET, 0), LastHttpContent.EMPTY_LAST_CONTENT);

    assertTrue(closed.get());
    FullHttpResponse response = channel.readOutbound();
    assertEquals(HttpResponseStatus.INTERNAL_SERVER_ERROR, response.status());
    assertEquals("boom", response.content().toString(StandardCharsets.UTF_8));
    response.release();
    assertFalse(channel.finishAndReleaseAll());
  }

  @Test
  void closesConnectionWithoutResponseWhenClientResets() {
    EmbeddedChannel channel = channel(router((request, response) -> response.write("ok")));

    channel.pipeline().fireExceptionCaught(new SocketException("Connection reset"));

    assertNull(channel.readOutbound());
    assertFalse(channel.isOpen());
  }

  @Test
  void respondsWithServerErrorToUnexpectedException() {
    EmbeddedChannel channel = channel(router((request, response) -> response.write("ok")));

    channel.pipeline().fireExceptionCaught(new IllegalStateException("boom"));

    FullHttpResponse response = channel.readOutbound();
    assertEquals(HttpResponseStatus.INTERNAL_SERVER_ERROR, response.status());
    assertEquals(HttpHeaderValues.APPLICATION_XML.toString(), response.headers().get(HttpHeaderNames.CONTENT_TYPE));
    String body = response.content().toString(StandardCharsets.UTF_8);
    assertTrue(body.contains("<Code>InternalError</Code>"), body);
    assertFalse(body.contains("boom"), "The exception must not be revealed to the client.");
    response.release();
    assertFalse(channel.isOpen());
  }

  @Test
  void respondsWithS3ErrorWhenNoRouteMatches() {
    EmbeddedChannel channel = channel(router(null));

    channel.writeInbound(request(HttpMethod.PATCH, 0), LastHttpContent.EMPTY_LAST_CONTENT);

    FullHttpResponse response = channel.readOutbound();
    assertEquals(HttpResponseStatus.NOT_IMPLEMENTED, response.status());
    assertEquals(HttpHeaderValues.APPLICATION_XML.toString(), response.headers().get(HttpHeaderNames.CONTENT_TYPE));
    String body = response.content().toString(StandardCharsets.UTF_8);
    assertTrue(body.contains("<Code>NotImplemented</Code>"), body);
    response.release();
    assertFalse(channel.finishAndReleaseAll());
  }

  private static final RequestHeadVerifier REJECT_ALL =
      head -> new RequestHeadVerifier.Rejection(S3ErrorCode.SignatureDoesNotMatch, "The signature does not match.");

  private static EmbeddedChannel channel(Router router, RequestHeadVerifier headVerifier) {
    return new EmbeddedChannel(new ChunkedWriteHandler(),
        new LocalS3HttpRequestDecoder(MAX_REQUEST_BODY_SIZE, Long.MAX_VALUE, new XmlMapper(), headVerifier),
        new LocalS3HttpResponseEncoder(),
        new LocalS3HttpMessageHandler(router));
  }

  @Test
  void rejectsRequestWhoseHeadFailsVerificationWithoutContinue() {
    AtomicBoolean handled = new AtomicBoolean();
    EmbeddedChannel channel = channel(router((request, response) -> handled.set(true)), REJECT_ALL);

    DefaultHttpRequest request = request(HttpMethod.PUT, 4);
    request.headers().set(HttpHeaderNames.EXPECT, HttpHeaderValues.CONTINUE);
    channel.writeInbound(request);

    FullHttpResponse response = channel.readOutbound();
    assertEquals(HttpResponseStatus.valueOf(S3ErrorCode.SignatureDoesNotMatch.httpStatus()), response.status());
    assertEquals(HttpHeaderValues.CLOSE.toString(), response.headers().get(HttpHeaderNames.CONNECTION));
    String body = response.content().toString(StandardCharsets.UTF_8);
    assertTrue(body.contains("<Code>SignatureDoesNotMatch</Code>"), body);
    response.release();
    assertNull(channel.readOutbound(), "No 100 Continue is sent.");
    assertFalse(channel.isOpen());
    assertFalse(handled.get());
  }

  @Test
  void verifiesHeadOfRequestWithBodyBeforeReceivingTheBody() {
    AtomicReference<com.robothy.netty.http.HttpRequest> verifiedHead = new AtomicReference<>();
    EmbeddedChannel channel = channel(router((request, response) ->
        response.write(request.getBody().toString(StandardCharsets.UTF_8))), head -> {
          verifiedHead.set(head);
          return null;
        });

    channel.writeInbound(request(HttpMethod.PUT, 4));
    assertEquals("/bucket/key", verifiedHead.get().getPath());
    assertNull(verifiedHead.get().getBody());

    channel.writeInbound(new DefaultLastHttpContent(Unpooled.copiedBuffer("abcd", StandardCharsets.UTF_8)));
    FullHttpResponse response = channel.readOutbound();
    assertEquals("abcd", response.content().toString(StandardCharsets.UTF_8));
    response.release();
    assertFalse(channel.finishAndReleaseAll());
  }

  @Test
  void leavesRequestWithoutBodyToTheRouter() {
    EmbeddedChannel channel = channel(router((request, response) -> response.write("ok")), REJECT_ALL);

    channel.writeInbound(request(HttpMethod.GET, 0), LastHttpContent.EMPTY_LAST_CONTENT);

    FullHttpResponse response = channel.readOutbound();
    assertEquals(HttpResponseStatus.OK, response.status());
    response.release();
    assertTrue(channel.isOpen());
    assertFalse(channel.finishAndReleaseAll());
  }

}
