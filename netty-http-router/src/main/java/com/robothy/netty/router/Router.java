package com.robothy.netty.router;


import com.robothy.netty.http.HttpRequest;
import com.robothy.netty.http.HttpRequestHandler;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;

/**
 * A Router is a {@link HttpRequestHandler} container. The route path must start with a '/'.
 *
 * <ul>
 *   <li>"/" and "//" match route("/"); a request path that does not start with '/', e.g. "", matches nothing.</li>
 *   <li>"/a" matches route("/a") firstly, then "/{param}" with 'a' as the value of {@code param}.</li>
 *   <li>A path variable is the whole segment "{name}"; regular expressions such as "{id:[0-9]+}" are not supported,
 *   the text between the braces is taken as the variable name as is.</li>
 * </ul>
 *
 * <p><b>Thread safety:</b> the registration methods ({@link #route(Route)}, {@link #notFound(HttpRequestHandler)},
 * {@link #staticResource(String)}, {@link #headFallbackToGet(boolean)}, {@link #methodNotAllowed(boolean)},
 * {@link #exceptionHandler(Class, ExceptionHandler)} and
 * {@link #interfaceExceptionHandler(Class, ExceptionHandler)}) are not thread-safe. Register everything <b>before the
 * server starts</b> and do not modify the router afterwards; {@link #match(HttpRequest)} and
 * {@link #findExceptionHandler(Class)} are safe to call concurrently once registration is done.
 */
public interface Router {

  HttpRequestHandler DEFAULT_NOT_FOUND_HANDLER = (request, response) -> response.status(HttpResponseStatus.NOT_FOUND)
      .putHeader(HttpHeaderNames.CONTENT_TYPE.toString(), "text/plain; charset=utf-8")
      .write("Netty HTTP Router: 404 Not Found.");

  /**
   * Create a default Router instance.
   */
  static Router router() {
    return new DefaultRouter();
  }

  /**
   * Register a handler for the request that matched rule.
   *
   * @param rule match conditions
   * @return this
   */
  Router route(Route rule);

  default Router route(HttpMethod method, String path, HttpRequestHandler handler) {
    Route rule = Route.builder()
        .method(method)
        .path(path)
        .handler(handler)
        .build();
    return route(rule);
  }

  /**
   * Set resource not found handler.
   *
   * @param handler the handler for requests that match no route; must not be {@code null}.
   * @return this
   */
  Router notFound(HttpRequestHandler handler);

  /**
   * Serve the files under {@code rootPath} to the {@code GET} and {@code HEAD} requests that no route matches, mapping
   * the request path to the relative path of a file under the root. Static resources are <b>off</b> by default.
   *
   * <ul>
   *   <li>{@code rootPath} is either a directory, which must exist, or a classpath resource path such as
   *   {@code classpath:static}, which is looked up with the context class loader of the calling thread.</li>
   *   <li>Routes registered via {@code route()} take precedence over static resources.</li>
   * </ul>
   *
   * @param rootPath static resources root directory, or classpath resource path prefixed with {@code classpath:}.
   * @return this.
   * @throws IllegalArgumentException if {@code rootPath} is a directory that doesn't exist.
   * @throws UnsupportedOperationException if this router doesn't serve static resources; the default.
   */
  default Router staticResource(String rootPath) {
    throw new UnsupportedOperationException(getClass().getName() + " doesn't serve static resources.");
  }

  /**
   * Whether a {@code HEAD} request that no {@code HEAD} route matches is handled by the {@code GET} route of the same
   * path, as RFC 9110 (section 9.3.2) expects of a resource that supports {@code GET}. The response keeps the headers
   * of the {@code GET} response, e.g. its {@code Content-Length}, without the body. Off by default.
   *
   * @param enabled {@code true} to fall back to {@code GET} routes.
   * @return this.
   * @throws UnsupportedOperationException if this router doesn't support it; the default.
   */
  default Router headFallbackToGet(boolean enabled) {
    throw new UnsupportedOperationException(getClass().getName() + " doesn't support falling back to GET routes.");
  }

  /**
   * Whether a request whose path matches routes of other methods only is answered with
   * {@code 405 Method Not Allowed} and an {@code Allow} header listing those methods, instead of the not found
   * handler. Static resources are tried first. Off by default.
   *
   * @param enabled {@code true} to answer with {@code 405 Method Not Allowed}.
   * @return this.
   * @throws UnsupportedOperationException if this router doesn't support it; the default.
   */
  default Router methodNotAllowed(boolean enabled) {
    throw new UnsupportedOperationException(getClass().getName() + " doesn't support 405 Method Not Allowed.");
  }

  /**
   * Set a handler for exceptions with {@code exceptionType}.
   *
   * @param exceptionType subtype of Throwable.
   * @param handler handle specific exception.
   * @return this.
   * @param <T> type of the exception to handle..
   */
  <T extends Throwable> Router exceptionHandler(Class<T> exceptionType, ExceptionHandler<T> handler);

  /**
   * Set a handler for exceptions implementing {@code interfaceType}, e.g. a marker interface shared by several
   * unrelated exception classes.
   *
   * @param interfaceType an interface type; a non-interface type is rejected.
   * @param handler handle exceptions implementing the interface.
   * @return this.
   */
  Router interfaceExceptionHandler(Class<?> interfaceType, ExceptionHandler<Throwable> handler);

  /**
   * Find the best match exception handler for the given {@code exceptionType}. The superclass chain is walked from
   * {@code exceptionType} upwards; at each level the class is checked first, then the interfaces it declares
   * (breadth-first, including their super interfaces). Results are cached.
   */
  ExceptionHandler<Throwable> findExceptionHandler(Class<? extends Throwable> exceptionType);

  /**
   * Find a handler for the given request according to registered routes, and set the path variables of the
   * matched route to {@linkplain HttpRequest#getPathVariables()}, replacing those of a previous match; they are empty
   * if no route matches. Query parameters in {@linkplain HttpRequest#getParams()} are not changed.
   *
   * @param request HTTP request.
   * @return a matched handler; or {@code null} if no matched handlers.
   */
  HttpRequestHandler match(HttpRequest request);

}
