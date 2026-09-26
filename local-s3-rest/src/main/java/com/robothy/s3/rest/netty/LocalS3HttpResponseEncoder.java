package com.robothy.s3.rest.netty;

import com.robothy.netty.http.HttpResponse;
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
 * Encodes {@linkplain HttpResponse}s into Netty HTTP messages.
 *
 * <p>A buffered response becomes a {@linkplain FullHttpResponse}. A {@linkplain StreamingHttpResponse}
 * with a body stream becomes the response headers followed by streaming content. File-backed content
 * is sent as a zero-copy {@linkplain DefaultFileRegion} on plaintext connections, or a
 * {@linkplain ChunkedNioFile} through TLS; other streams use a {@linkplain ChunkedStream}. The content of an object
 * stored in parts, i.e. a {@linkplain CompositeInputStream}, is sent part by part on plaintext connections by a
 * {@linkplain CompositeContentChunkedInput}, which opens one part at a time.
 *
 * <p>On a plaintext connection, the content is written on the event loop of the connection. So that a large response
 * doesn't block the other connections of the loop, file-backed content is never read through the Java heap there: the
 * kernel transfers it. The other streams that storages answer hold their content in memory, which a chunk of is copied
 * at a time, as far as the connection is writable.
 *
 * <p>On a TLS connection, file-backed content has to be read into memory to be encrypted. It is read a chunk at a time
 * by the {@linkplain io.netty.handler.stream.ChunkedWriteHandler} of the connection, which runs on an executor of its
 * own rather than on the event loop; see {@linkplain LocalS3ServerInitializer}. Only the encryption of the chunks runs
 * on the event loop.
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
