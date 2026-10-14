package com.robothy.s3.rest.handler;

import com.robothy.netty.http.RouterHttpRequest;
import com.robothy.s3.rest.LocalS3Config;
import com.robothy.s3.rest.netty.ConnectionSchemes;
import io.netty.handler.codec.http.HttpHeaderNames;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The endpoint that a client reached the service at, which a presigned URL is built on: a URL signs its host, and the
 * client that asked for it, or the one it is handed to, is the one that reaches the service there.
 */
final class ServiceEndpoints {

  /**
   * A {@code Host} header that is a name or an address and a port, which a presigned URL is built on.
   */
  private static final Pattern HOST = Pattern.compile("[A-Za-z0-9._\\-]+(:\\d{1,5})?|\\[[0-9A-Fa-f:.]+](:\\d{1,5})?");

  private ServiceEndpoints() {
  }

  /**
   * @return the scheme and the host that the request addressed, e.g. {@code http://localhost:29090}.
   * @throws IllegalArgumentException if the request names no host that a URL can be built on.
   */
  static String of(RouterHttpRequest request, @Nullable LocalS3Config config) {
    String host = request.header(HttpHeaderNames.HOST.toString())
        .map(String::trim)
        .filter(value -> HOST.matcher(value).matches())
        .orElseThrow(() -> new IllegalArgumentException("The request names no host to sign a URL for."));
    String scheme = ConnectionSchemes.of(request)
        .orElse(config != null && config.tlsEnabled() ? ConnectionSchemes.HTTPS : ConnectionSchemes.HTTP);
    return scheme + "://" + host;
  }

}
