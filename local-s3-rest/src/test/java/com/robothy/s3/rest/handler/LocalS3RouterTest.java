package com.robothy.s3.rest.handler;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import com.robothy.netty.http.HttpRequest;
import com.robothy.netty.http.HttpRequestHandler;
import com.robothy.netty.router.Route;
import com.robothy.s3.core.exception.LocalS3RequestException;
import com.robothy.s3.core.exception.S3ErrorCode;
import com.robothy.s3.rest.netty.OperationHandler;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LocalS3RouterTest {

  @Test
  void match() throws IllegalAccessException {
    LocalS3Router localS3Router = new LocalS3Router();

    HttpRequestHandler handler1 = mock(HttpRequestHandler.class, "handler1");
    localS3Router.route(Route.builder()
        .method(HttpMethod.GET).path("/a")
        .paramMatcher(params -> params.containsKey("versioning"))
        .handler(handler1)
        .build());

    HttpRequestHandler handler2 = mock(HttpRequestHandler.class, "handler2");
    localS3Router.route(Route.builder()
        .method(HttpMethod.GET).path("/a")
        .paramMatcher(params -> params.containsKey("versioning"))
        .headerMatcher(headers -> headers.containsKey("x-header"))
        .handler(handler2)
        .build());

    HttpRequestHandler matchedHandler1 = localS3Router.match(HttpRequest.builder()
        .method(HttpMethod.GET).path("/a")
        .params(Map.of("versioning", List.of("true")))
        .build());
    assertSame(handler1, ((OperationHandler) matchedHandler1).handler());

    HttpRequestHandler matchedHandler2 = localS3Router.match(HttpRequest.builder()
        .method(HttpMethod.GET).path("/a")
        .params(Map.of("versioning", List.of("true")))
        .headers(Map.of("x-header", "value"))
        .build());
    assertSame(handler2, ((OperationHandler) matchedHandler2).handler());
  }

  @Test
  void matchPath() {
    LocalS3Router localS3Router = new LocalS3Router();
    Map<String, List<Route>> rules = new HashMap<>();

    List<Route> pathRule1 = mock(List.class, "pathRule1");
    List<Route> objectPathRule = mock(List.class, "objectPathRule");
    List<Route> bucketPathRule = mock(List.class, "bucketPathRule");
    rules.put("/a/b", pathRule1);
    rules.put(LocalS3Router.BUCKET_KEY_PATH, objectPathRule);
    rules.put(LocalS3Router.BUCKET_PATH, bucketPathRule);

    assertSame(pathRule1, localS3Router.matchPath(rules, HttpRequest.builder().path("/a/b/").build()));
    assertSame(pathRule1, localS3Router.matchPath(rules, HttpRequest.builder().path("/a/b").build()));

    HttpRequest bucketOperation1 = HttpRequest.builder().path("/a").build();
    assertSame(bucketPathRule, localS3Router.matchPath(rules, bucketOperation1));
    assertEquals("a", bucketOperation1.parameter("bucket").get());
    assertTrue(bucketOperation1.parameter("key").isEmpty());

    HttpRequest bucketOperation2 = HttpRequest.builder().path("/a/").build();
    assertSame(bucketPathRule, localS3Router.matchPath(rules, bucketOperation2));
    assertEquals("a", bucketOperation2.parameter("bucket").get());
    assertTrue(bucketOperation2.parameter("key").isEmpty());

    List<Route> listBucketsRule = mock(List.class, "listBucketsRule");
    rules.put("/", listBucketsRule);
    assertSame(listBucketsRule, localS3Router.matchPath(rules, HttpRequest.builder().path("/")
        .headers(new HashMap<>(Map.of(HttpHeaderNames.HOST.toString(), "localhost:29090"))).build()));

    HttpRequest bucketOperation3 = HttpRequest.builder().path("/").build();
    bucketOperation3.getHeaders().put(HttpHeaderNames.HOST.toString(), "images.example.com.s3.us-east-1.amazonaws.com");
    assertSame(bucketPathRule, localS3Router.matchPath(rules, bucketOperation3));
    assertEquals("images.example.com", bucketOperation3.parameter("bucket").get());
    assertFalse(bucketOperation3.parameter("key").isPresent());

    HttpRequest objectOperation1 = HttpRequest.builder().path("/a/key").build();
    assertSame(objectPathRule, localS3Router.matchPath(rules, objectOperation1));
    assertEquals("a", objectOperation1.parameter("bucket").get());
    assertEquals("key", objectOperation1.parameter("key").get());

    HttpRequest objectOperation2 = HttpRequest.builder().path("/a/key/").build();
    assertSame(objectPathRule, localS3Router.matchPath(rules, objectOperation2));
    assertEquals("a", objectOperation2.parameter("bucket").get());
    assertEquals("key/", objectOperation2.parameter("key").get());

    HttpRequest objectOperation3 = HttpRequest.builder().path("/a/dir/a.txt").build();
    assertSame(objectPathRule, localS3Router.matchPath(rules, objectOperation3));
    assertEquals("a", objectOperation3.parameter("bucket").get());
    assertEquals("dir/a.txt", objectOperation3.parameter("key").get());

    HttpRequest objectOperation4 = HttpRequest.builder().path("/a/dir/sub-dir/").build();
    assertSame(objectPathRule, localS3Router.matchPath(rules, objectOperation4));
    assertEquals("a", objectOperation4.parameter("bucket").get());
    assertEquals("dir/sub-dir/", objectOperation4.parameter("key").get());

    HttpRequest objectOperation5 = HttpRequest.builder().path("/a/dir/sub-dir/")
        .headers(Map.of(HttpHeaderNames.HOST.toString(), "bucket1.s3.localhost")).build();
    assertSame(objectPathRule, localS3Router.matchPath(rules, objectOperation5));
    assertEquals("bucket1.s3", objectOperation5.parameter("bucket").get());
    assertEquals("a/dir/sub-dir/", objectOperation5.parameter("key").get());

    // A virtual-hosted request whose key happens to be a path of the service is an object operation.
    HttpRequest objectOperation6 = HttpRequest.builder().path("/a/b")
        .headers(Map.of(HttpHeaderNames.HOST.toString(), "bucket1.localhost")).build();
    assertSame(objectPathRule, localS3Router.matchPath(rules, objectOperation6));
    assertEquals("bucket1", objectOperation6.parameter("bucket").get());
    assertEquals("a/b", objectOperation6.parameter("key").get());
  }

  @Test
  void virtualHostedHealthCheckKeepsItsPath() {
    LocalS3Router localS3Router = new LocalS3Router();
    Map<String, List<Route>> rules = new HashMap<>();
    List<Route> healthCheckRule = mock(List.class, "healthCheckRule");
    rules.put(LocalS3Router.HEALTH_CHECK_PATH, healthCheckRule);
    rules.put(LocalS3Router.BUCKET_KEY_PATH, mock(List.class, "objectPathRule"));

    assertSame(healthCheckRule, localS3Router.matchPath(rules, HttpRequest.builder().path("/_health")
        .headers(Map.of(HttpHeaderNames.HOST.toString(), "bucket1.localhost")).build()));
  }

  @Test
  void trimPath() {
    LocalS3Router localS3Router = new LocalS3Router();
    localS3Router.trimPath("/").equals("");
    localS3Router.trimPath("/a").equals("/a");
    localS3Router.trimPath("/a/").equals("/a");
    localS3Router.trimPath("/a/b").equals("/a/b");
    localS3Router.trimPath("/a/b/ ").equals("/a/b");
  }


  @Test
  void verifyRoutesRejectsRoutesWithTheSameConditions() {
    LocalS3Router router = new LocalS3Router();
    router.route("GetBucketLifecycle", route(HttpMethod.GET, ParamCondition.has("lifecycle"), null));
    router.route("GetBucketLifecycleConfiguration", route(HttpMethod.GET, ParamCondition.has("lifecycle"), null));

    IllegalStateException thrown = assertThrows(IllegalStateException.class, router::verifyRoutes);
    assertTrue(thrown.getMessage().contains("GetBucketLifecycle and GetBucketLifecycleConfiguration"),
        thrown.getMessage());
  }

  /**
   * {@code GetBucketAnalyticsConfiguration} and {@code ListBucketAnalyticsConfigurations} differ by the {@code id}
   * of the configuration, which conditions on the subresource alone don't tell apart.
   */
  @Test
  void verifyRoutesAcceptsRoutesThatAParameterTellsApart() {
    LocalS3Router ambiguous = new LocalS3Router();
    ambiguous.route("GetBucketAnalyticsConfiguration", route(HttpMethod.GET, ParamCondition.has("analytics"), null));
    ambiguous.route("ListBucketAnalyticsConfigurations", route(HttpMethod.GET, ParamCondition.has("analytics"), null));
    assertThrows(IllegalStateException.class, ambiguous::verifyRoutes);

    LocalS3Router router = new LocalS3Router();
    HttpRequestHandler get = mock(HttpRequestHandler.class, "get");
    HttpRequestHandler list = mock(HttpRequestHandler.class, "list");
    router.route("GetBucketAnalyticsConfiguration", Route.builder().method(HttpMethod.GET).path("/a")
        .paramMatcher(ParamCondition.has("analytics", "id")).handler(get).build());
    router.route("ListBucketAnalyticsConfigurations", Route.builder().method(HttpMethod.GET).path("/a")
        .paramMatcher(ParamCondition.has("analytics").andHasNot("id")).handler(list).build());
    assertDoesNotThrow(router::verifyRoutes);

    OperationHandler matchedGet =
        (OperationHandler) router.match(request(Map.of("analytics", List.of(""), "id", List.of("1")), Map.of()));
    assertSame(get, matchedGet.handler());
    assertEquals("GetBucketAnalyticsConfiguration", matchedGet.operation(), "The handler names its operation.");
    OperationHandler matchedList = (OperationHandler) router.match(request(Map.of("analytics", List.of("")), Map.of()));
    assertSame(list, matchedList.handler());
    assertEquals("ListBucketAnalyticsConfigurations", matchedList.operation());
  }

  @Test
  void verifyRoutesRejectsARouteThatAnotherOneShadows() {
    LocalS3Router router = new LocalS3Router();
    router.route("Broad", route(HttpMethod.GET, ParamCondition.has("a"), null));
    router.route("Narrow", route(HttpMethod.GET, ParamCondition.has("a"), HeaderCondition.hasNot("x-header")));

    IllegalStateException thrown = assertThrows(IllegalStateException.class, router::verifyRoutes);
    assertTrue(thrown.getMessage().contains("Broad of GET /a is shadowed by Narrow"), thrown.getMessage());
  }

  @Test
  void verifyRoutesRejectsConditionsThatCantBeChecked() {
    LocalS3Router router = new LocalS3Router();
    router.route("Declared", route(HttpMethod.GET, ParamCondition.has("a"), null));
    router.route("Coded", Route.builder().method(HttpMethod.GET).path("/a")
        .paramMatcher(params -> params.containsKey("b")).handler(mock(HttpRequestHandler.class)).build());

    IllegalStateException thrown = assertThrows(IllegalStateException.class, router::verifyRoutes);
    assertTrue(thrown.getMessage().contains("Coded has a condition that can't be checked"), thrown.getMessage());
  }

  /**
   * A request that matches several routes equally, e.g. one that combines two subresources, is rejected, whichever
   * order the routes were registered in.
   */
  @Test
  void aRequestThatMatchesSeveralRoutesEquallyIsRejected() {
    for (boolean aclFirst : new boolean[] {true, false}) {
      LocalS3Router router = new LocalS3Router();
      Route acl = route(HttpMethod.GET, ParamCondition.has("acl"), null);
      Route tagging = route(HttpMethod.GET, ParamCondition.has("tagging"), null);
      if (aclFirst) {
        router.route("GetBucketAcl", acl).route("GetBucketTagging", tagging);
      } else {
        router.route("GetBucketTagging", tagging).route("GetBucketAcl", acl);
      }
      assertDoesNotThrow(router::verifyRoutes);
      assertSame(acl.getHandler(),
          ((OperationHandler) router.match(request(Map.of("acl", List.of("")), Map.of()))).handler());

      HttpRequestHandler handler = router.match(request(Map.of("acl", List.of(""), "tagging", List.of("")), Map.of()));
      assertEquals(LocalS3Router.AMBIGUOUS_OPERATION, ((OperationHandler) handler).operation());
      LocalS3RequestException thrown = assertThrows(LocalS3RequestException.class,
          () -> handler.handle(null, null));
      assertEquals(S3ErrorCode.InvalidRequest, thrown.getS3ErrorCode());
      assertTrue(thrown.getMessage().contains("GetBucketAcl") && thrown.getMessage().contains("GetBucketTagging"),
          thrown.getMessage());
    }
  }

  @Test
  void conditionsMatchTheRequestsTheyDeclare() {
    ParamCondition listV2 = ParamCondition.equalTo("list-type", "2");
    assertTrue(listV2.apply(Map.of("list-type", List.of("2"))));
    assertFalse(listV2.apply(Map.of("list-type", List.of("1"))));
    assertFalse(listV2.apply(Map.of("list-type", List.of())));
    assertFalse(listV2.apply(Map.of()));
    assertThrows(IllegalArgumentException.class, () -> ParamCondition.has("id").andHasNot("id"));

    HeaderCondition copy = HeaderCondition.has("X-Amz-Copy-Source");
    assertTrue(copy.apply(Map.of("x-amz-copy-source", "/b/k")));
    assertTrue(copy.apply(Map.of("X-AMZ-COPY-SOURCE", "/b/k")), "Header names are compared ignoring case.");
    assertFalse(copy.apply(Map.of()));
    assertTrue(HeaderCondition.hasNot("x-amz-object-attributes").apply(Map.of()));
  }

  private static Route route(HttpMethod method, ParamCondition params, HeaderCondition headers) {
    Route.Builder builder = Route.builder().method(method).path("/a").handler(mock(HttpRequestHandler.class));
    if (params != null) {
      builder.paramMatcher(params);
    }
    if (headers != null) {
      builder.headerMatcher(headers);
    }
    return builder.build();
  }

  private static HttpRequest request(Map<CharSequence, List<String>> params, Map<CharSequence, String> headers) {
    return HttpRequest.builder().method(HttpMethod.GET).path("/a")
        .params(new HashMap<>(params)).headers(new HashMap<>(headers)).build();
  }

}
