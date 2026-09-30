package com.robothy.netty.router;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.robothy.netty.http.HttpRequest;
import com.robothy.netty.http.HttpRequestHandler;
import com.robothy.netty.http.HttpResponse;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.stream.ChunkedInput;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

class DefaultRouterTest {

  private static final HttpRequestHandler NOT_FOUND = (request, response) -> { };

  @Test
  void match() throws Exception {
    DefaultRouter router = new DefaultRouter();
    HttpRequestHandler listHandler = Mockito.mock(HttpRequestHandler.class);
    assertThrows(IllegalArgumentException.class, () -> router.route(HttpMethod.GET, "", listHandler));
    assertThrows(IllegalArgumentException.class, () -> router.route(HttpMethod.GET, "/a/{}", listHandler));
    router.route(Route.builder().method(HttpMethod.GET).path("/list").handler(listHandler).build());
    HttpRequest.HttpRequestBuilder requestBuilder = HttpRequest.builder();
    HttpRequest listRequest = requestBuilder
        .method(HttpMethod.GET)
        .uri("/list?id=1")
        .path("/list")
        .params(new HashMap<>())
        .headers(new HashMap<>())
        .build();
    assertEquals(listHandler, router.match(listRequest));

    HttpRequestHandler notFoundHandler = Mockito.mock(HttpRequestHandler.class);
    assertEquals(DefaultRouter.DEFAULT_NOT_FOUND_HANDLER, router.match(requestBuilder.path("/list/a").build()));
    router.notFound(notFoundHandler);
    assertEquals(notFoundHandler, router.match(requestBuilder.path("/").build()));
    assertEquals(notFoundHandler, router.match(requestBuilder.method(HttpMethod.PUT).path("/list").build()));
    assertEquals(notFoundHandler, router.match(requestBuilder.method(HttpMethod.PUT).path("/list/").build()));

    HttpRequestHandler actionHandler = Mockito.mock(HttpRequestHandler.class);
    router.route(HttpMethod.GET, "/{action}", actionHandler);
    assertEquals(notFoundHandler, router.match(requestBuilder.path("/").build()));
    HttpRequest actionRequest = requestBuilder.method(HttpMethod.GET).path("/read").build();
    assertEquals(actionHandler, router.match(actionRequest));
    assertEquals("read", actionRequest.pathVariable("action").orElseThrow());
    assertEquals(listHandler, router.match(requestBuilder.path("/list").build()));

    HttpRequestHandler emptyPathHandler = Mockito.mock(HttpRequestHandler.class);
    router.route(HttpMethod.POST, "//", emptyPathHandler);
    assertEquals(emptyPathHandler, router.match(requestBuilder.method(HttpMethod.POST).path("/").build()));

    HttpRequestHandler postContentHandler = Mockito.mock(HttpRequestHandler.class);
    HttpRequestHandler postAHandler = Mockito.mock(HttpRequestHandler.class);
    router.route(HttpMethod.POST, "/{id}/content", postContentHandler);
    router.route(HttpMethod.POST, "/a/content", postAHandler);
    HttpRequest postContentRequest = requestBuilder.method(HttpMethod.POST).path("/123/content").build();
    assertEquals(postContentHandler, router.match(postContentRequest));
    assertEquals(postAHandler, router.match(requestBuilder.path("/a/content").build()));

    HttpRequestHandler paramRequestHandler = Mockito.mock(HttpRequestHandler.class);
    router.route(Route.builder()
        .method(HttpMethod.HEAD)
        .path("/a/content")
        .paramMatcher(ps -> ps.containsKey("version"))
        .handler(paramRequestHandler)
        .build());
    Map<String, List<String>> parameters = new HashMap<>();
    parameters.put("version", Collections.emptyList());
    assertEquals(notFoundHandler, router.match(requestBuilder.method(HttpMethod.HEAD).path("/a/content").build()));
    assertEquals(paramRequestHandler, router.match(requestBuilder.params(parameters).build()));

    HttpRequestHandler headerRequestHandler = Mockito.mock(HttpRequestHandler.class);
    router.route(Route.builder().method(HttpMethod.HEAD).path("/a/content")
            .headerMatcher(hs -> hs.containsKey("hello"))
            .handler(headerRequestHandler)
            .build());
    assertEquals(paramRequestHandler, router.match(requestBuilder.method(HttpMethod.HEAD).path("/a/content").build()));
    Map<String, String> headers = new HashMap<>();
    headers.put("hello", "");
    // The header matcher has higher priority.
    assertEquals(headerRequestHandler, router.match(requestBuilder.headers(headers).build()));

    HttpRequestHandler headerParamHandler = Mockito.mock(HttpRequestHandler.class);
    router.route(Route.builder()
            .method(HttpMethod.HEAD)
            .path("/a/content")
            .headerMatcher(hs -> hs.containsKey("hello"))
            .paramMatcher(ps -> ps.containsKey("version"))
            .handler(headerParamHandler)
        .build());
    assertEquals(headerParamHandler, router.match(requestBuilder.build()));
  }

