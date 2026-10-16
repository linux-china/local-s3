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

  private static final long TIMEOUT_MILLIS = 50;

  // Netty 4.2's IdleStateHandler reads the event loop's ticker, which advanceTimeBy() moves, while 4.1's reads
  // System.nanoTime(); passing real time as well makes the connection idle on both.
  private static void idle(EmbeddedChannel channel) {
    try {
      Thread.sleep(TIMEOUT_MILLIS * 2);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
    channel.advanceTimeBy(TIMEOUT_MILLIS * 2, TimeUnit.MILLISECONDS);
    channel.runScheduledPendingTasks();
  }

  private static EmbeddedChannel newChannel() {
    return new EmbeddedChannel(new IdleConnectionHandler(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
  }

  private static void receiveRequest(EmbeddedChannel channel) {
    channel.writeInbound(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/bucket/key"));
  }

  @Test
  void closesIdleConnection() {
    EmbeddedChannel channel = newChannel();

    idle(channel);

    assertFalse(channel.isOpen());
  }

  @Test
  void keepsConnectionWithRequestInFlightOpen() {
    EmbeddedChannel channel = newChannel();
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
    EmbeddedChannel channel = newChannel();
    receiveRequest(channel);

    channel.writeOutbound(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE));
    idle(channel);

    assertTrue(channel.isOpen());
    channel.finishAndReleaseAll();
  }

  @Test
  void streamedResponseEndsRequest() {
    EmbeddedChannel channel = newChannel();
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
