package com.robothy.netty.http;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
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
@Builder
public class HttpRequest {

  /**
   * Request headers keyed by lower-case names; the values of a repeated header are joined by commas. Look a header up
   * with a lower-case {@linkplain String}, e.g. {@code HttpHeaderNames.HOST.toString()}, or with
   * {@linkplain #header(CharSequence)}, which accepts any {@linkplain CharSequence} in any case.
   */
  @Builder.Default
  private Map<String, String> headers = new HashMap<>();

  @Builder.Default
  private Map<CharSequence, List<String>> params = new HashMap<>();

  /**
   * Path variables of the matched route, e.g. {@code id} of {@code /user/{id}}, set by
   * {@linkplain com.robothy.netty.router.Router#match(HttpRequest)}. Query parameters are in {@link #params}, so a
   * query parameter never overrides a path variable of the same name, or the other way around.
   */
  @Setter
  @Builder.Default
  private Map<String, String> pathVariables = Map.of();

  private String path;

  private String uri;

  private HttpMethod method;

  private ByteBuf body;

  private HttpVersion httpVersion;

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
    if (params.containsKey(name) && params.get(name).size() > 0) {
      return Optional.ofNullable(params.get(name).get(0));
    }
    return Optional.empty();
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