  @Test
  void testStaticResources() {
    Router router = new DefaultRouter().notFound(NOT_FOUND);
    // Off by default, e.g. the "static" directory of a Spring Boot application on the classpath isn't served.
    assertSame(NOT_FOUND, router.match(getRequest("/test.html")));

    /* Static resources in classpath:static */
    router.staticResource("classpath:static");
    assertNotSame(NOT_FOUND, router.match(getRequest("/test.html")));

    /* Static resources in directory */
    router.staticResource("src");
    assertNotSame(NOT_FOUND, router.match(getRequest("/test/java/com/robothy/netty/router/DefaultRouterTest.java")));
    assertSame(NOT_FOUND, router.match(HttpRequest.builder()
        .method(HttpMethod.POST)
        .path("/test/java/com/robothy/netty/router/DefaultRouterTest.java").build()));
  }

  @Test
  void staticResourceDirectoryMustExist(@TempDir Path directory) throws Exception {
    Router router = new DefaultRouter();
    assertThrows(IllegalArgumentException.class, () -> router.staticResource(directory.resolve("missing").toString()));
    Path file = Files.writeString(directory.resolve("file.txt"), "file");
    assertThrows(IllegalArgumentException.class, () -> router.staticResource(file.toString()));
  }

  @Test
  void staticResourcesAreOnlyServedByDefaultRouter() {
    Router router = new AbstractRouter() {
      @Override
      public Router route(Route rule) {
        return this;
      }

      @Override
      public HttpRequestHandler match(HttpRequest request) {
        return notFoundHandler();
      }
    };
    assertThrows(UnsupportedOperationException.class, () -> router.staticResource("classpath:static"));
  }

  @Test
  void staticResourceContentType(@TempDir Path directory) throws Exception {
    Router router = new DefaultRouter().staticResource("classpath:static");
    HttpRequest classpathRequest = getRequest("/test.html");
    HttpResponse classpathResponse = new HttpResponse();
    router.match(classpathRequest).handle(classpathRequest, classpathResponse);
    assertEquals("text/html; charset=utf-8", classpathResponse.getHeaders().get("content-type"));
    readBody(classpathResponse);

    Files.writeString(directory.resolve("data.no-such-extension"), "data");
    router.staticResource(directory.toString());
    HttpRequest fileRequest = getRequest("/data.no-such-extension");
    HttpResponse fileResponse = new HttpResponse();
    router.match(fileRequest).handle(fileRequest, fileResponse);
    assertEquals("application/octet-stream", fileResponse.getHeaders().get("content-type"));
    readBody(fileResponse);
  }

