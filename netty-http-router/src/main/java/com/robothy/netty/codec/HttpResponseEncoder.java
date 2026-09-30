package com.robothy.netty.codec;

import com.robothy.netty.http.HttpResponse;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.EmptyHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpVersion;
import java.util.List;

/**
 * Encodes a {@linkplain HttpResponse} to a netty {@linkplain io.netty.handler.codec.http.FullHttpResponse}, which takes
 * over the response body.
 */
public class HttpResponseEncoder extends MessageToMessageEncoder<HttpResponse> {
  @Override
  protected void encode(ChannelHandlerContext ctx, HttpResponse msg, List<Object> out) throws Exception {
    HttpHeaders headers = new DefaultHttpHeaders();
    try {
      msg.getHeaders().forEach(headers::add);
      out.add(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, msg.getStatus(), msg.getBody(), headers,
          EmptyHttpHeaders.INSTANCE));
    } catch (RuntimeException e) {
      // The body is not handed over to netty, e.g. an invalid header value or a null status.
      msg.getBody().release();
      throw e;
    }
  }
}
