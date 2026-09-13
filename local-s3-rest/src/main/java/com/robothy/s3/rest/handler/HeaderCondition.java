package com.robothy.s3.rest.handler;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * The condition of a route on a header of a request, declared rather than coded, so that
 * {@linkplain LocalS3Router#verifyRoutes()} can tell which requests the route matches: that a header is present, or
 * that it is absent. Header names are compared ignoring case.
 */
final class HeaderCondition implements Function<Map<CharSequence, String>, Boolean> {

  private final String name;

  private final boolean present;

  private HeaderCondition(String name, boolean present) {
    this.name = Objects.requireNonNull(name).toLowerCase(Locale.ROOT);
    this.present = present;
  }

  /**
   * A condition that the request has a header, e.g. {@code x-amz-copy-source}.
   *
   * @param name the name of the header.
   * @return the condition.
   */
  static HeaderCondition has(String name) {
    return new HeaderCondition(name, true);
  }

  /**
   * A condition that the request doesn't have a header.
   *
   * @param name the name of the header.
   * @return the condition.
   */
  static HeaderCondition hasNot(String name) {
    return new HeaderCondition(name, false);
  }

  @Override
  public Boolean apply(Map<CharSequence, String> headers) {
    boolean found = headers.containsKey(name)
        || headers.keySet().stream().anyMatch(header -> name.equalsIgnoreCase(header.toString()));
    return found == present;
  }

  /**
   * The headers of the smallest request that satisfies this condition.
   *
   * @return the headers.
   */
  Map<CharSequence, String> minimalHeaders() {
    Map<CharSequence, String> headers = new LinkedHashMap<>();
    if (present) {
      headers.put(name, "");
    }
    return headers;
  }

  @Override
  public String toString() {
    return (present ? "" : "!") + name;
  }

}