  @Test
  void classpathResourcesStayUnderRoot() {
    Router router = new DefaultRouter().notFound(NOT_FOUND).staticResource("classpath:static");
    assertNotSame(NOT_FOUND, router.match(getRequest("/test.html")));
    // The test classes are on the classpath next to "static", so "static/../com/..." resolves to one of them.
    assertSame(NOT_FOUND, router.match(getRequest("/../com/robothy/netty/router/DefaultRouterTest.class")));
    assertSame(NOT_FOUND, router.match(getRequest("/x/../../com/robothy/netty/router/DefaultRouterTest.class")));
    assertSame(NOT_FOUND, router.match(getRequest("/..\\com/robothy/netty/router/DefaultRouterTest.class")));
  }

  @Test
  void classpathResourcesAreRegularFilesServedToGet() {
    Router router = new DefaultRouter().notFound(NOT_FOUND).staticResource("classpath:static");
    // A directory would be answered with a listing of its files.
    assertSame(NOT_FOUND, router.match(getRequest("/../static")));
    assertSame(NOT_FOUND, router.match(getRequest("/../com/robothy/netty")));
    HttpRequest post = HttpRequest.builder().method(HttpMethod.POST).uri("/test.html").path("/test.html").build();
    assertSame(NOT_FOUND, router.match(post));
  }

  @Test
  void classpathResourcesInJar(@TempDir Path directory) throws Exception {
    Path jar = directory.resolve("static.jar");
    try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
      out.putNextEntry(new JarEntry("jar-static/"));
      out.putNextEntry(new JarEntry("jar-static/dir/"));
      out.putNextEntry(new JarEntry("jar-static/a.txt"));
      out.write("a".getBytes(StandardCharsets.UTF_8));
      out.closeEntry();
    }

