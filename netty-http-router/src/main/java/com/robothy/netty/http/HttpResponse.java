package com.robothy.netty.http;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import lombok.Getter;

@Getter
public class HttpResponse {

  // Header names are case-insensitive (RFC 9110); a case-sensitive map would let "Content-Length" and
  // "content-length" both be emitted.
  private final Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

  private HttpResponseStatus status;

  // No component limit: consolidating the components of a large body would copy it over and over.
  private final CompositeByteBuf body = Unpooled.compositeBuffer(Integer.MAX_VALUE);

  public HttpResponse write(String content) {
    if (content != null) {
      write(content.getBytes(StandardCharsets.UTF_8));
    }
    return this;
  }

  public HttpResponse write(byte[] bytes) {
    body.addComponent(true, Unpooled.copiedBuffer(bytes));
    return this;
  }

  public HttpResponse write(ByteBuf buf) {
    body.addComponent(true, buf);
    return this;
  }

  public HttpResponse status(HttpResponseStatus status) {
    this.status = status;
    return this;
  }

  public HttpResponse putHeader(String key, Object value) {
    headers.put(key, String.valueOf(value));
    return this;
  }

}
