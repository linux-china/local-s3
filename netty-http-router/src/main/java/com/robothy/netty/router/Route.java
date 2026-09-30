package com.robothy.netty.router;

import com.robothy.netty.http.HttpRequestHandler;
import io.netty.handler.codec.http.HttpMethod;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
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

  private final Function<Map<String, String>, Boolean> headerMatcher;

  private final Function<Map<CharSequence, List<String>>, Boolean> paramMatcher;

  private final String trimPath;

  private Route(HttpMethod method, String path, HttpRequestHandler handler,
                Function<Map<String, String>, Boolean> headerMatcher,
                Function<Map<CharSequence, List<String>>, Boolean> paramMatcher, String trimPath) {
    this.method = method;
    this.path = path;
    this.handler = handler;
    this.headerMatcher = headerMatcher;
    this.paramMatcher = paramMatcher;
    this.trimPath = trimPath;
  }

  /**
   * Returns a copy of this route with the given header matcher; this route is not modified.
   *
   * @deprecated set the matcher with {@link Builder#headerMatcher(Function)} instead.
   */
  @Deprecated(forRemoval = true)
  public Route headerMather(Function<Map<String, String>, Boolean> headerMatcher) {
    return new Route(method, path, handler, headerMatcher, paramMatcher, trimPath);
  }

  /**
   * Returns a copy of this route with the given parameter matcher; this route is not modified.
   *
   * @deprecated set the matcher with {@link Builder#paramMatcher(Function)} instead.
   */
  @Deprecated(forRemoval = true)
  public Route paramMatcher(Function<Map<CharSequence, List<String>>, Boolean> paramMatcher) {
    return new Route(method, path, handler, headerMatcher, paramMatcher, trimPath);
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

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {

    private HttpMethod method;

    private String path;

    private HttpRequestHandler handler;

    private Function<Map<String, String>, Boolean> headerMatcher;

    private Function<Map<CharSequence, List<String>>, Boolean> paramMatcher;

    public Builder method(HttpMethod method) {
      this.method = method;
      return this;
    }

    public Builder path(String path) {
      this.path = path;
      return this;
    }

    public Builder headerMatcher(Function<Map<String, String>, Boolean> headerMatcher) {
      this.headerMatcher = headerMatcher;
      return this;
    }

    public Builder paramMatcher(Function<Map<CharSequence, List<String>>, Boolean> paramMatcher) {
      this.paramMatcher = paramMatcher;
      return this;
    }

    public Builder handler(HttpRequestHandler handler) {
      this.handler = handler;
      return this;
    }

    private String trimPath(String path) {
      String[] segments = path.split("/");
      StringBuilder result = new StringBuilder();
      for (String seg : segments) {
        if (seg.startsWith("{") && seg.endsWith("}")) {
          result.append("/{}");
        } else {
          result.append('/').append(seg);
        }
      }
      return result.toString();
    }

    public Route build() {
      Objects.requireNonNull(method, "'method' is required.");
      Objects.requireNonNull(path, "'path' is required.");
      Objects.requireNonNull(handler, "'handler' is required.");
      return new Route(method, path, handler, headerMatcher, paramMatcher, trimPath(path));
    }

  }

}
