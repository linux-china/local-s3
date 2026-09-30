package com.robothy.netty.router;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.robothy.netty.http.HttpRequestHandler;
import io.netty.handler.codec.http.HttpMethod;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class RouteTest {

  private static final HttpRequestHandler HANDLER = (request, response) -> { };

  @Test
  @SuppressWarnings("removal")
  void deprecatedParamMatcherDoesNotModifyTheRoute() {
    Route route = Route.builder().method(HttpMethod.GET).path("/a/{b}").handler(HANDLER).build();
    Set<Route> routes = new HashSet<>(Set.of(route));
    int hashCode = route.hashCode();

    Predicate<Map<String, List<String>>> params = ps -> ps.containsKey("x");
    Route withParams = route.paramMatcher(params);

    assertNull(route.getParamMatcher());
    assertEquals(hashCode, route.hashCode());
    assertTrue(routes.contains(route), "The route can still be found in a hash set after the calls.");

    assertNotSame(route, withParams);
    assertSame(params, withParams.getParamMatcher());
    assertEquals(route.getTrimPath(), withParams.getTrimPath());
    assertSame(HANDLER, withParams.getHandler());
  }

  @Test
  void builderDoesNotAffectBuiltRoutes() {
    Route.Builder builder = Route.builder().method(HttpMethod.GET).path("/a").handler(HANDLER);
    Route route = builder.build();
    builder.path("/b").paramMatcher(ps -> true);
    assertEquals("/a", route.getPath());
    assertNull(route.getParamMatcher());
  }

  @Test
  void trimPathIgnoresEmptySegments() {
    for (String path : List.of("/a/b", "/a//b", "/a/b/", "//a/b")) {
      assertEquals("/a/b", Route.builder().method(HttpMethod.GET).path(path).handler(HANDLER).build().getTrimPath());
    }
    assertEquals("/a/{}", Route.builder().method(HttpMethod.GET).path("/a//{b}/").handler(HANDLER).build().getTrimPath());
    assertEquals("", Route.builder().method(HttpMethod.GET).path("/").handler(HANDLER).build().getTrimPath());
  }

}
