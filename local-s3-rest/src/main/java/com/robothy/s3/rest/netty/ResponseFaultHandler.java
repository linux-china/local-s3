package com.robothy.s3.rest.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.traffic.ChannelTrafficShapingHandler;
import io.netty.util.ReferenceCountUtil;
import java.io.IOException;

/**
 * Cuts the response of a request that a fault breaks off: it lets the headers and the first bytes of the body through,
 * then closes the connection. Installed for one response, between the HTTP encoder and the chunked writer, where the
 * body arrives as {@linkplain HttpContent}s; the connection writes one response at a time, so it sees no other.
 */
final class ResponseFaultHandler extends ChannelOutboundHandlerAdapter {

  private static final String NAME = "local-s3-fault";

  private static final String THROTTLE_NAME = "local-s3-fault-throttle";

  // The HTTP encoder of the server; a pipeline without it, e.g. of a test, has no place to cut the encoded body.
  private static final String HTTP_ENCODER = "http-response-encoder";

  private long remaining;

  private boolean cut;

  private ResponseFaultHandler(long remaining) {
    this.remaining = remaining;
  }

  /**
   * Install the handlers that shape the next response of a connection.
   *
   * @return whether they are installed; they are to be {@linkplain #uninstall removed} once the response is written.
   */
  static boolean install(ChannelPipeline pipeline, FaultInjector.Injection injection) {
    if (pipeline.get(HTTP_ENCODER) == null) {
      return false;
    }
    if (injection.resetAfterBytes() >= 0) {
      pipeline.addAfter(HTTP_ENCODER, NAME, new ResponseFaultHandler(injection.resetAfterBytes()));
    }
    if (injection.bytesPerSecond() > 0) {
      // Network side of the cut, so it only paces what is sent. The traffic shaping handler marks the channel
      // unwritable while it holds back, which pauses the chunked writer, so a large body isn't read ahead into memory.
      pipeline.addAfter(HTTP_ENCODER, THROTTLE_NAME,
          new ChannelTrafficShapingHandler(injection.bytesPerSecond(), 0, 100));
    }
    return true;
  }

  static void uninstall(ChannelPipeline pipeline) {
    for (String name : new String[] {NAME, THROTTLE_NAME}) {
      if (pipeline.get(name) != null) {
        pipeline.remove(name);
      }
    }
  }

  @Override
  public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
    if (cut) {
      ReferenceCountUtil.release(msg);
      promise.tryFailure(new IOException("The connection was closed by an injected fault."));
      return;
    }
    switch (msg) {
      case FullHttpResponse full when full.content().readableBytes() > remaining -> {
        HttpResponse head = new DefaultHttpResponse(full.protocolVersion(), full.status());
        head.headers().set(full.headers());
        ByteBuf part = full.content().retainedSlice(full.content().readerIndex(), (int) remaining);
        full.release();
        ctx.write(head);
        cutAfter(ctx, part, promise);
      }
      case FullHttpResponse full -> {
        remaining -= full.content().readableBytes();
        ctx.write(msg, promise);
      }
      case HttpContent content when content.content().readableBytes() > remaining -> {
        ByteBuf part = content.content().retainedSlice(content.content().readerIndex(), (int) remaining);
        content.release();
        cutAfter(ctx, part, promise);
      }
      case HttpContent content -> {
        remaining -= content.content().readableBytes();
        ctx.write(msg, promise);
      }
      default -> ctx.write(msg, promise);
    }
  }

  /**
   * Send the last bytes, then close the connection. Closed rather than reset: a reset would discard what the kernel
   * hasn't sent yet, so the client wouldn't reliably get the bytes before the cut.
   */
  private void cutAfter(ChannelHandlerContext ctx, ByteBuf part, ChannelPromise promise) {
    cut = true;
    // The response fails only once the bytes are written: its failure closes the connection, which would drop them.
    ctx.writeAndFlush(new DefaultHttpContent(part)).addListener(written -> {
      promise.tryFailure(new IOException("The connection was closed by an injected fault."));
      ctx.close();
    });
  }

}
