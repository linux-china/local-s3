package com.robothy.s3.rest.netty;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpChunkedInput;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.stream.ChunkedStream;
import java.io.ByteArrayInputStream;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class IdleConnectionHandlerTest {

  private static final long TIMEOUT_SECONDS = 10;

  private static void idle(EmbeddedChannel channel) {
    channel.advanceTimeBy(TIMEOUT_SECONDS + 1, TimeUnit.SECONDS);
    channel.runScheduledPendingTasks();
  }

  private static void receiveRequest(EmbeddedChannel channel) {
    channel.writeInbound(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/bucket/key"));
  }

  @Test
  void closesIdleConnection() {
    EmbeddedChannel channel = new EmbeddedChannel(new IdleConnectionHandler(TIMEOUT_SECONDS));

    idle(channel);

    assertFalse(channel.isOpen());
  }

  @Test
  void keepsConnectionWithRequestInFlightOpen() {
    EmbeddedChannel channel = new EmbeddedChannel(new IdleConnectionHandler(TIMEOUT_SECONDS));
    receiveRequest(channel);

    idle(channel);
    assertTrue(channel.isOpen());

    channel.writeOutbound(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK));
    idle(channel);
    assertFalse(channel.isOpen());
    channel.finishAndReleaseAll();
  }

  @Test
  void interimResponseDoesNotEndRequest() {
    EmbeddedChannel channel = new EmbeddedChannel(new IdleConnectionHandler(TIMEOUT_SECONDS));
    receiveRequest(channel);

    channel.writeOutbound(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE));
    idle(channel);

    assertTrue(channel.isOpen());
    channel.finishAndReleaseAll();
  }

  @Test
  void streamedResponseEndsRequest() {
    EmbeddedChannel channel = new EmbeddedChannel(new IdleConnectionHandler(TIMEOUT_SECONDS));
    receiveRequest(channel);

    channel.writeOutbound(new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK));
    idle(channel);
    assertTrue(channel.isOpen());

    channel.writeOutbound(new HttpChunkedInput(new ChunkedStream(new ByteArrayInputStream(new byte[8]))));
    idle(channel);
    assertFalse(channel.isOpen());
    channel.finishAndReleaseAll();
  }

}
