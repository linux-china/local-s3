package com.robothy.netty.router;

import com.robothy.netty.http.HttpRequestHandler;
import io.netty.handler.codec.http.HttpMethod;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import lombok.AccessLevel;
import lombok.Getter;

/**
 * An immutable route. Instances are created by {@link Builder} and must not change afterwards, as they are kept in
 * hash-based and sorted collections of the router.
 */
@Getter
public final class Route {

  private final HttpMethod method;

  private final String path;

  private final HttpRequestHandler handler;

  private final Predicate<Map<String, String>> headerMatcher;

  private final Predicate<Map<String, List<String>>> paramMatcher;

  private final String trimPath;

  /**
   * The non-empty segments of {@link #path}, split once when the route is created.
   */
  @Getter(AccessLevel.NONE)
  private final String[] segments;

  /**
   * The path variable name of each segment; {@code null} for the literal segments.
   */
  @Getter(AccessLevel.NONE)
  private final String[] variableNames;

  private Route(HttpMethod method, String path, HttpRequestHandler handler,
                Predicate<Map<String, String>> headerMatcher,
                Predicate<Map<String, List<String>>> paramMatcher) {
    this.method = method;
    this.path = path;
    this.handler = handler;
    this.headerMatcher = headerMatcher;
    this.paramMatcher = paramMatcher;
    this.segments = splitPath(path);
    this.variableNames = new String[segments.length];
    StringBuilder trim = new StringBuilder();
    for (int i = 0; i < segments.length; i++) {
      String seg = segments[i];
      if (seg.startsWith("{") && seg.endsWith("}")) {
        variableNames[i] = seg.substring(1, seg.length() - 1);
        trim.append("/{}");
      } else {
        trim.append('/').append(seg);
      }
    }
    this.trimPath = trim.toString();
  }

  /**
   * The number of path segments.
   */
  int segmentCount() {
    return segments.length;
  }

  /**
   * The segment at {@code idx}.
   */
  String segment(int idx) {
    return segments[idx];
  }

  /**
   * The path variable name of the segment at {@code idx}; {@code null} if the segment is literal.
   */
  String variableName(int idx) {
    return variableNames[idx];
  }

  /**
   * Returns a copy of this route with the given header matcher; this route is not modified.
   *
   * @deprecated set the matcher with {@link Builder#headerMatcher(Predicate)} instead.
   */
  @Deprecated(forRemoval = true)
  public Route headerMather(Predicate<Map<String, String>> headerMatcher) {
    return new Route(method, path, handler, headerMatcher, paramMatcher);
  }

  /**
   * Returns a copy of this route with the given parameter matcher; this route is not modified.
   *
   * @deprecated set the matcher with {@link Builder#paramMatcher(Predicate)} instead.
   */
  @Deprecated(forRemoval = true)
  public Route paramMatcher(Predicate<Map<String, List<String>>> paramMatcher) {
    return new Route(method, path, handler, headerMatcher, paramMatcher);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    Route route = (Route) o;
    return method.equals(route.method) && trimPath.equals(route.trimPath) &&
        Objects.equals(headerMatcher, route.headerMatcher) && Objects.equals(paramMatcher, route.paramMatcher);
  }

  @Override
  public int hashCode() {
    return Objects.hash(method, trimPath, headerMatcher, paramMatcher);
  }

  /**
   * Split the path into non-empty segments; consecutive, leading and trailing slashes are ignored.
   */
  static String[] splitPath(String path) {
    Objects.requireNonNull(path, "The path cannot be null.");
    if (!path.startsWith("/")) {
      throw new IllegalArgumentException("The path must start with '/'.");
    }

    List<String> segments = new ArrayList<>();
    StringBuilder seg = new StringBuilder();
    for (int i = 1; i < path.length(); i++) {
      if (path.charAt(i) == '/') {
        if (!(seg.length() == 0)) {
          segments.add(seg.toString());
          seg = new StringBuilder();
        }
      } else {
        seg.append(path.charAt(i));
      }
    }

    if (!(seg.length() == 0)) {
      segments.add(seg.toString());
    }

    return segments.toArray(new String[0]);
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {

    private HttpMethod method;

    private String path;

    private HttpRequestHandler handler;

    private Predicate<Map<String, String>> headerMatcher;

    private Predicate<Map<String, List<String>>> paramMatcher;

    public Builder method(HttpMethod method) {
      this.method = method;
      return this;
    }

    public Builder path(String path) {
      this.path = path;
      return this;
    }

    public Builder headerMatcher(Predicate<Map<String, String>> headerMatcher) {
      this.headerMatcher = headerMatcher;
      return this;
    }

    public Builder paramMatcher(Predicate<Map<String, List<String>>> paramMatcher) {
      this.paramMatcher = paramMatcher;
      return this;
    }

    public Builder handler(HttpRequestHandler handler) {
      this.handler = handler;
      return this;
    }

    public Route build() {
      Objects.requireNonNull(method, "'method' is required.");
      Objects.requireNonNull(path, "'path' is required.");
      Objects.requireNonNull(handler, "'handler' is required.");
      return new Route(method, path, handler, headerMatcher, paramMatcher);
    }

  }

}
