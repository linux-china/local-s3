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
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class RouteTest {

  private static final HttpRequestHandler HANDLER = (request, response) -> { };

  @Test
  @SuppressWarnings("removal")
  void deprecatedMatcherMethodsDoNotModifyTheRoute() {
    Route route = Route.builder().method(HttpMethod.GET).path("/a/{b}").handler(HANDLER).build();
    Set<Route> routes = new HashSet<>(Set.of(route));
    int hashCode = route.hashCode();

    Function<Map<CharSequence, List<String>>, Boolean> params = ps -> ps.containsKey("x");
    Function<Map<String, String>, Boolean> headers = hs -> hs.containsKey("y");
    Route withParams = route.paramMatcher(params);
    Route withHeaders = route.headerMather(headers);

    assertNull(route.getParamMatcher());
    assertNull(route.getHeaderMatcher());
    assertEquals(hashCode, route.hashCode());
    assertTrue(routes.contains(route), "The route can still be found in a hash set after the calls.");

    assertNotSame(route, withParams);
    assertSame(params, withParams.getParamMatcher());
    assertEquals(route.getTrimPath(), withParams.getTrimPath());
    assertSame(headers, withHeaders.getHeaderMatcher());
    assertSame(HANDLER, withHeaders.getHandler());
  }

  @Test
  void builderDoesNotAffectBuiltRoutes() {
    Route.Builder builder = Route.builder().method(HttpMethod.GET).path("/a").handler(HANDLER);
    Route route = builder.build();
    builder.path("/b").paramMatcher(ps -> true);
    assertEquals("/a", route.getPath());
    assertNull(route.getParamMatcher());
  }

}
