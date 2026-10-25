package com.robothy.s3.core.util;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Decoding of percent-encoded URL paths.
 */
public final class UrlPaths {

  private UrlPaths() {
  }

  /**
   * Decode the percent-escapes of a URL path, e.g. {@code %20} to a space. A plus sign stays a plus sign:
   * {@linkplain URLDecoder} decodes {@code application/x-www-form-urlencoded} text, where it means a space, but a
   * {@code +} of a path is a literal character.
   *
   * @param path the encoded path; may be {@code null}.
   * @return the decoded path, or {@code null} if {@code path} is {@code null}.
   * @throws IllegalArgumentException if {@code path} holds a malformed percent-escape.
   */
  public static String decode(String path) {
    if (path == null) {
      return null;
    }
    return URLDecoder.decode(path.replace("+", "%2B"), StandardCharsets.UTF_8);
  }

}
