package com.robothy.netty.utils;

import java.util.Locale;

/**
 * Normalize the request target of an HTTP request (RFC 9112, section 3.2).
 */
public final class RequestTargets {

  private RequestTargets() {
  }

  /**
   * Convert a request target to the origin-form. An absolute-form target, e.g. {@code http://host/a?b=c}, which a
   * client sends to a proxy, is converted to its path and query, e.g. {@code /a?b=c}.
   *
   * @param target the request target of the request line.
   * @return the origin-form of the target; {@code null} if the target cannot be converted, e.g. the asterisk-form
   *     {@code *} or the authority-form {@code host:port}.
   */
  public static String toOriginForm(String target) {
    if (target == null || target.isEmpty()) {
      return null;
    }
    if (target.charAt(0) == '/') {
      return target;
    }

    String lower = target.toLowerCase(Locale.ROOT);
    int authorityStart;
    if (lower.startsWith("http://")) {
      authorityStart = "http://".length();
    } else if (lower.startsWith("https://")) {
      authorityStart = "https://".length();
    } else {
      return null;
    }

    for (int i = authorityStart; i < target.length(); i++) {
      char c = target.charAt(i);
      if (c == '/') {
        return target.substring(i);
      }
      if (c == '?' || c == '#') {
        return "/" + target.substring(i);
      }
    }
    return "/";
  }

}
