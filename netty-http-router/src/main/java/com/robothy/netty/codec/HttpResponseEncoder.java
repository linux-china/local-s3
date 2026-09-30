package com.robothy.netty.codec;

import com.robothy.netty.http.HttpResponse;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.EmptyHttpHeaders;
import io.netty.handler.codec.http.HttpChunkedInput;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.stream.ChunkedInput;
import java.util.List;

/**
 * Encodes a {@linkplain HttpResponse} to a netty {@linkplain io.netty.handler.codec.http.FullHttpResponse}, which takes
 * over the response body.
 *
 * <p>A response with a {@linkplain HttpResponse#chunkedBody(ChunkedInput) chunked body} is encoded to its head and a
 * {@linkplain HttpChunkedInput} instead, which a {@linkplain io.netty.handler.stream.ChunkedWriteHandler} further on
 * in the pipeline writes chunk by chunk and closes.
 */
public class HttpResponseEncoder extends MessageToMessageEncoder<HttpResponse> {
  @Override
  protected void encode(ChannelHandlerContext ctx, HttpResponse msg, List<Object> out) throws Exception {
    ChunkedInput<ByteBuf> chunkedBody = msg.detachChunkedBody();
    HttpHeaders headers = new DefaultHttpHeaders();
    try {
      msg.getHeaders().forEach(headers::add);
      if (chunkedBody == null) {
        out.add(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, msg.getStatus(), msg.getBody(), headers,
            EmptyHttpHeaders.INSTANCE));
        return;
      }

      DefaultHttpResponse head = new DefaultHttpResponse(HttpVersion.HTTP_1_1, msg.getStatus(), headers);
      if (!headers.contains(HttpHeaderNames.CONTENT_LENGTH)) {
        HttpUtil.setTransferEncodingChunked(head, true);
      }
      // Only the chunked body is sent.
      msg.getBody().release();
      out.add(head);
      out.add(new HttpChunkedInput(chunkedBody));
    } catch (RuntimeException e) {
      // The body is not handed over to netty, e.g. an invalid header value or a null status.
      if (msg.getBody().refCnt() > 0) {
        msg.getBody().release();
      }
      HttpResponse.closeQuietly(chunkedBody);
      throw e;
    }
  }
}
