package com.robothy.netty.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.robothy.netty.http.RouterHttpRequest;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
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

class RouterRouterHttpRequestDecoderTest {

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
    assertThrows(IllegalArgumentException.class, () -> new RouterHttpRequestDecoder(0));
  }

  @Test
  void rejectTooLargeContentLengthBeforeContinue() {
    EmbeddedChannel channel = new EmbeddedChannel(new RouterHttpRequestDecoder(4));
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
    }, new RouterHttpRequestDecoder(4));
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
    EmbeddedChannel channel = new EmbeddedChannel(new RouterHttpRequestDecoder(4));
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
    EmbeddedChannel channel = new EmbeddedChannel(new RouterHttpRequestDecoder(4));
    channel.writeInbound(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.PUT, "/upload"));
    channel.writeInbound(new DefaultHttpContent(content("12")));
    channel.writeInbound(new DefaultLastHttpContent(content("34")));
    RouterHttpRequest request = channel.readInbound();
    assertEquals("1234", request.getBody().toString(StandardCharsets.UTF_8));
    request.getBody().release();
    assertTrue(channel.isOpen());
    channel.finishAndReleaseAll();
  }

  @Test
  void rejectMalformedRequest() {
    EmbeddedChannel channel = new EmbeddedChannel(new RouterHttpRequestDecoder());
    DefaultHttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/bad");
    request.setDecoderResult(DecoderResult.failure(new IllegalArgumentException("invalid header")));
    channel.writeInbound(request);
    assertRejected(channel, HttpResponseStatus.BAD_REQUEST);
    channel.finishAndReleaseAll();
  }

  @Test
  void rejectMalformedUri() {
    EmbeddedChannel channel = new EmbeddedChannel(new RouterHttpRequestDecoder());
    channel.writeInbound(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/bucket/%zz"));
    assertRejected(channel, HttpResponseStatus.BAD_REQUEST);
    channel.finishAndReleaseAll();
  }

  @Test
  void convertAbsoluteFormTarget() {
    EmbeddedChannel channel = new EmbeddedChannel(new RouterHttpRequestDecoder());
    channel.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "http://host:8080/a/b?c=d"));
    RouterHttpRequest request = channel.readInbound();
    assertEquals("/a/b?c=d", request.getUri());
    assertEquals("/a/b", request.getPath());
    assertEquals(List.of("d"), request.getParams().get("c"));
    request.getBody().release();
    channel.finishAndReleaseAll();
  }

  @Test
  void absoluteFormAuthorityReplacesHostHeader() {
    EmbeddedChannel channel = new EmbeddedChannel(new RouterHttpRequestDecoder());
    DefaultFullHttpRequest absoluteForm =
        new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "http://bucket.localhost:8080/key");
    absoluteForm.headers().set(HttpHeaderNames.HOST, "other.localhost:8080");
    DefaultFullHttpRequest originForm = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/key");
    originForm.headers().set(HttpHeaderNames.HOST, "other.localhost:8080");
    channel.writeInbound(absoluteForm, originForm);

    RouterHttpRequest request = channel.readInbound();
    assertEquals("bucket.localhost:8080", request.header("Host").orElse(null));
    request.getBody().release();
    // The Host header of an origin-form target is kept.
    request = channel.readInbound();
    assertEquals("other.localhost:8080", request.header("Host").orElse(null));
    request.getBody().release();
    channel.finishAndReleaseAll();
  }

  @Test
  void semicolonIsNotParameterSeparator() {
    EmbeddedChannel channel = new EmbeddedChannel(new RouterHttpRequestDecoder());
    channel.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/bucket?prefix=a;b&c=d"));
    RouterHttpRequest request = channel.readInbound();
    assertEquals(List.of("a;b"), request.getParams().get("prefix"));
    assertEquals(List.of("d"), request.getParams().get("c"));
    assertFalse(request.getParams().containsKey("b"));
    request.getBody().release();
    channel.finishAndReleaseAll();
  }

  @Test
  void rejectAsteriskFormTarget() {
    EmbeddedChannel channel = new EmbeddedChannel(new RouterHttpRequestDecoder());
    channel.writeInbound(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.OPTIONS, "*"));
    assertRejected(channel, HttpResponseStatus.BAD_REQUEST);
    channel.finishAndReleaseAll();
  }

  @Test
  void dropBodyOfMalformedUriRequest() {
    // Hold the writes, so that the connection is still open when the body arrives.
    List<ChannelPromise> pendingWrites = new ArrayList<>();
    List<Object> written = new ArrayList<>();
    EmbeddedChannel channel = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
      @Override
      public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        written.add(msg);
        pendingWrites.add(promise);
      }
    }, new RouterHttpRequestDecoder());
    channel.writeInbound(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.PUT, "/bucket/key?a=%zz"));
    assertEquals(1, written.size());
    assertEquals(HttpResponseStatus.BAD_REQUEST, ((FullHttpResponse) written.get(0)).status());
    written.forEach(ReferenceCountUtil::release);

    // No NPE: the content of the rejected request is dropped, not built into a request.
    ByteBuf body = content("123");
    channel.writeInbound(new DefaultLastHttpContent(body));
    assertEquals(0, body.refCnt());
    assertNull(channel.readInbound());

    pendingWrites.forEach(ChannelPromise::setSuccess);
    assertFalse(channel.isOpen());
    channel.finishAndReleaseAll();
  }

  @Test
  void rejectMalformedContent() {
    EmbeddedChannel channel = new EmbeddedChannel(new RouterHttpRequestDecoder());
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
    EmbeddedChannel channel = new EmbeddedChannel(new RouterHttpRequestDecoder());
    DefaultFullHttpRequest fullRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
    fullRequest.headers()
        .set(HttpHeaderNames.HOST, "localhost")
        .add("X-Amz-Meta-Tag", "a")
        .add("x-amz-meta-tag", "b c")
        .add("Cookie", "a=1")
        .add("cookie", "b=2");
    channel.writeInbound(fullRequest);
    RouterHttpRequest request = channel.readInbound();

    assertEquals("localhost", request.getHeaders().get("host"));
    assertEquals("localhost", request.getHeaders().get(HttpHeaderNames.HOST.toString()));
    assertEquals("localhost", request.header(HttpHeaderNames.HOST).orElseThrow());
    assertEquals("localhost", request.header("Host").orElseThrow());
    // The values of a repeated header are joined by commas, in the order they were received.
    assertEquals("a,b c", request.getHeaders().get("x-amz-meta-tag"));
    // Cookie values are joined by "; " (RFC 6265, section 5.4).
    assertEquals("a=1; b=2", request.getHeaders().get("cookie"));
    request.getBody().release();
    channel.finishAndReleaseAll();
  }

  @Test
  void decodeFullHttpRequest() {
    EmbeddedChannel channel = new EmbeddedChannel(new RouterHttpRequestDecoder());
    ByteBuf body = content("hello");
    channel.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.PUT, "/upload?a=1", body));
    RouterHttpRequest request = channel.readInbound();
    assertEquals("/upload", request.getPath());
    assertEquals("1", request.parameter("a").orElseThrow());
    assertEquals("hello", request.getBody().toString(StandardCharsets.UTF_8));
    request.getBody().release();
    assertEquals(0, body.refCnt());
    channel.finishAndReleaseAll();
  }

  @Test
  void manyChunksAreNotConsolidated() {
    EmbeddedChannel channel = new EmbeddedChannel(new RouterHttpRequestDecoder());
    channel.writeInbound(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.PUT, "/upload"));
    int chunks = 100;
    StringBuilder expected = new StringBuilder();
    for (int i = 0; i < chunks; i++) {
      channel.writeInbound(new DefaultHttpContent(content("c" + i + ";")));
      expected.append("c").append(i).append(';');
    }
    channel.writeInbound(new DefaultLastHttpContent());
    RouterHttpRequest request = channel.readInbound();
    // Every chunk, and the empty last content, stays a component: consolidating them would copy the body over and over.
    assertEquals(chunks + 1, ((CompositeByteBuf) request.getBody()).numComponents());
    assertEquals(expected.toString(), request.getBody().toString(StandardCharsets.UTF_8));
    request.getBody().release();
    channel.finishAndReleaseAll();
  }

}
