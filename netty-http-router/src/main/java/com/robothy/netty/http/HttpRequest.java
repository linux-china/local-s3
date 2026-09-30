package com.robothy.netty.http;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Getter
public class HttpRequest {

  /**
   * Request headers keyed by lower-case names; the values of a repeated header are joined by commas. Look a header up
   * with a lower-case {@linkplain String}, e.g. {@code HttpHeaderNames.HOST.toString()}, or with
   * {@linkplain #header(CharSequence)}, which accepts any {@linkplain CharSequence} in any case.
   */
  @Getter(lombok.AccessLevel.NONE)
  private final Map<String, String> headers;

  @Getter(lombok.AccessLevel.NONE)
  private final Map<String, List<String>> params;

  /**
   * Path variables of the matched route, e.g. {@code id} of {@code /user/{id}}, set by
   * {@linkplain com.robothy.netty.router.Router#match(HttpRequest)}. Query parameters are in {@link #params}, so a
   * query parameter never overrides a path variable of the same name, or the other way around.
   */
  @Setter
  private Map<String, String> pathVariables;

  private String path;

  private String uri;

  private HttpMethod method;

  private ByteBuf body;

  private HttpVersion httpVersion;

  @Builder
  private HttpRequest(Map<String, String> headers, Map<String, List<String>> params, Map<String, String> pathVariables,
                      String path, String uri, HttpMethod method, ByteBuf body, HttpVersion httpVersion) {
    this.headers = headers == null ? new HashMap<>() : new HashMap<>(headers);
    this.params = new HashMap<>();
    if (params != null) {
      params.forEach((name, values) -> this.params.put(name, List.copyOf(values)));
    }
    this.pathVariables = pathVariables == null ? Map.of() : pathVariables;
    this.path = path;
    this.uri = uri;
    this.method = method;
    this.body = body;
    this.httpVersion = httpVersion;
  }

  /**
   * Request headers keyed by lower-case names, read-only.
   *
   * @return an unmodifiable view of the headers.
   */
  public Map<String, String> getHeaders() {
    return Collections.unmodifiableMap(headers);
  }

  /**
   * Query parameters, read-only. Use {@linkplain #putParameter(String, List)} to add a parameter while routing.
   *
   * @return an unmodifiable view of the query parameters; each value list is immutable as well.
   */
  public Map<String, List<String>> getParams() {
    return Collections.unmodifiableMap(params);
  }

  /**
   * Set a parameter, e.g. the bucket name or the object key resolved by a router from the path or the host.
   *
   * @param name parameter name.
   * @param values parameter values.
   */
  public void putParameter(String name, List<String> values) {
    Objects.requireNonNull(name, "The parameter name shouldn't be null.");
    params.put(name, List.copyOf(values));
  }

  /**
   * Get the header value by name.
   *
   * @param name header name.
   * @return the header value.
   */
  public Optional<String> header(CharSequence name) {
    Objects.requireNonNull(name, "The header name shouldn't be null.");
    return Optional.ofNullable(headers.get(name.toString().toLowerCase(Locale.ROOT)));
  }

  /**
   * Get the first value of a query parameter.
   *
   * @param name parameter name.
   * @return the first value of the parameter.
   */
  public Optional<String> parameter(String name) {
    return Optional.ofNullable(params.get(name)).filter(values -> !values.isEmpty()).map(values -> values.get(0));
  }

  /**
   * Get the value of a path variable.
   *
   * @param name path variable name, e.g. {@code id} of {@code /user/{id}}.
   * @return the path variable value.
   */
  public Optional<String> pathVariable(String name) {
    return Optional.ofNullable(pathVariables.get(name));
  }

  /**
   * Get the query parameter values by name. Path variables are in {@linkplain #pathVariable(String)}.
   *
   * @param name parameter name.
   * @return the parameter values.
   */
  public Optional<List<String>> parameters(String name) {
    return Optional.ofNullable(params.get(name));
  }

}
