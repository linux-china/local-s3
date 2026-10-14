package com.robothy.s3.rest.handler;

import com.robothy.netty.http.RouterHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The credentials of a service as the endpoints that a client can't sign for ask for them: the console, which a
 * browser opens, and the MCP server, which an AI agent connects to with a header it was configured with.
 */
final class ServiceCredentials {

  private static final String BASIC = "Basic ";

  private static final String BEARER = "Bearer ";

  private ServiceCredentials() {
  }

  /**
   * Whether a request carries the key pair as HTTP Basic authentication: the access key ID as the user name, and the
   * secret access key as the password.
   */
  static boolean hasBasic(RouterHttpRequest request, String accessKeyId, @Nullable String secretAccessKey) {
    String authorization = authorization(request);
    if (!authorization.regionMatches(true, 0, BASIC, 0, BASIC.length())) {
      return false;
    }

    byte[] decoded;
    try {
      decoded = Base64.getDecoder().decode(authorization.substring(BASIC.length()).trim());
    } catch (IllegalArgumentException _) {
      return false;
    }
    String credentials = new String(decoded, StandardCharsets.UTF_8);
    int colon = credentials.indexOf(':');
    if (colon < 0) {
      return false;
    }
    // Compared without returning early on the first byte that differs, like the signatures of the S3 API are.
    return equals(credentials.substring(0, colon), accessKeyId)
        & equals(credentials.substring(colon + 1), secretAccessKey);
  }

  /**
   * Whether a request carries the secret access key as a bearer token, the one header that MCP clients let a user
   * configure without an OAuth flow.
   */
  static boolean hasBearer(RouterHttpRequest request, @Nullable String secretAccessKey) {
    String authorization = authorization(request);
    return authorization.regionMatches(true, 0, BEARER, 0, BEARER.length())
        && equals(authorization.substring(BEARER.length()).trim(), secretAccessKey);
  }

  private static String authorization(RouterHttpRequest request) {
    return request.header(HttpHeaderNames.AUTHORIZATION.toString()).orElse("");
  }

  private static boolean equals(String given, @Nullable String expected) {
    return MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8),
        Objects.toString(expected, "").getBytes(StandardCharsets.UTF_8));
  }

}
