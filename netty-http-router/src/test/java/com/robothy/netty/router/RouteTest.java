package com.robothy.netty.router;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import com.robothy.netty.http.RouterHttpRequestHandler;
import io.netty.handler.codec.http.HttpMethod;
import java.util.List;
import org.junit.jupiter.api.Test;

class RouteTest {

  private static final RouterHttpRequestHandler HANDLER = (request, response) -> { };

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
