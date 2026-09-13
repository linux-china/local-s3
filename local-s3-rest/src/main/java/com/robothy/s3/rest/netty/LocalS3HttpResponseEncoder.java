package com.robothy.s3.rest.netty;

import com.robothy.netty.http.HttpResponse;
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
 * Encodes {@linkplain HttpResponse}s into Netty HTTP messages.
 *
 * <p>A buffered response becomes a {@linkplain FullHttpResponse}. A {@linkplain StreamingHttpResponse}
 * with a body stream becomes the response headers followed by streaming content. File-backed content
 * is sent as a zero-copy {@linkplain DefaultFileRegion} on plaintext connections, or a
 * {@linkplain ChunkedNioFile} through TLS; other streams use a {@linkplain ChunkedStream}.
 */
public class LocalS3HttpResponseEncoder extends MessageToMessageEncoder<HttpResponse> {

  static final int STREAM_CHUNK_SIZE = 64 * 1024;

  @Override
  protected void encode(ChannelHandlerContext ctx, HttpResponse msg, List<Object> out) throws IOException {
    InputStream bodyStream = msg instanceof StreamingHttpResponse streaming ? streaming.getBodyStream() : null;
    if (bodyStream == null) {
      FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, msg.getStatus(), msg.getBody());
      msg.getHeaders().forEach(response.headers()::set);
      out.add(response);
      return;
    }

    msg.getBody().release();
    DefaultHttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, msg.getStatus());
    msg.getHeaders().forEach(response.headers()::set);
    if (!response.headers().contains(HttpHeaderNames.CONTENT_LENGTH)) {
      HttpUtil.setTransferEncodingChunked(response, true);
    }
    out.add(response);
    if (bodyStream instanceof FileRegionInputStream file) {
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
