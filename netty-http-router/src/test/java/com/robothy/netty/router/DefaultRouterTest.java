package com.robothy.netty.router;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.robothy.netty.http.RouterHttpRequest;
import com.robothy.netty.http.RouterHttpRequestHandler;
import com.robothy.netty.http.RouterHttpResponse;
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
import java.nio.file.attribute.FileTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

class DefaultRouterTest {

  private static final RouterHttpRequestHandler NOT_FOUND = (request, response) -> { };

  @Test
  void match() throws Exception {
    DefaultRouter router = new DefaultRouter();
    RouterHttpRequestHandler listHandler = Mockito.mock(RouterHttpRequestHandler.class);
    assertThrows(IllegalArgumentException.class, () -> router.route(HttpMethod.GET, "", listHandler));
    assertThrows(IllegalArgumentException.class, () -> router.route(HttpMethod.GET, "/a/{}", listHandler));
    router.route(Route.builder().method(HttpMethod.GET).path("/list").handler(listHandler).build());
    RouterHttpRequest.RouterHttpRequestBuilder requestBuilder = RouterHttpRequest.builder();
    RouterHttpRequest listRequest = requestBuilder
        .method(HttpMethod.GET)
        .uri("/list?id=1")
        .path("/list")
        .params(new HashMap<>())
        .headers(new HashMap<>())
        .build();
    assertEquals(listHandler, router.match(listRequest));

    RouterHttpRequestHandler notFoundHandler = Mockito.mock(RouterHttpRequestHandler.class);
    assertEquals(DefaultRouter.DEFAULT_NOT_FOUND_HANDLER, router.match(requestBuilder.path("/list/a").build()));
    router.notFound(notFoundHandler);
    assertEquals(notFoundHandler, router.match(requestBuilder.path("/").build()));
    assertEquals(notFoundHandler, router.match(requestBuilder.method(HttpMethod.PUT).path("/list").build()));
    assertEquals(notFoundHandler, router.match(requestBuilder.method(HttpMethod.PUT).path("/list/").build()));

    RouterHttpRequestHandler actionHandler = Mockito.mock(RouterHttpRequestHandler.class);
    router.route(HttpMethod.GET, "/{action}", actionHandler);
    assertEquals(notFoundHandler, router.match(requestBuilder.path("/").build()));
    RouterHttpRequest actionRequest = requestBuilder.method(HttpMethod.GET).path("/read").build();
    assertEquals(actionHandler, router.match(actionRequest));
    assertEquals("read", actionRequest.pathVariable("action").orElseThrow());
    assertEquals(listHandler, router.match(requestBuilder.path("/list").build()));

    RouterHttpRequestHandler emptyPathHandler = Mockito.mock(RouterHttpRequestHandler.class);
    router.route(HttpMethod.POST, "//", emptyPathHandler);
    assertEquals(emptyPathHandler, router.match(requestBuilder.method(HttpMethod.POST).path("/").build()));

    RouterHttpRequestHandler postContentHandler = Mockito.mock(RouterHttpRequestHandler.class);
    RouterHttpRequestHandler postAHandler = Mockito.mock(RouterHttpRequestHandler.class);
    router.route(HttpMethod.POST, "/{id}/content", postContentHandler);
    router.route(HttpMethod.POST, "/a/content", postAHandler);
    RouterHttpRequest postContentRequest = requestBuilder.method(HttpMethod.POST).path("/123/content").build();
    assertEquals(postContentHandler, router.match(postContentRequest));
    assertEquals(postAHandler, router.match(requestBuilder.path("/a/content").build()));

    RouterHttpRequestHandler paramRequestHandler = Mockito.mock(RouterHttpRequestHandler.class);
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

    RouterHttpRequestHandler headerRequestHandler = Mockito.mock(RouterHttpRequestHandler.class);
    router.route(Route.builder().method(HttpMethod.HEAD).path("/a/content")
            .headerMatcher(hs -> hs.containsKey("hello"))
            .handler(headerRequestHandler)
            .build());
    assertEquals(paramRequestHandler, router.match(requestBuilder.method(HttpMethod.HEAD).path("/a/content").build()));
    Map<String, String> headers = new HashMap<>();
    headers.put("hello", "");
    // The header matcher has higher priority.
    assertEquals(headerRequestHandler, router.match(requestBuilder.headers(headers).build()));

    RouterHttpRequestHandler headerParamHandler = Mockito.mock(RouterHttpRequestHandler.class);
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
    assertSame(NOT_FOUND, router.match(RouterHttpRequest.builder()
        .method(HttpMethod.POST)
        .path("/test/java/com/robothy/netty/router/DefaultRouterTest.java").build()));
  }

