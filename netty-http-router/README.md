# netty-http-router

A library help to build web applications based on Netty.

## 1. Getting Started

### 1.1 Hello World

The HelloWorld example follows the standard steps of starting a Netty application. You only
need to define a `Router` and an executor group that executes HTTP message handlers.

```java
class HelloWorld {
  public static void main(String[] args) throws InterruptedException {
    Router router = Router.router()
        .route(HttpMethod.GET, "/hello", ((request, response) -> response
            .status(HttpResponseStatus.OK)
            .write("Hello World")));

    DefaultEventExecutorGroup executor = new DefaultEventExecutorGroup(2);
    HttpServerInitializer serverInitializer = new HttpServerInitializer(executor, router);

    EventLoopGroup parentGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
    EventLoopGroup childGroup = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
    try {
      Channel serverSocketChannel = new ServerBootstrap().group(parentGroup, childGroup)
          .handler(new LoggingHandler(LogLevel.DEBUG))
          .channel(NioServerSocketChannel.class)
          .childHandler(serverInitializer)
          .bind(8686)
          .sync()
          .channel();
      // Serve until the server channel is closed, e.g. try `curl http://localhost:8686/hello`.
      serverSocketChannel.closeFuture().sync();
    } finally {
      parentGroup.shutdownGracefully();
      childGroup.shutdownGracefully();
      executor.shutdownGracefully();
    }
  }
}
```

## 2. Usages

### 2.1 Request mapping

netty-http-router map an HTTP request to a `HttpMessageHandler` according to the method, path, headers, and query parameters.

#### Request path pattern

A route path must start with `/` and is split into segments by `/`; empty segments are ignored, so `/a//b/` is the
same as `/a/b`. A segment is either a literal, which matches the same text exactly, or a path variable `{name}`, which
matches any single segment.

+ `"/hello"` matches `"/hello"`
+ `"/user/{id}"` matches `/user/123`, `/user/666`, etc.
+ `/user/{id}/profile` matches `/user/123/profile`, `/user/bob/profile`, etc.
+ `"/"` matches `/` and `//`.

A literal segment has priority over a path variable: with both `/user/me` and `/user/{id}` registered, `/user/me`
is handled by the former. If the literal branch has no matched route, the path variable branch is tried.

Regular expressions (e.g. `{id:[0-9]+}`), wildcards (`*`, `**`) and variables in part of a segment (e.g.
`/file.{ext}`) are **not** supported: the whole text between the braces is taken as the variable name, and no
validation is performed on the value. Check the value in the handler instead.

Path variables are read with `request.pathVariable("id")`, query parameters with `request.parameter("id")`;
they are kept apart, so a query parameter never overrides a path variable of the same name.

#### Headers and parameters matcher

netty-http-router allows setting headers and parameters match rules via 
`headerMatcher(header rule)` and `paramMatcher(parameter rule)`. The matching
priority from high to low is HTTP method, path, headers, and parameters.

Header names in the map passed to the header matcher are **lower-case**, and the values of a repeated header are
joined by commas.

```java
Route.builder()
    .method(HttpMethod.HEAD)
    .path("/a/content")
    .headerMatcher(headers -> headers.containsKey("x-authorization"))
    .paramMatcher(params -> params.containsKey("user"))
    .handler(paramRequestHandler)
    .build()
```

### 2.2 Serving static resources

Static resources are **off** by default. Turn them on with `Router#staticResource()`, which takes either a directory,
which must exist, or a classpath resource path prefixed with `classpath:`. Classpath resources are looked up with the
context class loader of the thread that calls `staticResource()`.

```java
Router router = Router.router()
    .route(HttpMethod.GET, "/", handler)
    .staticResource("my-static-resources"); // or .staticResource("classpath:static")
```

Routes take precedence over static resources. Only the router of `Router.router()` serves static resources; for a
subclass of `AbstractRouter`, `staticResource()` throws `UnsupportedOperationException`.

Static resources are served to `GET` and `HEAD` requests only, and only regular files are served, not directories.
A request path with a `..` segment or a backslash is not found, so a request cannot read a file outside of the root.

> **Security note:** every file under the root is readable by anyone who can reach the server, without
> authentication. Don't put secrets under it, and keep in mind that a symbolic link under a root directory is
> followed, even if it points outside of the root.

Files under a root directory are streamed in 64 KB chunks rather than read into memory.

### 2.3 Streaming responses

A handler can stream a large body with `HttpResponse#chunkedBody(ChunkedInput)` instead of writing it to memory.
The response closes the input once it is written, or dropped, e.g. for a `HEAD` request. The `Content-Length` is
the length of the input unless the handler sets it; an input of unknown length, e.g. a `ChunkedStream`, is sent with
`Transfer-Encoding: chunked`.

```java
router.route(HttpMethod.GET, "/export", (request, response) -> response
    .putHeader("Content-Type", "text/csv")
    .chunkedBody(new ChunkedStream(openExport())));
```

A pipeline of your own needs a `ChunkedWriteHandler` between the HTTP codec and the `HttpResponseEncoder`, as
`HttpServerInitializer` has. Request bodies are still aggregated in memory, up to the max request body size.

### 2.4 Not found handler and exception handlers

You can set a not found handler and exception handlers for a Router.

```java
Router router = Router.router()
    .route(HttpMethod.GET, "/", handler)
    .notFound((request, response) -> response.status(HttpResponseStatus.NOT_FOUND))
    .exceptionHandler(IllegalArgumentException.class, (e, request, response) -> response
      .status(HttpResponseStatus.BAD_REQUEST)
      .write(e.getMessage()))
    .exceptionHandler(IllegalStateException.class, (e, request, response) -> response
      .status(HttpResponseStatus.INTERNAL_SERVER_ERROR)
      .write(e.getMessage()))
    ;
```

> [!WARNING]
> Without a handler for its type, an exception, including an `Error` such as `OutOfMemoryError`, is answered by the
> default handler with `500` and the full stack trace as `text/plain; charset=utf-8`. Register a handler for
> `Throwable.class` to keep the stack trace from clients.
