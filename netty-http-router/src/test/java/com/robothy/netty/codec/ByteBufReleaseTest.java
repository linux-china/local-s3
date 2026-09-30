package com.robothy.netty.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import com.robothy.netty.http.HttpResponse;
import com.robothy.netty.router.Router;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

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