  @Test
  void classpathStaticResourceRootIsTrimmed() {
    for (String root : List.of("classpath:static", "classpath:/static", "classpath:static/", "classpath://static//")) {
      Router router = new DefaultRouter().notFound(NOT_FOUND).staticResource(root);
      assertNotSame(NOT_FOUND, router.match(getRequest("/test.html")), root);
    }
    // The classpath root itself.
    for (String root : List.of("classpath:", "classpath:/")) {
      Router router = new DefaultRouter().notFound(NOT_FOUND).staticResource(root);
      assertNotSame(NOT_FOUND, router.match(getRequest("/static/test.html")), root);
    }
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
      public RouterHttpRequestHandler match(RouterHttpRequest request) {
        return notFoundHandler();
      }
    };
    assertThrows(UnsupportedOperationException.class, () -> router.staticResource("classpath:static"));
  }

  @Test
  void staticResourceContentType(@TempDir Path directory) throws Exception {
    Router router = new DefaultRouter().staticResource("classpath:static");
    RouterHttpRequest classpathRequest = getRequest("/test.html");
    RouterHttpResponse classpathResponse = new RouterHttpResponse();
    router.match(classpathRequest).handle(classpathRequest, classpathResponse);
    assertEquals("text/html; charset=utf-8", classpathResponse.getHeaders().get("content-type"));
    readBody(classpathResponse);

    Files.writeString(directory.resolve("data.no-such-extension"), "data");
    router.staticResource(directory.toString());
    RouterHttpRequest fileRequest = getRequest("/data.no-such-extension");
    RouterHttpResponse fileResponse = new RouterHttpResponse();
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
    RouterHttpRequest post = RouterHttpRequest.builder().method(HttpMethod.POST).uri("/test.html").path("/test.html").build();
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
      RouterHttpRequest request = getRequest("/a.txt");
      RouterHttpResponse response = new RouterHttpResponse();
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

    RouterHttpRequest inside = getRequest("/inside.txt");
    RouterHttpResponse response = new RouterHttpResponse();
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
      RouterHttpRequest request = getRequest("/" + name);
      RouterHttpResponse response = new RouterHttpResponse();
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
    RouterHttpRequest request = getRequest("/data.bin");
    RouterHttpResponse response = new RouterHttpResponse();
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
  private static byte[] readBody(RouterHttpResponse response) throws Exception {
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
      RouterHttpResponse.closeQuietly(input);
    }
  }

  @Test
  void matchRoutesWithSamePriority() {
    DefaultRouter router = new DefaultRouter();
    RouterHttpRequestHandler aclHandler = Mockito.mock(RouterHttpRequestHandler.class);
    RouterHttpRequestHandler taggingHandler = Mockito.mock(RouterHttpRequestHandler.class);
    RouterHttpRequestHandler jsonHandler = Mockito.mock(RouterHttpRequestHandler.class);
    RouterHttpRequestHandler xmlHandler = Mockito.mock(RouterHttpRequestHandler.class);
    RouterHttpRequestHandler defaultHandler = Mockito.mock(RouterHttpRequestHandler.class);
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
    RouterHttpRequestHandler exactHandler = Mockito.mock(RouterHttpRequestHandler.class);
    RouterHttpRequestHandler variableHandler = Mockito.mock(RouterHttpRequestHandler.class);
    router.route(HttpMethod.GET, "/a/b/c", exactHandler)
        .route(HttpMethod.GET, "/{x}/b/d", variableHandler);

    assertEquals(exactHandler, router.match(getRequest("/a/b/c")));
    // The exact branch "/a/b" has no child "d", backtrack to "/{x}/b/d".
    RouterHttpRequest request = getRequest("/a/b/d");
    assertEquals(variableHandler, router.match(request));
    assertEquals(Map.of("x", "a"), request.getPathVariables());

    DefaultRouter paramRouter = new DefaultRouter();
    RouterHttpRequestHandler paramHandler = Mockito.mock(RouterHttpRequestHandler.class);
    paramRouter.route(Route.builder().method(HttpMethod.GET).path("/a")
            .paramMatcher(params -> params.containsKey("z")).handler(paramHandler).build())
        .route(HttpMethod.GET, "/{x}", variableHandler);
    // The exact route "/a" has priority when it matches.
    RouterHttpRequest paramRequest = getRequest("/a");
    paramRequest.putParameter("z", List.of(""));
    assertEquals(paramHandler, paramRouter.match(paramRequest));
    // The exact route "/a" does not match the params, backtrack to "/{x}".
    assertEquals(variableHandler, paramRouter.match(getRequest("/a")));
  }

  @Test
  void matchNoRouteForTargetNotInOriginForm() {
    DefaultRouter router = new DefaultRouter();
    RouterHttpRequestHandler handler = Mockito.mock(RouterHttpRequestHandler.class);
    router.route(HttpMethod.GET, "/{x}", handler);
    assertEquals(router.notFoundHandler(), router.match(getRequest("*")));
    assertEquals(router.notFoundHandler(), router.match(getRequest("http://host/a")));
  }

  @Test
  void pathVariablesAreSeparatedFromQueryParameters() {
    DefaultRouter router = new DefaultRouter();
    RouterHttpRequestHandler userHandler = Mockito.mock(RouterHttpRequestHandler.class);
    router.route(HttpMethod.GET, "/user/{id}", userHandler);

    RouterHttpRequest request = getRequest("/user/123");
    request.putParameter("id", List.of("x"));
    assertEquals(userHandler, router.match(request));
    assertEquals("123", request.pathVariable("id").orElseThrow());
    // The query parameter of the same name is not changed.
    assertEquals(Map.of("id", List.of("x")), request.getParams());

    // No path variables are left from a previous match if no route matches.
    RouterHttpRequest notFoundRequest = getRequest("/order/123");
    notFoundRequest.setPathVariables(Map.of("id", "stale"));
    assertEquals(DefaultRouter.DEFAULT_NOT_FOUND_HANDLER, router.match(notFoundRequest));
    assertTrue(notFoundRequest.getPathVariables().isEmpty());
  }

  @Test
  void rejectRoutesThatOnlyDifferInEmptySegments() {
    DefaultRouter router = new DefaultRouter();
    RouterHttpRequestHandler handler = Mockito.mock(RouterHttpRequestHandler.class);
    router.route(HttpMethod.GET, "/a/b", handler);
    assertThrows(IllegalArgumentException.class, () -> router.route(HttpMethod.GET, "/a//b", handler));
    assertThrows(IllegalArgumentException.class, () -> router.route(HttpMethod.GET, "/a/b/", handler));
    assertThrows(IllegalArgumentException.class, () -> router.route(HttpMethod.GET, "//a/b", handler));
  }

  @Test
  void rejectInvalidPathVariables() {
    DefaultRouter router = new DefaultRouter();
    RouterHttpRequestHandler handler = Mockito.mock(RouterHttpRequestHandler.class);
    assertThrows(IllegalArgumentException.class, () -> router.route(HttpMethod.GET, "/{id}/a/{id}", handler));
    assertThrows(IllegalArgumentException.class, () -> router.route(HttpMethod.GET, "/b/{}", handler));
    // The rejected routes are not registered, so they can be registered again once fixed.
    router.route(HttpMethod.GET, "/{id}/a/{name}", handler);
    router.route(HttpMethod.GET, "/b/{name}", handler);
  }

  private static RouterHttpRequest request(HttpMethod method, String path) {
    return RouterHttpRequest.builder().method(method).uri(path).path(path).build();
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
    RouterHttpRequestHandler get = (request, response) -> response.write("get");
    RouterHttpRequestHandler head = (request, response) -> { };
    Router router = new DefaultRouter().notFound(NOT_FOUND).headFallbackToGet(true)
        .route(HttpMethod.GET, "/user/{id}", get)
        .route(HttpMethod.GET, "/file", get)
        .route(HttpMethod.HEAD, "/file", head);

    RouterHttpRequest request = request(HttpMethod.HEAD, "/user/1");
    assertSame(get, router.match(request));
    assertEquals("1", request.pathVariable("id").orElse(null));
    // A HEAD route takes precedence.
    assertSame(head, router.match(request(HttpMethod.HEAD, "/file")));
    // Only HEAD falls back.
    assertSame(NOT_FOUND, router.match(request(HttpMethod.POST, "/user/1")));
  }

  @Test
  void methodNotAllowed(@TempDir Path directory) throws Exception {
    RouterHttpRequestHandler handler = (request, response) -> { };
    Router router = new DefaultRouter().notFound(NOT_FOUND).methodNotAllowed(true)
        .route(HttpMethod.GET, "/user/{id}", handler)
        .route(HttpMethod.DELETE, "/user/{id}", handler)
        .route(HttpMethod.POST, "/upload", handler);

    RouterHttpRequest post = request(HttpMethod.POST, "/user/1");
    RouterHttpResponse response = new RouterHttpResponse();
    router.match(post).handle(post, response);
    assertEquals(HttpResponseStatus.METHOD_NOT_ALLOWED, response.getStatus());
    assertEquals("DELETE, GET", response.getHeaders().get("allow"));
    response.getBody().release();

    // A path that no route of any method matches is not found.
    assertSame(NOT_FOUND, router.match(request(HttpMethod.POST, "/user/1/profile")));

    // HEAD is allowed along with GET when it falls back to it.
    router.headFallbackToGet(true);
    response = new RouterHttpResponse();
    router.match(post).handle(post, response);
    assertEquals("DELETE, GET, HEAD", response.getHeaders().get("allow"));
    response.getBody().release();

    // A static resource is served before the request is answered with 405.
    Files.writeString(directory.resolve("upload"), "file");
    router.staticResource(directory.toString());
    RouterHttpRequest get = getRequest("/upload");
    response = new RouterHttpResponse();
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
      public RouterHttpRequestHandler match(RouterHttpRequest request) {
        return notFoundHandler();
      }
    };
    assertThrows(UnsupportedOperationException.class, () -> router.headFallbackToGet(true));
    assertThrows(UnsupportedOperationException.class, () -> router.methodNotAllowed(true));
  }

  @Test
  void staticResourceConditionalRequests(@TempDir Path directory) throws Exception {
    Path file = Files.writeString(directory.resolve("app.js"), "app");
    Files.setLastModifiedTime(file, FileTime.fromMillis(1_700_000_000_123L));
    Router router = new DefaultRouter().staticResource(directory.toString());

    RouterHttpRequest request = getRequest("/app.js");
    RouterHttpResponse response = new RouterHttpResponse();
    router.match(request).handle(request, response);
    assertEquals(HttpResponseStatus.OK, response.getStatus());
    String lastModified = response.getHeaders().get("last-modified");
    String etag = response.getHeaders().get("etag");
    assertEquals("Tue, 14 Nov 2023 22:13:20 GMT", lastModified);
    assertTrue(etag.startsWith("W/\""), etag);
    assertEquals("no-cache", response.getHeaders().get("cache-control"));
    readBody(response);

    for (Map<String, String> headers : List.of(
        Map.of("if-none-match", etag),
        Map.of("if-none-match", "\"other\", " + etag.substring(2)),
        Map.of("if-none-match", "*"),
        Map.of("if-modified-since", lastModified),
        Map.of("if-modified-since", "Wed, 15 Nov 2023 00:00:00 GMT"))) {
      for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.HEAD)) {
        RouterHttpRequest conditional = request(method, "/app.js", headers);
        RouterHttpResponse notModified = new RouterHttpResponse();
        router.match(conditional).handle(conditional, notModified);
        assertEquals(HttpResponseStatus.NOT_MODIFIED, notModified.getStatus(), headers + " " + method);
        assertEquals(etag, notModified.getHeaders().get("etag"));
        assertEquals(null, notModified.getChunkedBody());
      }
    }

    for (Map<String, String> headers : List.of(
        Map.of("if-none-match", "W/\"other\""),
        // If-Modified-Since is ignored along with If-None-Match.
        Map.of("if-none-match", "W/\"other\"", "if-modified-since", lastModified),
        Map.of("if-modified-since", "Tue, 14 Nov 2023 22:13:19 GMT"),
        Map.of("if-modified-since", "not a date"))) {
      RouterHttpRequest conditional = request(HttpMethod.GET, "/app.js", headers);
      RouterHttpResponse modified = new RouterHttpResponse();
      router.match(conditional).handle(conditional, modified);
      assertEquals(HttpResponseStatus.OK, modified.getStatus(), headers.toString());
      assertEquals("app", new String(readBody(modified), StandardCharsets.UTF_8));
    }

    // A changed file has another ETag.
    Files.writeString(file, "app2");
    RouterHttpRequest changed = request(HttpMethod.GET, "/app.js", Map.of("if-none-match", etag));
    RouterHttpResponse changedResponse = new RouterHttpResponse();
    router.match(changed).handle(changed, changedResponse);
    assertEquals(HttpResponseStatus.OK, changedResponse.getStatus());
    assertEquals("app2", new String(readBody(changedResponse), StandardCharsets.UTF_8));
  }

  @Test
  void classpathResourceConditionalRequests(@TempDir Path directory) throws Exception {
    Path jar = directory.resolve("static.jar");
    try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
      JarEntry entry = new JarEntry("cond-static/a.txt");
      entry.setTime(1_700_000_000_000L);
      out.putNextEntry(entry);
      out.write("a".getBytes(StandardCharsets.UTF_8));
      out.closeEntry();
    }
    Thread thread = Thread.currentThread();
    ClassLoader contextClassLoader = thread.getContextClassLoader();
    try (URLClassLoader classLoader = new URLClassLoader(new URL[] {jar.toUri().toURL()}, null)) {
      thread.setContextClassLoader(classLoader);
      Router router = new DefaultRouter().staticResource("classpath:cond-static");
      thread.setContextClassLoader(contextClassLoader);
      RouterHttpRequest request = getRequest("/a.txt");
      RouterHttpResponse response = new RouterHttpResponse();
      router.match(request).handle(request, response);
      assertEquals("a", new String(readBody(response), StandardCharsets.UTF_8));
      String etag = response.getHeaders().get("etag");
      assertNotNull(response.getHeaders().get("last-modified"));

      RouterHttpRequest conditional = request(HttpMethod.GET, "/a.txt", Map.of("if-none-match", etag));
      RouterHttpResponse notModified = new RouterHttpResponse();
      router.match(conditional).handle(conditional, notModified);
      assertEquals(HttpResponseStatus.NOT_MODIFIED, notModified.getStatus());
      assertEquals(null, notModified.getChunkedBody());
    } finally {
      thread.setContextClassLoader(contextClassLoader);
    }
  }

  @Test
  void directoryIndex(@TempDir Path directory) throws Exception {
    Files.createDirectories(directory.resolve("docs/empty"));
    Files.writeString(directory.resolve("docs/index.html"), "docs");
    Router router = new DefaultRouter().notFound(NOT_FOUND).staticResource(directory.toString());

    RouterHttpRequest request = getRequest("/docs/");
    RouterHttpResponse response = new RouterHttpResponse();
    router.match(request).handle(request, response);
    assertEquals("docs", new String(readBody(response), StandardCharsets.UTF_8));
    assertEquals("text/html; charset=utf-8", response.getHeaders().get("content-type"));

    RouterHttpRequest withoutSlash = RouterHttpRequest.builder().method(HttpMethod.GET).uri("/docs?a=1").path("/docs").build();
    RouterHttpResponse redirect = new RouterHttpResponse();
    router.match(withoutSlash).handle(withoutSlash, redirect);
    assertEquals(HttpResponseStatus.MOVED_PERMANENTLY, redirect.getStatus());
    assertEquals("/docs/?a=1", redirect.getHeaders().get("location"));

    // A directory without an index is not found.
    assertSame(NOT_FOUND, router.match(getRequest("/docs/empty/")));

    // On the classpath: the test resources have static/test.html only, and "static" has no index.
    Router classpathRouter = new DefaultRouter().notFound(NOT_FOUND).staticResource("classpath:");
    assertSame(NOT_FOUND, classpathRouter.match(getRequest("/static/")));
  }

  @Test
  void classpathResourceNotFoundIsCached() {
    AtomicInteger lookups = new AtomicInteger();
    ClassLoader counting = new ClassLoader(getClass().getClassLoader()) {
      @Override
      public URL getResource(String name) {
        lookups.incrementAndGet();
        return super.getResource(name);
      }
    };
    Thread thread = Thread.currentThread();
    ClassLoader contextClassLoader = thread.getContextClassLoader();
    Router router;
    try {
      thread.setContextClassLoader(counting);
      router = new DefaultRouter().notFound(NOT_FOUND).staticResource("classpath:static");
    } finally {
      thread.setContextClassLoader(contextClassLoader);
    }
    lookups.set(0);
    assertSame(NOT_FOUND, router.match(getRequest("/favicon.ico")));
    int firstLookups = lookups.get();
    assertTrue(firstLookups > 0);
    assertSame(NOT_FOUND, router.match(getRequest("/favicon.ico")));
    assertSame(NOT_FOUND, router.match(getRequest("//favicon.ico")));
    assertEquals(firstLookups, lookups.get());
    // A found resource is still looked up.
    assertNotSame(NOT_FOUND, router.match(getRequest("/test.html")));
  }

  @Test
  void classpathDirectoryIndexInJar(@TempDir Path directory) throws Exception {
    Path jar = directory.resolve("static.jar");
    try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
      // No entry for the directory itself.
      out.putNextEntry(new JarEntry("index-static/docs/index.html"));
      out.write("docs".getBytes(StandardCharsets.UTF_8));
      out.closeEntry();
    }
    Thread thread = Thread.currentThread();
    ClassLoader contextClassLoader = thread.getContextClassLoader();
    try (URLClassLoader classLoader = new URLClassLoader(new URL[] {jar.toUri().toURL()}, null)) {
      thread.setContextClassLoader(classLoader);
      Router router = new DefaultRouter().notFound(NOT_FOUND).staticResource("classpath:index-static");
      thread.setContextClassLoader(contextClassLoader);
      RouterHttpRequest request = getRequest("/docs/");
      RouterHttpResponse response = new RouterHttpResponse();
      router.match(request).handle(request, response);
      assertEquals("docs", new String(readBody(response), StandardCharsets.UTF_8));

      RouterHttpRequest withoutSlash = getRequest("/docs");
      RouterHttpResponse redirect = new RouterHttpResponse();
      router.match(withoutSlash).handle(withoutSlash, redirect);
      assertEquals(HttpResponseStatus.MOVED_PERMANENTLY, redirect.getStatus());
      assertEquals("/docs/", redirect.getHeaders().get("location"));
    } finally {
      thread.setContextClassLoader(contextClassLoader);
    }
  }

  private static RouterHttpRequest request(HttpMethod method, String path, Map<String, String> headers) {
    return RouterHttpRequest.builder()
        .method(method)
        .uri(path)
        .path(path)
        .params(new HashMap<>())
        .headers(new HashMap<>(headers))
        .build();
  }

  private static RouterHttpRequest getRequest(String path) {
    return RouterHttpRequest.builder()
        .method(HttpMethod.GET)
        .uri(path)
        .path(path)
        .params(new HashMap<>())
        .headers(new HashMap<>())
        .build();
  }

  private static RouterHttpRequest bucketRequest(Map<String, List<String>> params, Map<String, String> headers) {
    return RouterHttpRequest.builder()
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

  @Test
  void notFoundRejectsNull() {
    assertThrows(NullPointerException.class, () -> Router.router().notFound(null));
  }

  @Test
  void defaultNotFoundHandlerSetsContentType() throws Exception {
    RouterHttpResponse response = new RouterHttpResponse();
    Router.DEFAULT_NOT_FOUND_HANDLER.handle(null, response);
    assertEquals(HttpResponseStatus.NOT_FOUND, response.getStatus());
    assertEquals("text/plain; charset=utf-8", response.getHeaders().get("content-type"));
  }

}
