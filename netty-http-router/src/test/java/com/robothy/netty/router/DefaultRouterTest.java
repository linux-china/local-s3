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
import io.netty.buffer.ByteBufUtil;
import io.netty.handler.codec.http.HttpMethod;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
    Map<CharSequence, List<String>> parameters = new HashMap<>();
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
    Router router = new DefaultRouter();
    HttpRequest.HttpRequestBuilder requestBuilder = HttpRequest.builder();

    /* Static resources in classpath:static */
    assertNotNull(router.match(requestBuilder.method(HttpMethod.GET)
        .path("/test.html").build()));

    /* Static resources in directory */
    router.staticResource("src");
    assertNotNull(router.match(requestBuilder
        .method(HttpMethod.GET)
        .path("/test/java/com/robothy/netty/router/RouterImplTest.java").build()));

    assertNotNull(router.match(requestBuilder
        .method(HttpMethod.POST)
        .path("/test/java/com/robothy/netty/router/RouterImplTest.java").build()));
  }

  @Test
  void staticResourceContentType(@TempDir Path directory) throws Exception {
    Router router = new DefaultRouter();
    HttpRequest classpathRequest = getRequest("/test.html");
    HttpResponse classpathResponse = new HttpResponse();
    router.match(classpathRequest).handle(classpathRequest, classpathResponse);
    assertEquals("text/html; charset=utf-8", classpathResponse.getHeaders().get("content-type"));
    classpathResponse.getBody().release();

    Files.writeString(directory.resolve("data.no-such-extension"), "data");
    router.staticResource(directory.toString());
    HttpRequest fileRequest = getRequest("/data.no-such-extension");
    HttpResponse fileResponse = new HttpResponse();
    router.match(fileRequest).handle(fileRequest, fileResponse);
    assertEquals("application/octet-stream", fileResponse.getHeaders().get("content-type"));
    fileResponse.getBody().release();
  }

  @Test
  void classpathResourcesStayUnderRoot() {
    Router router = new DefaultRouter().notFound(NOT_FOUND);
    assertNotSame(NOT_FOUND, router.match(getRequest("/test.html")));
    // The test classes are on the classpath next to "static", so "static/../com/..." resolves to one of them.
    assertSame(NOT_FOUND, router.match(getRequest("/../com/robothy/netty/router/DefaultRouterTest.class")));
    assertSame(NOT_FOUND, router.match(getRequest("/x/../../com/robothy/netty/router/DefaultRouterTest.class")));
    assertSame(NOT_FOUND, router.match(getRequest("/..\\com/robothy/netty/router/DefaultRouterTest.class")));
  }

  @Test
  void classpathResourcesAreRegularFilesServedToGet() {
    Router router = new DefaultRouter().notFound(NOT_FOUND);
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
      HttpRequest request = getRequest("/a.txt");
      HttpResponse response = new HttpResponse();
      router.match(request).handle(request, response);
      assertEquals("a", response.getBody().toString(StandardCharsets.UTF_8));
      response.getBody().release();
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
    assertEquals("inside", response.getBody().toString(StandardCharsets.UTF_8));
    response.getBody().release();

    assertSame(NOT_FOUND, router.match(getRequest("/../outside.txt")));
    assertSame(NOT_FOUND, router.match(getRequest("/sub/../../outside.txt")));
    // A directory is not found, instead of failing to be read.
    assertSame(NOT_FOUND, router.match(getRequest("/sub")));
  }

  @Test
  void staticResourceContent(@TempDir Path directory) throws Exception {
    // One file is read into the heap, the other one, over 10 MB, is mapped.
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
      assertArrayEquals(expected, ByteBufUtil.getBytes(response.getBody()));
      response.getBody().release();
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
    paramRequest.getParams().put("z", List.of(""));
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
    request.getParams().put("id", List.of("x"));
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

  private static HttpRequest getRequest(String path) {
    return HttpRequest.builder()
        .method(HttpMethod.GET)
        .uri(path)
        .path(path)
        .params(new HashMap<>())
        .headers(new HashMap<>())
        .build();
  }

  private static HttpRequest bucketRequest(Map<CharSequence, List<String>> params, Map<String, String> headers) {
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

}