package com.robothy.netty.router;

import com.robothy.netty.http.HttpRequest;
import com.robothy.netty.http.HttpRequestHandler;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A {@link Router} backed by a dictionary tree of path segments.
 *
 * <p>The tree and the route set are plain {@link HashMap}/{@link HashSet} instances without synchronization, so
 * {@link #route(Route)} must only be called before the server starts. Concurrent {@link #match(HttpRequest)} calls
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

  @Override
  public Router staticResource(String rootPath) {
    this.staticResourceMatcher = StaticResourceMatcher.create(rootPath);
    return this;
  }

  @Override
  public Router route(Route route) {
    String[] segments = Route.splitPath(route.getPath());
    Set<String> variableNames = new HashSet<>();
    for (String segment : segments) {
      if (!isPathVariable(segment)) {
        continue;
      }
      String name = pathVariableName(segment);
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
    for (String segment : segments) {
      node = addNode(node, segment);
    }
    node.addRoute(route);
    return this;
  }

  private TreeNode addNode(TreeNode parent, String path) {
    TreeNode child = new TreeNode();
    if (isPathVariable(path)) {
      if (parent.likeChild == null) {
        parent.likeChild = child;
      }
      return parent.likeChild;
    } else {
      if (!parent.exactChildren.containsKey(path)) {
        parent.exactChildren.put(path, child);
      }
      return parent.exactChildren.get(path);
    }
  }


  @Override
  public HttpRequestHandler match(HttpRequest request) {
    HttpRequestHandler handler;
    if (null != (handler = matchHandler(request))) {
      return handler;
    }
    if (staticResourceMatcher != null && null != (handler = staticResourceMatcher.match(request))) {
      return handler;
    }
    return super.notFoundHandler();
  }

  private HttpRequestHandler matchHandler(HttpRequest request) {
    request.setPathVariables(Map.of());
    // A request target that is not in the origin-form, e.g. "*", matches no route.
    if (request.getPath() == null || !request.getPath().startsWith("/")) {
      return null;
    }
    String[] segments = Route.splitPath(request.getPath());
    TreeNode node = root.exactChildren.get(request.getMethod().name());
    if (node == null) {
      return null;
    }

    Route result = matchRoute(node, segments, 0, request);
    if (result == null) {
      return null;
    }

    request.setPathVariables(parsePathVariables(result.getPath(), segments));
    return result.getHandler();
  }

  private Map<String, String> parsePathVariables(String pattern, String[] pathSegments) {
    String[] patternSegments = Route.splitPath(pattern);
    if (pathSegments.length != patternSegments.length) {
      throw new IllegalArgumentException("'" + String.join("/", pathSegments) + "' should not match '" + pattern + "'.");
    }

    Map<String, String> result = new HashMap<>();
    for (int i = 0; i < pathSegments.length; i++) {
      if (isPathVariable(patternSegments[i])) {
        result.put(pathVariableName(patternSegments[i]), pathSegments[i]);
      }
    }
    return Map.copyOf(result);
  }

  private static boolean isPathVariable(String segment) {
    return segment.startsWith("{") && segment.endsWith("}");
  }

  private static String pathVariableName(String segment) {
    return segment.substring(1, segment.length() - 1);
  }

  /**
   * Find the route matching {@code segments[idx..]} under {@code node} with depth-first search. The exact child is
   * tried before the path variable child, and the search backtracks to the path variable child if the exact branch
   * has no matched route.
   */
  private Route matchRoute(TreeNode node, String[] segments, int idx, HttpRequest request) {
    if (idx == segments.length) {
      for (Route route : node.routes) {
        boolean headerMatched = (route.getHeaderMatcher() == null || route.getHeaderMatcher().test(request.getHeaders()));
        boolean paramMatched = (route.getParamMatcher() == null || route.getParamMatcher().test(request.getParams()));
        if (headerMatched && paramMatched) {
          return route;
        }
      }
      return null;
    }

    TreeNode exactChild = node.exactChildren.get(segments[idx]);
    if (exactChild != null) {
      Route route = matchRoute(exactChild, segments, idx + 1, request);
      if (route != null) {
        return route;
      }
    }
    return node.likeChild == null ? null : matchRoute(node.likeChild, segments, idx + 1, request);
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
