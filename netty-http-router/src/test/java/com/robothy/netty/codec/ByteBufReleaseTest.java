package com.robothy.netty.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.robothy.netty.http.HttpResponse;
import com.robothy.netty.router.Router;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpChunkedInput;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.stream.ChunkedStream;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ByteBufReleaseTest {

  private static EmbeddedChannel channel(Router router) {
    return new EmbeddedChannel(new HttpRequestDecoder(), new HttpResponseEncoder(), new HttpMessageHandler(router));
  }

  private static ByteBuf content(String text) {
    return Unpooled.copiedBuffer(text, StandardCharsets.UTF_8);
  }

  private static void writeRequest(EmbeddedChannel channel, ByteBuf... contents) {
    channel.writeInbound(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.PUT, "/upload"));
    for (int i = 0; i < contents.length - 1; i++) {
      channel.writeInbound(new DefaultHttpContent(contents[i]));
    }
    channel.writeInbound(new DefaultLastHttpContent(contents[contents.length - 1]));
  }

  @Test
  void releaseRequestBodyAfterHandled() {
    Router router = Router.router().route(HttpMethod.PUT, "/upload",
        (request, response) -> response.write(request.getBody().readableBytes() + " bytes"));
    EmbeddedChannel channel = channel(router);
    ByteBuf part1 = content("hello ");
    ByteBuf part2 = content("world");
    writeRequest(channel, part1, part2);

    FullHttpResponse response = channel.readOutbound();
    assertEquals("11 bytes", response.content().toString(StandardCharsets.UTF_8));
    response.release();
    assertEquals(0, part1.refCnt());
    assertEquals(0, part2.refCnt());
    channel.finishAndReleaseAll();
  }

  @Test
  void releaseResponseBodyReplacedByExceptionHandler() {
    AtomicReference<HttpResponse> failedResponse = new AtomicReference<>();
    Router router = Router.router().route(HttpMethod.PUT, "/upload", (request, response) -> {
      failedResponse.set(response.write("partial"));
      throw new IllegalStateException("failed");
    }).exceptionHandler(IllegalStateException.class,
        (e, request, response) -> response.status(HttpResponseStatus.CONFLICT).write(e.getMessage()));
    EmbeddedChannel channel = channel(router);
    ByteBuf body = content("data");
    writeRequest(channel, body);

    FullHttpResponse response = channel.readOutbound();
    assertEquals(HttpResponseStatus.CONFLICT, response.status());
    response.release();
    assertEquals(0, failedResponse.get().getBody().refCnt());
    assertEquals(0, body.refCnt());
    channel.finishAndReleaseAll();
  }

  @Test
  void releaseResponseBodyIfWriteFailed() {
    AtomicReference<HttpResponse> writtenResponse = new AtomicReference<>();
    Router router = Router.router().route(HttpMethod.PUT, "/upload",
        // An invalid header name fails the encoder, so the body is never handed over to netty.
        (request, response) -> writtenResponse.set(response.putHeader("invalid header", "value").write("data")));
    EmbeddedChannel channel = channel(router);
    ByteBuf body = content("data");
    writeRequest(channel, body);

    assertNull(channel.readOutbound());
    assertEquals(0, writtenResponse.get().getBody().refCnt());
    assertEquals(0, body.refCnt());
    channel.finishAndReleaseAll();
  }

  /**
   * A chunked body that records whether it was closed.
   */
  private static class TrackedInput extends ChunkedStream {

    private boolean closed;

    TrackedInput(String content) {
      super(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
    }

    @Override
    public void close() throws Exception {
      closed = true;
      super.close();
    }
  }

  @Test
  void closeChunkedBodyReplacedByExceptionHandler() {
    TrackedInput input = new TrackedInput("partial");
    Router router = Router.router().route(HttpMethod.PUT, "/upload", (request, response) -> {
      response.chunkedBody(input);
      throw new IllegalStateException("failed");
    });
    EmbeddedChannel channel = channel(router);
    writeRequest(channel, content("data"));

    FullHttpResponse response = channel.readOutbound();
    assertEquals(HttpResponseStatus.INTERNAL_SERVER_ERROR, response.status());
    response.release();
    assertTrue(input.closed);
    channel.finishAndReleaseAll();
  }

  @Test
  void closeChunkedBodyIfEncoderFailed() {
    TrackedInput input = new TrackedInput("data");
    Router router = Router.router().route(HttpMethod.PUT, "/upload",
        (request, response) -> response.putHeader("invalid header", "value").chunkedBody(input));
    EmbeddedChannel channel = channel(router);
    writeRequest(channel, content("data"));

    assertNull(channel.readOutbound());
    assertTrue(input.closed);
    assertFalse(channel.isOpen());
    channel.finishAndReleaseAll();
  }

  @Test
  void chunkedBodyIsSentAfterItsHead() throws Exception {
    TrackedInput input = new TrackedInput("streamed");
    Router router = Router.router().route(HttpMethod.PUT, "/upload",
        (request, response) -> response.write("dropped").chunkedBody(input));
    EmbeddedChannel channel = channel(router);
    writeRequest(channel, content("data"));

    io.netty.handler.codec.http.HttpResponse head = channel.readOutbound();
    assertFalse(head instanceof FullHttpResponse);
    // The length of a stream is unknown.
    assertTrue(HttpUtil.isTransferEncodingChunked(head));
    assertNull(head.headers().get(HttpHeaderNames.CONTENT_LENGTH));
    HttpChunkedInput body = channel.readOutbound();
    HttpContent chunk = body.readChunk(ByteBufAllocator.DEFAULT);
    assertEquals("streamed", chunk.content().toString(StandardCharsets.UTF_8));
    chunk.release();
    body.close();
    assertTrue(input.closed);
    channel.finishAndReleaseAll();
  }

  @ParameterizedTest
  @CsvSource({"HEAD, 200", "PUT, 304"})
  void closeChunkedBodyOfResponseWithoutContent(String method, int status) {
    TrackedInput input = new TrackedInput("data");
    Router router = Router.router().route(HttpMethod.valueOf(method), "/upload", (request, response) -> response
        .status(HttpResponseStatus.valueOf(status))
        .chunkedBody(input));
    EmbeddedChannel channel = channel(router);
    channel.writeInbound(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.valueOf(method), "/upload"),
        new DefaultLastHttpContent());

    FullHttpResponse response = channel.readOutbound();
    assertEquals(0, response.content().readableBytes());
    response.release();
    assertTrue(input.closed);
    channel.finishAndReleaseAll();
  }

  @Test
  void releaseUnfinishedRequestBodyIfChannelClosed() {
    Router router = Router.router().route(HttpMethod.PUT, "/upload", (request, response) -> response.write("ok"));
    EmbeddedChannel channel = channel(router);
    ByteBuf part1 = content("hello ");
    channel.writeInbound(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.PUT, "/upload"));
    channel.writeInbound(new DefaultHttpContent(part1));
    assertEquals(1, part1.refCnt());

    channel.close();
    assertEquals(0, part1.refCnt());
    channel.finishAndReleaseAll();
  }

  @Test
  void releaseUnfinishedRequestBodyIfReplaced() {
    Router router = Router.router().route(HttpMethod.PUT, "/upload", (request, response) -> response.write("ok"));
    EmbeddedChannel channel = channel(router);
    ByteBuf unfinished = content("hello ");
    channel.writeInbound(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.PUT, "/upload"));
    channel.writeInbound(new DefaultHttpContent(unfinished));

    ByteBuf body = content("data");
    writeRequest(channel, body);
    FullHttpResponse response = channel.readOutbound();
    assertNotNull(response);
    response.release();
    assertEquals(0, unfinished.refCnt());
    assertEquals(0, body.refCnt());
    channel.finishAndReleaseAll();
  }

}
