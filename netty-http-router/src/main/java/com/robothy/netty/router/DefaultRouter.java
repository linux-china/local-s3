package com.robothy.netty.router;

import com.robothy.netty.http.RouterHttpRequest;
import com.robothy.netty.http.RouterHttpRequestHandler;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * A {@link Router} backed by a dictionary tree of path segments.
 *
 * <p>The tree and the route set are plain {@link HashMap}/{@link HashSet} instances without synchronization, so
 * {@link #route(Route)} must only be called before the server starts. Concurrent {@link #match(RouterHttpRequest)} calls
 * are safe once registration is finished and the router has been safely published to the I/O threads (e.g. by
 * starting the server after registration).
 */
final class DefaultRouter extends AbstractRouter {

  private final Set<Route> ruleSet = new HashSet<>();

  private final TreeNode root = new TreeNode();

  /**
   * Serves the static resources to the requests that no route matches; {@code null} while they are off.
   */
  private StaticResourceMatcher staticResourceMatcher;

  private boolean headFallbackToGet;

  private boolean methodNotAllowed;

  @Override
  public Router staticResource(String rootPath) {
    this.staticResourceMatcher = StaticResourceMatcher.create(rootPath);
    return this;
  }

  @Override
  public Router headFallbackToGet(boolean enabled) {
    this.headFallbackToGet = enabled;
    return this;
  }

  @Override
  public Router methodNotAllowed(boolean enabled) {
    this.methodNotAllowed = enabled;
    return this;
  }

  @Override
  public Router route(Route route) {
    Set<String> variableNames = new HashSet<>();
    for (int i = 0; i < route.segmentCount(); i++) {
      String name = route.variableName(i);
      if (name == null) {
        continue;
      }
      if (name.isEmpty()) {
        throw new IllegalArgumentException("The path variable name cannot be empty.");
      }
      if (!variableNames.add(name)) {
        throw new IllegalArgumentException("Duplicate path variable '" + name + "' in " + route.getPath() + ".");
      }
    }

    if (ruleSet.contains(route)) {
      throw new IllegalArgumentException("The router already has a handler for route " + route);
    }
    ruleSet.add(route);

    TreeNode node = addNode(root, route.getMethod().name());
    for (int i = 0; i < route.segmentCount(); i++) {
      node = route.variableName(i) == null ? addNode(node, route.segment(i)) : addLikeNode(node);
    }
    node.addRoute(route);
    return this;
  }

  private TreeNode addNode(TreeNode parent, String path) {
    return parent.exactChildren.computeIfAbsent(path, k -> new TreeNode());
  }

  private TreeNode addLikeNode(TreeNode parent) {
    if (parent.likeChild == null) {
      parent.likeChild = new TreeNode();
    }
    return parent.likeChild;
  }


  @Override
  public RouterHttpRequestHandler match(RouterHttpRequest request) {
    request.setPathVariables(Map.of());
    // A request target that is not in the origin-form, e.g. "*", matches no route.
    String[] segments = request.getPath() == null || !request.getPath().startsWith("/")
        ? null : Route.splitPath(request.getPath());
    // Read-only views created once for all candidate routes.
    Map<String, String> headers = request.getHeaders();
    Map<String, List<String>> params = request.getParams();
    RouterHttpRequestHandler handler;
    if (null != (handler = matchHandler(request, segments, headers, params, request.getMethod()))) {
      return handler;
    }
    // The body of the GET response is dropped by the codec, which pairs the response with the HEAD request.
    if (headFallbackToGet && HttpMethod.HEAD.equals(request.getMethod())
        && null != (handler = matchHandler(request, segments, headers, params, HttpMethod.GET))) {
      return handler;
    }
    if (staticResourceMatcher != null && null != (handler = staticResourceMatcher.match(request))) {
      return handler;
    }
    if (methodNotAllowed && null != (handler = methodNotAllowedHandler(segments, headers, params))) {
      return handler;
    }
    return super.notFoundHandler();
  }

  /**
   * Answer with {@code 405 Method Not Allowed} if routes of other methods match the request.
   *
   * @return the handler; or {@code null} if no route of any method matches.
   */
  private RouterHttpRequestHandler methodNotAllowedHandler(String[] segments, Map<String, String> headers,
                                                           Map<String, List<String>> params) {
    if (segments == null) {
      return null;
    }
    Set<String> allowed = new TreeSet<>();
    root.exactChildren.forEach((method, node) -> {
      if (matchRoute(node, segments, 0, headers, params) != null) {
        allowed.add(method);
      }
    });
    if (headFallbackToGet && allowed.contains(HttpMethod.GET.name())) {
      allowed.add(HttpMethod.HEAD.name());
    }
    if (allowed.isEmpty()) {
      return null;
    }
    String allow = String.join(", ", allowed);
    return (req, response) -> response.status(HttpResponseStatus.METHOD_NOT_ALLOWED)
        .putHeader(HttpHeaderNames.ALLOW.toString(), allow)
        .putHeader(HttpHeaderNames.CONTENT_TYPE.toString(), "text/plain; charset=utf-8")
        .write("Netty HTTP Router: 405 Method Not Allowed.");
  }

  private RouterHttpRequestHandler matchHandler(RouterHttpRequest request, String[] segments,
                                                Map<String, String> headers, Map<String, List<String>> params,
                                                HttpMethod method) {
    if (segments == null) {
      return null;
    }
    TreeNode node = root.exactChildren.get(method.name());
    if (node == null) {
      return null;
    }

    Route result = matchRoute(node, segments, 0, headers, params);
    if (result == null) {
      return null;
    }

    request.setPathVariables(parsePathVariables(result, segments));
    return result.getHandler();
  }

  private Map<String, String> parsePathVariables(Route route, String[] pathSegments) {
    if (pathSegments.length != route.segmentCount()) {
      throw new IllegalArgumentException("'" + String.join("/", pathSegments) + "' should not match '" + route.getPath() + "'.");
    }

    Map<String, String> result = new HashMap<>();
    for (int i = 0; i < pathSegments.length; i++) {
      String name = route.variableName(i);
      if (name != null) {
        result.put(name, pathSegments[i]);
      }
    }
    return Map.copyOf(result);
  }

  /**
   * Find the route matching {@code segments[idx..]} under {@code node} with depth-first search. The exact child is
   * tried before the path variable child, and the search backtracks to the path variable child if the exact branch
   * has no matched route.
   */
  private Route matchRoute(TreeNode node, String[] segments, int idx, Map<String, String> headers,
                           Map<String, List<String>> params) {
    if (idx == segments.length) {
      for (Route route : node.routes) {
        boolean headerMatched = (route.getHeaderMatcher() == null || route.getHeaderMatcher().test(headers));
        boolean paramMatched = (route.getParamMatcher() == null || route.getParamMatcher().test(params));
        if (headerMatched && paramMatched) {
          return route;
        }
      }
      return null;
    }

    TreeNode exactChild = node.exactChildren.get(segments[idx]);
    if (exactChild != null) {
      Route route = matchRoute(exactChild, segments, idx + 1, headers, params);
      if (route != null) {
        return route;
      }
    }
    return node.likeChild == null ? null : matchRoute(node.likeChild, segments, idx + 1, headers, params);
  }

  /**
   * A dictionary tree node.
   */
  private static class TreeNode {

    private final Map<String, TreeNode> exactChildren = new HashMap<>();

    private TreeNode likeChild;

    /**
     * Routes with the same method and path, ordered by {@link #score(Route)} from high to low. Routes with the same
     * score keep their registration order.
     */
    private final List<Route> routes = new ArrayList<>();

    private void addRoute(Route route) {
      int score = score(route);
      int idx = 0;
      while (idx < routes.size() && score(routes.get(idx)) >= score) {
        idx++;
      }
      routes.add(idx, route);
    }

    /**
     * Header matcher has higher priority than param matcher.
     */
    private static int score(Route route) {
      int score = 0;
      if (Objects.nonNull(route.getHeaderMatcher())) {
        score |= (1 << 1);
      }
      if (Objects.nonNull(route.getParamMatcher())) {
        score |= 1;
      }
      return score;
    }
  }

}
