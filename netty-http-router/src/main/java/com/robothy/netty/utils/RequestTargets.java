package com.robothy.netty.utils;

import java.util.Locale;

/**
 * Normalize the request target of an HTTP request (RFC 9112, section 3.2).
 */
public final class RequestTargets {

  private RequestTargets() {
  }

  /**
   * A request target converted to the origin-form.
   *
   * @param originForm the path and query, e.g. {@code /a?b=c}.
   * @param authority  the host and optional port of an absolute-form target without its user info, e.g.
   *                   {@code host:8080}, which replaces the {@code Host} header (RFC 9112, section 3.2.2);
   *                   {@code null} for an origin-form target.
   */
  public record RequestTarget(String originForm, String authority) {
  }

  /**
   * Convert a request target to the origin-form. An absolute-form target, e.g. {@code http://host/a?b=c}, which a
   * client sends to a proxy, is converted to its path and query, e.g. {@code /a?b=c}.
   *
   * @param target the request target of the request line.
   * @return the origin-form of the target; {@code null} if the target cannot be converted, e.g. the asterisk-form
   *     {@code *}, the authority-form {@code host:port}, or an absolute-form target without a host.
   * @see #parse(String)
   */
  public static String toOriginForm(String target) {
    RequestTarget requestTarget = parse(target);
    return requestTarget == null ? null : requestTarget.originForm();
  }

  /**
   * Convert a request target to the origin-form, keeping the authority of an absolute-form target. A server must use
   * that authority instead of the {@code Host} header of the request (RFC 9112, section 3.2.2).
   *
   * @param target the request target of the request line.
   * @return the converted target without a fragment, e.g. {@code /a} of {@code /a#b}, which a client doesn't send
   *     (RFC 9110, section 7.1); {@code null} if the target cannot be converted, e.g. the asterisk-form {@code *},
   *     the authority-form {@code host:port}, or an absolute-form target without a host, e.g. {@code http:///a},
   *     which is invalid (RFC 9110, section 4.2.1).
   */
  public static RequestTarget parse(String target) {
    if (target == null) {
      return null;
    }
    // A '#' in a path or a query is percent-encoded, so the first one starts the fragment.
    int fragmentStart = target.indexOf('#');
    if (fragmentStart >= 0) {
      target = target.substring(0, fragmentStart);
    }
    if (target.isEmpty()) {
      return null;
    }
    if (target.charAt(0) == '/') {
      return new RequestTarget(target, null);
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

    int authorityEnd = authorityStart;
    while (authorityEnd < target.length() && "/?".indexOf(target.charAt(authorityEnd)) < 0) {
      authorityEnd++;
    }
    // The user info, e.g. "user:password@", is not part of the Host header.
    String authority = target.substring(authorityStart, authorityEnd);
    authority = authority.substring(authority.lastIndexOf('@') + 1);
    if (authority.isEmpty()) {
      return null;
    }

    String originForm;
    if (authorityEnd == target.length()) {
      originForm = "/";
    } else if (target.charAt(authorityEnd) == '/') {
      originForm = target.substring(authorityEnd);
    } else {
      originForm = "/" + target.substring(authorityEnd);
    }
    return new RequestTarget(originForm, authority);
  }

}
