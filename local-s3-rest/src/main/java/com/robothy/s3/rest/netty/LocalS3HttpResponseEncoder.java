package com.robothy.s3.rest.netty;

import com.robothy.netty.http.RouterHttpResponse;
import com.robothy.s3.core.storage.CompositeInputStream;
import com.robothy.s3.core.storage.FileRegionInputStream;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.DefaultFileRegion;
import io.netty.handler.codec.MessageToMessageEncoder;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpChunkedInput;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.stream.ChunkedNioFile;
import io.netty.handler.stream.ChunkedStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Encodes {@linkplain RouterHttpResponse}s into Netty HTTP messages: a buffered response as a
 * {@linkplain FullHttpResponse}, a {@linkplain StreamingRouterHttpResponse} as headers followed by streamed content.
 *
 * <p>The content is written on the event loop, so it must never block it: on a plaintext connection a file is sent as a
 * zero-copy {@linkplain DefaultFileRegion} and an object of parts part by part ({@linkplain CompositeContentChunkedInput},
 * one part open at a time); in-memory streams are copied a chunk at a time while the connection is writable. A TLS
 * connection has to read a file to encrypt it, so it uses a {@linkplain ChunkedNioFile} read by a
 * {@linkplain io.netty.handler.stream.ChunkedWriteHandler} off the event loop, see {@linkplain LocalS3ServerInitializer}.
 * Other streams use a {@linkplain ChunkedStream}.
 */
public class LocalS3HttpResponseEncoder extends MessageToMessageEncoder<RouterHttpResponse> {

  static final int STREAM_CHUNK_SIZE = 64 * 1024;

  @Override
  protected void encode(ChannelHandlerContext ctx, RouterHttpResponse msg, List<Object> out) throws IOException {
    InputStream bodyStream = msg instanceof StreamingRouterHttpResponse streaming ? streaming.getBodyStream() : null;
    if (bodyStream == null) {
      FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, msg.getStatus(), msg.getBody());
      msg.getAllHeaders().forEach(response.headers()::set);
      out.add(response);
      return;
    }

    msg.getBody().release();
    DefaultHttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, msg.getStatus());
    msg.getAllHeaders().forEach(response.headers()::set);
    if (!response.headers().contains(HttpHeaderNames.CONTENT_LENGTH)) {
      HttpUtil.setTransferEncodingChunked(response, true);
    }
    out.add(response);
    if (bodyStream instanceof CompositeInputStream composite && ctx.pipeline().get(SslHandler.class) == null) {
      // Sends one part at a time, and ends the message with a last chunk of its own.
      out.add(CompositeContentChunkedInput.of(composite, ctx, STREAM_CHUNK_SIZE));
    } else if (bodyStream instanceof FileRegionInputStream file) {
      if (ctx.pipeline().get(SslHandler.class) == null) {
        out.add(new DefaultFileRegion(file.getChannel(), file.getPosition(), file.getCount()));
        out.add(LastHttpContent.EMPTY_LAST_CONTENT);
      } else {
        out.add(new HttpChunkedInput(new ChunkedNioFile(
            file.getChannel(), file.getPosition(), file.getCount(), STREAM_CHUNK_SIZE)));
      }
    } else {
      out.add(new HttpChunkedInput(new ChunkedStream(bodyStream, STREAM_CHUNK_SIZE)));
    }
  }

}
