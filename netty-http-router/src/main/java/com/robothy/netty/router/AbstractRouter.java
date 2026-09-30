package com.robothy.netty.router;

import com.robothy.netty.http.HttpRequestHandler;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public abstract class AbstractRouter implements Router {


  private final Map<Class<? extends Throwable>, ExceptionHandler<?>> exceptionHandlerMap;

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

  private StaticResourceMatcher staticResourceMatcher
      = StaticResourceMatcher.create("classpath:static");

  @Override
  public Router notFound(HttpRequestHandler handler) {
    this.notFoundHandler = handler;
    return this;
  }

  @Override
  public Router staticResource(String rootPath) {
    this.staticResourceMatcher = StaticResourceMatcher.create(rootPath);
    return this;
  }

  protected HttpRequestHandler notFoundHandler() {
    return this.notFoundHandler;
  }

  protected StaticResourceMatcher staticResourceMatcher() {
    return this.staticResourceMatcher;
  }

  @Override
  public <T extends Throwable> Router exceptionHandler(Class<T> exceptionType, ExceptionHandler<T> handler) {
    Objects.requireNonNull(handler, "The exception handler shouldn't be null.");
    exceptionHandlerMap.put(exceptionType, handler);
    return this;
  }

  @Override
  public ExceptionHandler<Throwable> findExceptionHandler(Class<? extends Throwable> exceptionType) {
    ExceptionHandler<? extends Throwable> exceptionHandler = null;
    Class<?> type = exceptionType;
    while ( (exceptionHandler = exceptionHandlerMap.get(type)) == null) {
      type = type.getSuperclass();
    }
    //noinspection unchecked
    return (ExceptionHandler<Throwable>) exceptionHandler;
  }


}
