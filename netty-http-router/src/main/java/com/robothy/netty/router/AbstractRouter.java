package com.robothy.netty.router;

import com.robothy.netty.http.HttpRequestHandler;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Base {@link Router} implementation that manages the not found handler and exception handlers. Static resources are
 * served by {@link DefaultRouter} only, see {@link Router#staticResource(String)}.
 *
 * <p><b>Thread safety:</b> registration methods ({@code route}, {@code notFound}, {@code exceptionHandler}) are not thread-safe and must only be called before the server starts. Once the server is
 * running, the router is only read ({@code match}, {@code findExceptionHandler}), which is safe from multiple threads.
 */
public abstract class AbstractRouter implements Router {

  /**
   * Registered handlers keyed by exception class or interface. Only written before the server starts.
   */
  private final Map<Class<?>, ExceptionHandler<?>> exceptionHandlerMap;

  /**
   * Resolved handlers keyed by the thrown exception class, filled lazily by {@link #findExceptionHandler(Class)}
   * from I/O threads and cleared whenever a handler is registered.
   */
  private final Map<Class<?>, ExceptionHandler<?>> resolvedHandlerCache = new ConcurrentHashMap<>();

  // The default handler for exceptions without a more specific handler. It writes the full stack trace to the
  // response body, which exposes server internals to clients; register a handler for Throwable to override it.
  {
    exceptionHandlerMap = new HashMap<>();
    exceptionHandlerMap.put(Throwable.class, (cause, request, response) -> {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      cause.printStackTrace(new PrintStream(out, true, StandardCharsets.UTF_8));
      response.write(out.toString(StandardCharsets.UTF_8))
          .putHeader(HttpHeaderNames.CONTENT_TYPE.toString(), "text/plain; charset=utf-8")
          .status(HttpResponseStatus.INTERNAL_SERVER_ERROR);
    });
  }

  private HttpRequestHandler notFoundHandler = DEFAULT_NOT_FOUND_HANDLER;

  @Override
  public Router notFound(HttpRequestHandler handler) {
    this.notFoundHandler = handler;
    return this;
  }

  protected HttpRequestHandler notFoundHandler() {
    return this.notFoundHandler;
  }

  @Override
  public <T extends Throwable> Router exceptionHandler(Class<T> exceptionType, ExceptionHandler<T> handler) {
    Objects.requireNonNull(exceptionType, "The exception type shouldn't be null.");
    Objects.requireNonNull(handler, "The exception handler shouldn't be null.");
    exceptionHandlerMap.put(exceptionType, handler);
    resolvedHandlerCache.clear();
    return this;
  }

  @Override
  public Router interfaceExceptionHandler(Class<?> interfaceType, ExceptionHandler<Throwable> handler) {
    Objects.requireNonNull(interfaceType, "The interface type shouldn't be null.");
    Objects.requireNonNull(handler, "The exception handler shouldn't be null.");
    if (!interfaceType.isInterface()) {
      throw new IllegalArgumentException(interfaceType.getName() + " is not an interface.");
    }
    exceptionHandlerMap.put(interfaceType, handler);
    resolvedHandlerCache.clear();
    return this;
  }

  @Override
  public ExceptionHandler<Throwable> findExceptionHandler(Class<? extends Throwable> exceptionType) {
    //noinspection unchecked
    return (ExceptionHandler<Throwable>) resolvedHandlerCache.computeIfAbsent(exceptionType, this::resolveExceptionHandler);
  }

  /**
   * Walk up the superclass chain of {@code exceptionType}. At each level, the class itself is checked first, then the
   * interfaces it declares (and their super interfaces, breadth-first), before moving on to the superclass. A handler
   * for {@link Throwable} is always registered, so the lookup never fails.
   */
  private ExceptionHandler<?> resolveExceptionHandler(Class<?> exceptionType) {
    Set<Class<?>> visitedInterfaces = new HashSet<>();
    for (Class<?> type = exceptionType; type != null; type = type.getSuperclass()) {
      ExceptionHandler<?> handler = exceptionHandlerMap.get(type);
      if (handler != null) {
        return handler;
      }
      Deque<Class<?>> queue = new ArrayDeque<>(List.of(type.getInterfaces()));
      while (!queue.isEmpty()) {
        Class<?> iface = queue.poll();
        if (!visitedInterfaces.add(iface)) {
          continue;
        }
        if ((handler = exceptionHandlerMap.get(iface)) != null) {
          return handler;
        }
        queue.addAll(List.of(iface.getInterfaces()));
      }
    }
    throw new IllegalStateException("No exception handler found for " + exceptionType.getName());
  }


}
