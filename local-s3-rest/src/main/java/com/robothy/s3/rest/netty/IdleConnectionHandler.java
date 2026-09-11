package com.robothy.s3.rest.netty;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpChunkedInput;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpStatusClass;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Closes connections that have had no reads or writes for the idle timeout, so that idle keep-alive
 * connections don't pile up.
 *
 * <p>A connection with a request in flight is never closed: handling a request, or writing a streamed
 * response, may take longer than the timeout without any I/O on the connection. The handler must sit on
 * the channel's event loop between the HTTP codec and {@linkplain io.netty.handler.stream.ChunkedWriteHandler}'s
 * callers, where it sees Netty {@linkplain HttpRequest}s and the response messages before they are chunked.
 */
public class IdleConnectionHandler extends IdleStateHandler {

  private static final Logger log = LoggerFactory.getLogger(IdleConnectionHandler.class);

  /**
   * Requests received whose response hasn't been written completely. Only accessed on the event loop.
   */
  private int inFlightRequests;

  /**
   * Create a handler.
   *
   * @param idleTimeoutSeconds seconds without reads or writes after which an idle connection is closed.
   */
  public IdleConnectionHandler(long idleTimeoutSeconds) {
    super(0, 0, idleTimeoutSeconds, TimeUnit.SECONDS);
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
    if (msg instanceof HttpRequest) {
      inFlightRequests++;
    }
    super.channelRead(ctx, msg);
  }

  @Override
  public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
    if (isEndOfResponse(msg)) {
      promise = promise.unvoid();
      promise.addListener(future -> inFlightRequests = Math.max(0, inFlightRequests - 1));
    }
    super.write(ctx, msg, promise);
  }

  /**
   * A response ends with a full response, the last content, or the chunked input of a streamed body.
   * An interim response, e.g. {@code 100 Continue}, doesn't end the request.
   */
  private static boolean isEndOfResponse(Object msg) {
    if (msg instanceof HttpResponse response && response.status().codeClass() == HttpStatusClass.INFORMATIONAL) {
      return false;
    }
    return msg instanceof LastHttpContent || msg instanceof HttpChunkedInput;
  }

  @Override
  protected void channelIdle(ChannelHandlerContext ctx, IdleStateEvent evt) {
    if (inFlightRequests == 0) {
      log.debug("Closing idle connection {}.", ctx.channel().id());
      ctx.close();
    }
  }

}
