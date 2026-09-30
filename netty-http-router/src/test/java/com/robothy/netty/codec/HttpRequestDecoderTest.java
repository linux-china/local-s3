package com.robothy.netty.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.robothy.netty.http.HttpRequest;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.ReferenceCountUtil;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class HttpRequestDecoderTest {

  private static ByteBuf content(String text) {
    return Unpooled.copiedBuffer(text, StandardCharsets.UTF_8);
  }

  private static void assertRejected(EmbeddedChannel channel, HttpResponseStatus status) {
    FullHttpResponse response = channel.readOutbound();
    assertEquals(status, response.status());
    assertEquals("close", response.headers().get(HttpHeaderNames.CONNECTION));
    response.release();
    assertNull(channel.readOutbound());
    assertNull(channel.readInbound());
    assertFalse(channel.isOpen());
  }

  @Test
  void invalidMaxRequestBodySize() {
    assertThrows(IllegalArgumentException.class, () -> new HttpRequestDecoder(0));
  }

  @Test
  void rejectTooLargeContentLengthBeforeContinue() {
    EmbeddedChannel channel = new EmbeddedChannel(new HttpRequestDecoder(4));
    DefaultHttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.PUT, "/upload");
    request.headers().set(HttpHeaderNames.CONTENT_LENGTH, 5)
        .set(HttpHeaderNames.EXPECT, HttpHeaderValues.CONTINUE);
    channel.writeInbound(request);
    // The 413 response, not 100 Continue.
    assertRejected(channel, HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE);
    channel.finishAndReleaseAll();
  }

  @Test
  void dropBodyOfRejectedRequest() {
    // Hold the writes, so that the connection is still open when the rest of the body arrives, as on a slow network.
    List<ChannelPromise> pendingWrites = new ArrayList<>();
    EmbeddedChannel channel = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
      @Override
      public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        ReferenceCountUtil.release(msg);
        pendingWrites.add(promise);
      }
    }, new HttpRequestDecoder(4));
    DefaultHttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.PUT, "/upload");
    request.headers().set(HttpHeaderNames.CONTENT_LENGTH, 5);
    channel.writeInbound(request);
    assertEquals(1, pendingWrites.size());

    ByteBuf body = content("12345");
    channel.writeInbound(new DefaultLastHttpContent(body));
    assertEquals(0, body.refCnt());
    assertNull(channel.readInbound());

    // The connection is closed once the response is written.
    pendingWrites.forEach(ChannelPromise::setSuccess);
    assertFalse(channel.isOpen());
    channel.finishAndReleaseAll();
  }

  @Test
  void rejectTooLargeChunkedBody() {
    EmbeddedChannel channel = new EmbeddedChannel(new HttpRequestDecoder(4));
    DefaultHttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.PUT, "/upload");
    request.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
    channel.writeInbound(request);
    ByteBuf part1 = content("123");
    ByteBuf part2 = content("45");
    channel.writeInbound(new DefaultHttpContent(part1));
    channel.writeInbound(new DefaultHttpContent(part2));
    assertRejected(channel, HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE);
    assertEquals(0, part1.refCnt());
    assertEquals(0, part2.refCnt());
    channel.finishAndReleaseAll();
  }

  @Test
  void acceptBodyOfMaxSize() {
    EmbeddedChannel channel = new EmbeddedChannel(new HttpRequestDecoder(4));
    channel.writeInbound(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.PUT, "/upload"));
    channel.writeInbound(new DefaultHttpContent(content("12")));
    channel.writeInbound(new DefaultLastHttpContent(content("34")));
    HttpRequest request = channel.readInbound();
    assertEquals("1234", request.getBody().toString(StandardCharsets.UTF_8));
    request.getBody().release();
    assertTrue(channel.isOpen());
    channel.finishAndReleaseAll();
  }

  @Test
  void rejectMalformedRequest() {
    EmbeddedChannel channel = new EmbeddedChannel(new HttpRequestDecoder());
    DefaultHttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/bad");
    request.setDecoderResult(DecoderResult.failure(new IllegalArgumentException("invalid header")));
    channel.writeInbound(request);
    assertRejected(channel, HttpResponseStatus.BAD_REQUEST);
    channel.finishAndReleaseAll();
  }

  @Test
  void rejectMalformedContent() {
    EmbeddedChannel channel = new EmbeddedChannel(new HttpRequestDecoder());
    channel.writeInbound(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.PUT, "/upload"));
    ByteBuf part1 = content("123");
    channel.writeInbound(new DefaultHttpContent(part1));
    DefaultLastHttpContent invalid = new DefaultLastHttpContent(content("45"));
    invalid.setDecoderResult(DecoderResult.failure(new IllegalArgumentException("invalid chunk")));
    channel.writeInbound(invalid);
    assertRejected(channel, HttpResponseStatus.BAD_REQUEST);
    assertEquals(0, part1.refCnt());
    assertEquals(0, invalid.refCnt());
    channel.finishAndReleaseAll();
  }

  @Test
  void decodeHeaders() {
    EmbeddedChannel channel = new EmbeddedChannel(new HttpRequestDecoder());
    DefaultFullHttpRequest fullRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
    fullRequest.headers()
        .set(HttpHeaderNames.HOST, "localhost")
        .add("X-Amz-Meta-Tag", "a")
        .add("x-amz-meta-tag", "b c");
    channel.writeInbound(fullRequest);
    HttpRequest request = channel.readInbound();

    assertEquals("localhost", request.getHeaders().get("host"));
    assertEquals("localhost", request.getHeaders().get(HttpHeaderNames.HOST.toString()));
    assertEquals("localhost", request.header(HttpHeaderNames.HOST).orElseThrow());
    assertEquals("localhost", request.header("Host").orElseThrow());
    // The values of a repeated header are joined by commas, in the order they were received.
    assertEquals("a,b c", request.getHeaders().get("x-amz-meta-tag"));
    request.getBody().release();
    channel.finishAndReleaseAll();
  }

  @Test
  void decodeFullHttpRequest() {
    EmbeddedChannel channel = new EmbeddedChannel(new HttpRequestDecoder());
    ByteBuf body = content("hello");
    channel.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.PUT, "/upload?a=1", body));
    HttpRequest request = channel.readInbound();
    assertEquals("/upload", request.getPath());
    assertEquals("1", request.parameter("a").orElseThrow());
    assertEquals("hello", request.getBody().toString(StandardCharsets.UTF_8));
    request.getBody().release();
    assertEquals(0, body.refCnt());
    channel.finishAndReleaseAll();
  }

}