    Thread thread = Thread.currentThread();
    ClassLoader contextClassLoader = thread.getContextClassLoader();
    try (URLClassLoader classLoader = new URLClassLoader(new URL[] {jar.toUri().toURL()}, contextClassLoader)) {
      thread.setContextClassLoader(classLoader);
      Router router = new DefaultRouter().notFound(NOT_FOUND).staticResource("classpath:jar-static");
      // The class loader is the one of the thread that set the root, not of the thread that serves the request.
      thread.setContextClassLoader(contextClassLoader);
      HttpRequest request = getRequest("/a.txt");
      HttpResponse response = new HttpResponse();
      router.match(request).handle(request, response);
      assertEquals("a", new String(readBody(response), StandardCharsets.UTF_8));
      assertSame(NOT_FOUND, router.match(getRequest("/dir")));
    } finally {
      thread.setContextClassLoader(contextClassLoader);
    }
  }

  @Test
  void directoryResourcesStayUnderRoot(@TempDir Path directory) throws Exception {
    Path root = Files.createDirectory(directory.resolve("root"));
    Files.writeString(root.resolve("inside.txt"), "inside");
    Files.writeString(directory.resolve("outside.txt"), "outside");
    Files.createDirectory(root.resolve("sub"));
    Router router = new DefaultRouter().notFound(NOT_FOUND).staticResource(root.toString());

    HttpRequest inside = getRequest("/inside.txt");
    HttpResponse response = new HttpResponse();
    router.match(inside).handle(inside, response);
    assertEquals("inside", new String(readBody(response), StandardCharsets.UTF_8));

    assertSame(NOT_FOUND, router.match(getRequest("/../outside.txt")));
    assertSame(NOT_FOUND, router.match(getRequest("/sub/../../outside.txt")));
    // A directory is not found, instead of failing to be read.
    assertSame(NOT_FOUND, router.match(getRequest("/sub")));
  }

  @Test
  void staticResourceContent(@TempDir Path directory) throws Exception {
    // Both files are streamed, the large one in many chunks.
    byte[] small = new byte[64 * 1024];
    byte[] large = new byte[10 * 1024 * 1024 + 1];
    new Random(1).nextBytes(small);
    new Random(2).nextBytes(large);
    Files.write(directory.resolve("small.bin"), small);
    Files.write(directory.resolve("large.bin"), large);
    Router router = new DefaultRouter().staticResource(directory.toString());

    for (String name : List.of("small.bin", "large.bin")) {
      HttpRequest request = getRequest("/" + name);
      HttpResponse response = new HttpResponse();
      router.match(request).handle(request, response);
      byte[] expected = name.equals("small.bin") ? small : large;
      assertEquals(String.valueOf(expected.length), response.getHeaders().get("content-length"));
      assertNotNull(response.getChunkedBody());
      assertEquals(0, response.getBody().readableBytes());
      assertArrayEquals(expected, readBody(response));
    }
  }

  @Test
  void staticFileTruncatedWhileSent(@TempDir Path directory) throws Exception {
    Path file = directory.resolve("data.bin");
    Files.write(file, new byte[200 * 1024]);
    Router router = new DefaultRouter().staticResource(directory.toString());
    HttpRequest request = getRequest("/data.bin");
    HttpResponse response = new HttpResponse();
    router.match(request).handle(request, response);

    try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
      channel.truncate(70 * 1024);
    }
    ChunkedInput<ByteBuf> input = response.detachChunkedBody();
    try {
      input.readChunk(ByteBufAllocator.DEFAULT).release();
      // Without the check, the chunks after the end of the file would be empty, and the end never reached.
      assertThrows(EOFException.class, () -> input.readChunk(ByteBufAllocator.DEFAULT));
    } finally {
      input.close();
      response.getBody().release();
    }
  }

  /**
   * Read the buffered or the chunked body of {@code response}, and release or close it.
   */
  private static byte[] readBody(HttpResponse response) throws Exception {
    ChunkedInput<ByteBuf> input = response.detachChunkedBody();
    try {
      if (input == null) {
        return ByteBufUtil.getBytes(response.getBody());
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      ByteBuf chunk;
      while ((chunk = input.readChunk(ByteBufAllocator.DEFAULT)) != null) {
        out.write(ByteBufUtil.getBytes(chunk));
        chunk.release();
      }
      return out.toByteArray();
    } finally {
      response.getBody().release();
      HttpResponse.closeQuietly(input);
    }
  }

  @Test
  void matchRoutesWithSamePriority() {
    DefaultRouter router = new DefaultRouter();
    HttpRequestHandler aclHandler = Mockito.mock(HttpRequestHandler.class);
    HttpRequestHandler taggingHandler = Mockito.mock(HttpRequestHandler.class);
    HttpRequestHandler jsonHandler = Mockito.mock(HttpRequestHandler.class);
    HttpRequestHandler xmlHandler = Mockito.mock(HttpRequestHandler.class);
    HttpRequestHandler defaultHandler = Mockito.mock(HttpRequestHandler.class);
    router.route(HttpMethod.GET, "/{bucket}", defaultHandler)
        .route(Route.builder().method(HttpMethod.GET).path("/{bucket}")
            .paramMatcher(params -> params.containsKey("acl")).handler(aclHandler).build())
        .route(Route.builder().method(HttpMethod.GET).path("/{bucket}")
            .paramMatcher(params -> params.containsKey("tagging")).handler(taggingHandler).build())
        .route(Route.builder().method(HttpMethod.GET).path("/{bucket}")
            .headerMatcher(headers -> "application/json".equals(headers.get("accept"))).handler(jsonHandler).build())
        .route(Route.builder().method(HttpMethod.GET).path("/{bucket}")
            .headerMatcher(headers -> "application/xml".equals(headers.get("accept"))).handler(xmlHandler).build());

    assertEquals(aclHandler, router.match(bucketRequest(Map.of("acl", List.of("")), Map.of())));
    assertEquals(taggingHandler, router.match(bucketRequest(Map.of("tagging", List.of("")), Map.of())));
    assertEquals(jsonHandler, router.match(bucketRequest(Map.of(), Map.of("accept", "application/json"))));
    assertEquals(xmlHandler, router.match(bucketRequest(Map.of(), Map.of("accept", "application/xml"))));
    // Header matchers have higher priority than param matchers.
    assertEquals(xmlHandler, router.match(bucketRequest(Map.of("acl", List.of("")), Map.of("accept", "application/xml"))));
    assertEquals(defaultHandler, router.match(bucketRequest(Map.of(), Map.of())));
  }

  @Test
  void matchBacktracksToPathVariable() {
    DefaultRouter router = new DefaultRouter();
    HttpRequestHandler exactHandler = Mockito.mock(HttpRequestHandler.class);
    HttpRequestHandler variableHandler = Mockito.mock(HttpRequestHandler.class);
    router.route(HttpMethod.GET, "/a/b/c", exactHandler)
        .route(HttpMethod.GET, "/{x}/b/d", variableHandler);

    assertEquals(exactHandler, router.match(getRequest("/a/b/c")));
    // The exact branch "/a/b" has no child "d", backtrack to "/{x}/b/d".
    HttpRequest request = getRequest("/a/b/d");
    assertEquals(variableHandler, router.match(request));
    assertEquals(Map.of("x", "a"), request.getPathVariables());

    DefaultRouter paramRouter = new DefaultRouter();
    HttpRequestHandler paramHandler = Mockito.mock(HttpRequestHandler.class);
    paramRouter.route(Route.builder().method(HttpMethod.GET).path("/a")
            .paramMatcher(params -> params.containsKey("z")).handler(paramHandler).build())
        .route(HttpMethod.GET, "/{x}", variableHandler);
    // The exact route "/a" has priority when it matches.
    HttpRequest paramRequest = getRequest("/a");
    paramRequest.putParameter("z", List.of(""));
    assertEquals(paramHandler, paramRouter.match(paramRequest));
    // The exact route "/a" does not match the params, backtrack to "/{x}".
    assertEquals(variableHandler, paramRouter.match(getRequest("/a")));
  }

  @Test
  void matchNoRouteForTargetNotInOriginForm() {
    DefaultRouter router = new DefaultRouter();
    HttpRequestHandler handler = Mockito.mock(HttpRequestHandler.class);
    router.route(HttpMethod.GET, "/{x}", handler);
    assertEquals(router.notFoundHandler(), router.match(getRequest("*")));
    assertEquals(router.notFoundHandler(), router.match(getRequest("http://host/a")));
  }

  @Test
  void pathVariablesAreSeparatedFromQueryParameters() {
    DefaultRouter router = new DefaultRouter();
    HttpRequestHandler userHandler = Mockito.mock(HttpRequestHandler.class);
    router.route(HttpMethod.GET, "/user/{id}", userHandler);

    HttpRequest request = getRequest("/user/123");
    request.putParameter("id", List.of("x"));
    assertEquals(userHandler, router.match(request));
    assertEquals("123", request.pathVariable("id").orElseThrow());
    // The query parameter of the same name is not changed.
    assertEquals(Map.of("id", List.of("x")), request.getParams());

    // No path variables are left from a previous match if no route matches.
    HttpRequest notFoundRequest = getRequest("/order/123");
    notFoundRequest.setPathVariables(Map.of("id", "stale"));
    assertEquals(DefaultRouter.DEFAULT_NOT_FOUND_HANDLER, router.match(notFoundRequest));
    assertTrue(notFoundRequest.getPathVariables().isEmpty());
  }

  @Test
  void rejectRoutesThatOnlyDifferInEmptySegments() {
    DefaultRouter router = new DefaultRouter();
    HttpRequestHandler handler = Mockito.mock(HttpRequestHandler.class);
    router.route(HttpMethod.GET, "/a/b", handler);
    assertThrows(IllegalArgumentException.class, () -> router.route(HttpMethod.GET, "/a//b", handler));
    assertThrows(IllegalArgumentException.class, () -> router.route(HttpMethod.GET, "/a/b/", handler));
    assertThrows(IllegalArgumentException.class, () -> router.route(HttpMethod.GET, "//a/b", handler));
  }

  @Test
  void rejectInvalidPathVariables() {
    DefaultRouter router = new DefaultRouter();
    HttpRequestHandler handler = Mockito.mock(HttpRequestHandler.class);
    assertThrows(IllegalArgumentException.class, () -> router.route(HttpMethod.GET, "/{id}/a/{id}", handler));
    assertThrows(IllegalArgumentException.class, () -> router.route(HttpMethod.GET, "/b/{}", handler));
    // The rejected routes are not registered, so they can be registered again once fixed.
    router.route(HttpMethod.GET, "/{id}/a/{name}", handler);
    router.route(HttpMethod.GET, "/b/{name}", handler);
  }

  private static HttpRequest request(HttpMethod method, String path) {
    return HttpRequest.builder().method(method).uri(path).path(path).build();
  }

  @Test
  void headFallbackAndMethodNotAllowedAreOffByDefault() {
    Router router = new DefaultRouter().notFound(NOT_FOUND)
        .route(HttpMethod.GET, "/user/{id}", (request, response) -> response.write("user"));
    assertSame(NOT_FOUND, router.match(request(HttpMethod.HEAD, "/user/1")));
    assertSame(NOT_FOUND, router.match(request(HttpMethod.POST, "/user/1")));
  }

  @Test
  void headFallsBackToGet() {
    HttpRequestHandler get = (request, response) -> response.write("get");
    HttpRequestHandler head = (request, response) -> { };
    Router router = new DefaultRouter().notFound(NOT_FOUND).headFallbackToGet(true)
        .route(HttpMethod.GET, "/user/{id}", get)
        .route(HttpMethod.GET, "/file", get)
        .route(HttpMethod.HEAD, "/file", head);

    HttpRequest request = request(HttpMethod.HEAD, "/user/1");
    assertSame(get, router.match(request));
    assertEquals("1", request.pathVariable("id").orElse(null));
    // A HEAD route takes precedence.
    assertSame(head, router.match(request(HttpMethod.HEAD, "/file")));
    // Only HEAD falls back.
    assertSame(NOT_FOUND, router.match(request(HttpMethod.POST, "/user/1")));
  }

  @Test
  void methodNotAllowed(@TempDir Path directory) throws Exception {
    HttpRequestHandler handler = (request, response) -> { };
    Router router = new DefaultRouter().notFound(NOT_FOUND).methodNotAllowed(true)
        .route(HttpMethod.GET, "/user/{id}", handler)
        .route(HttpMethod.DELETE, "/user/{id}", handler)
        .route(HttpMethod.POST, "/upload", handler);

    HttpRequest post = request(HttpMethod.POST, "/user/1");
    HttpResponse response = new HttpResponse();
    router.match(post).handle(post, response);
    assertEquals(HttpResponseStatus.METHOD_NOT_ALLOWED, response.getStatus());
    assertEquals("DELETE, GET", response.getHeaders().get("allow"));
    response.getBody().release();

    // A path that no route of any method matches is not found.
    assertSame(NOT_FOUND, router.match(request(HttpMethod.POST, "/user/1/profile")));

    // HEAD is allowed along with GET when it falls back to it.
    router.headFallbackToGet(true);
    response = new HttpResponse();
    router.match(post).handle(post, response);
    assertEquals("DELETE, GET, HEAD", response.getHeaders().get("allow"));
    response.getBody().release();

    // A static resource is served before the request is answered with 405.
    Files.writeString(directory.resolve("upload"), "file");
    router.staticResource(directory.toString());
    HttpRequest get = getRequest("/upload");
    response = new HttpResponse();
    router.match(get).handle(get, response);
    assertEquals("file", new String(readBody(response), StandardCharsets.UTF_8));
  }

  @Test
  void headFallbackAndMethodNotAllowedNeedDefaultRouter() {
    Router router = new AbstractRouter() {
      @Override
      public Router route(Route rule) {
        return this;
      }

      @Override
      public HttpRequestHandler match(HttpRequest request) {
        return notFoundHandler();
      }
    };
    assertThrows(UnsupportedOperationException.class, () -> router.headFallbackToGet(true));
    assertThrows(UnsupportedOperationException.class, () -> router.methodNotAllowed(true));
  }

  private static HttpRequest getRequest(String path) {
    return HttpRequest.builder()
        .method(HttpMethod.GET)
        .uri(path)
        .path(path)
        .params(new HashMap<>())
        .headers(new HashMap<>())
        .build();
  }

  private static HttpRequest bucketRequest(Map<String, List<String>> params, Map<String, String> headers) {
    return HttpRequest.builder()
        .method(HttpMethod.GET)
        .uri("/bucket")
        .path("/bucket")
        .params(new HashMap<>(params))
        .headers(new HashMap<>(headers))
        .build();
  }

  @Test
  void testExceptionHandler() {
    DefaultRouter router = new DefaultRouter();
    assertNotNull(router.findExceptionHandler(RuntimeException.class));
    // Both found the default exception handler.
    assertEquals(router.findExceptionHandler(RuntimeException.class), router.findExceptionHandler(IOException.class));

    ExceptionHandler<RuntimeException> runtimeExceptionHandler = Mockito.mock(ExceptionHandler.class);
    router.exceptionHandler(RuntimeException.class, runtimeExceptionHandler);
    assertEquals(runtimeExceptionHandler, router.findExceptionHandler(RuntimeException.class));

    class SubRuntimeException extends RuntimeException {

    }
    // Not register handler for SubRuntimeException, should return the handler for RuntimeException.
    assertEquals(runtimeExceptionHandler, router.findExceptionHandler(SubRuntimeException.class));
    ExceptionHandler<SubRuntimeException> subRuntimeExceptionHandler = Mockito.mock(ExceptionHandler.class);
    router.exceptionHandler(SubRuntimeException.class, subRuntimeExceptionHandler);
    assertEquals(subRuntimeExceptionHandler, router.findExceptionHandler(SubRuntimeException.class));
  }

  interface Retryable {
  }

  interface TransientFailure extends Retryable {
  }

  static class RetryableIOException extends IOException implements TransientFailure {
  }

  static class SubRetryableIOException extends RetryableIOException {
  }

  @Test
  void testInterfaceExceptionHandler() {
    DefaultRouter router = new DefaultRouter();
    ExceptionHandler<Throwable> defaultHandler = router.findExceptionHandler(Throwable.class);
    ExceptionHandler<IOException> ioExceptionHandler = Mockito.mock(ExceptionHandler.class);
    router.exceptionHandler(IOException.class, ioExceptionHandler);
    assertEquals(ioExceptionHandler, router.findExceptionHandler(SubRetryableIOException.class));

    // A super interface declared at a more specific level wins over the handler of a superclass.
    ExceptionHandler<Throwable> retryableHandler = Mockito.mock(ExceptionHandler.class);
    router.interfaceExceptionHandler(Retryable.class, retryableHandler);
    assertEquals(retryableHandler, router.findExceptionHandler(RetryableIOException.class));
    assertEquals(retryableHandler, router.findExceptionHandler(SubRetryableIOException.class));
    assertEquals(ioExceptionHandler, router.findExceptionHandler(IOException.class));
    assertEquals(defaultHandler, router.findExceptionHandler(RuntimeException.class));

    // The class itself wins over its interfaces.
    ExceptionHandler<RetryableIOException> classHandler = Mockito.mock(ExceptionHandler.class);
    router.exceptionHandler(RetryableIOException.class, classHandler);
    assertEquals(classHandler, router.findExceptionHandler(SubRetryableIOException.class));

    assertThrows(IllegalArgumentException.class,
        () -> router.interfaceExceptionHandler(RuntimeException.class, retryableHandler));
  }

}
