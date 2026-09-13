package com.robothy.s3.rest.handler;

import com.robothy.netty.http.HttpRequest;
import com.robothy.netty.http.HttpRequestHandler;
import com.robothy.netty.router.AbstractRouter;
import com.robothy.netty.router.Route;
import com.robothy.netty.router.Router;
import com.robothy.s3.core.exception.LocalS3RequestException;
import com.robothy.s3.core.exception.S3ErrorCode;
import com.robothy.s3.rest.model.request.BucketRegion;
import com.robothy.s3.rest.netty.RequestHeadVerifier;
import com.robothy.s3.rest.utils.VirtualHostParser;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.WeakHashMap;

import org.apache.commons.lang3.StringUtils;

/**
 * Routes the requests of LocalS3 to their handlers.
 *
 * <p>The routes of a method and a path are told apart by their conditions on the parameters and headers of a request.
 * Of the routes that match a request, the one whose conditions are the most specific wins: a matched condition on the
 * parameters counts more than a matched condition on the headers, and a matched condition more than none. The winner
 * never depends on the order the routes were registered in. {@linkplain #verifyRoutes()} checks, once the routes are
 * registered, that the smallest request that satisfies the conditions of every route is won by that route alone;
 * a request that still matches several routes equally, e.g. one that combines two subresources such as
 * {@code ?acl&tagging}, is answered with {@code InvalidRequest}.
 */
class LocalS3Router extends AbstractRouter implements RequestHeadVerifier {

  static final String BUCKET_PATH = "/{bucket}";

  static final String BUCKET_KEY_PATH = "/{bucket}/{key}";

  /**
   * Path of the health check. It is answered without authentication, so that container and Kubernetes
   * probes can use it; as an exact path, it takes precedence over a bucket named {@code _health}.
   */
  static final String HEALTH_CHECK_PATH = "/_health";

  private final Map<HttpMethod, Map<String, List<Route>>> rules = new HashMap<>();

  /**
   * The operation that each route answers, which the problems of the routes are reported by.
   */
  private final Map<Route, String> operations = new IdentityHashMap<>();

  private final AwsSignatureV4Verifier signatureVerifier;

  /**
   * The verified heads of the requests whose bodies are being received, by the head that the decoder verified.
   * Weak, and compared by identity, since {@linkplain HttpRequest} doesn't override {@code equals}: an entry is dropped
   * with a request that is abandoned, e.g. by a closed connection, and no request of a client can reach another's.
   */
  private final Map<HttpRequest, AwsSignatureV4Verifier.VerifiedHead> headsBeingReceived =
      Collections.synchronizedMap(new WeakHashMap<>());

  /**
   * The verified heads of the requests whose bodies are received, by the complete request, which
   * {@linkplain #match} verifies only the body of.
   */
  private final Map<HttpRequest, AwsSignatureV4Verifier.VerifiedHead> receivedRequests =
      Collections.synchronizedMap(new WeakHashMap<>());

  private final VirtualHostParser virtualHostParser;

  private final CorsResponseHeaders corsResponseHeaders;

  LocalS3Router() {
    this(null, new VirtualHostParser(Set.of()));
  }

  LocalS3Router(String accessKeyId, String secretAccessKey) {
    this(new AwsSignatureV4Verifier(accessKeyId, secretAccessKey), new VirtualHostParser(Set.of()));
  }

  /**
   * Create a router.
   *
   * @param signatureVerifier verifies request signatures; {@code null} to accept all requests.
   * @param virtualHostParser parses the bucket of virtual-hosted-style requests.
   */
  LocalS3Router(AwsSignatureV4Verifier signatureVerifier, VirtualHostParser virtualHostParser) {
    this(signatureVerifier, virtualHostParser, null);
  }

  /**
   * Create a router.
   *
   * @param signatureVerifier verifies request signatures; {@code null} to accept all requests.
   * @param virtualHostParser parses the bucket of virtual-hosted-style requests.
   * @param corsResponseHeaders adds CORS headers to the responses of cross-origin requests; {@code null} to add none.
   */
  LocalS3Router(AwsSignatureV4Verifier signatureVerifier, VirtualHostParser virtualHostParser,
                CorsResponseHeaders corsResponseHeaders) {
    this.signatureVerifier = signatureVerifier;
    this.virtualHostParser = Objects.requireNonNull(virtualHostParser);
    this.corsResponseHeaders = corsResponseHeaders;
  }

  @Override
  public Router route(Route rule) {
    return route(null, rule);
  }

  /**
   * Register a route of an operation.
   *
   * @param operation the name of the operation that the route answers, e.g. {@code GetBucketAcl}; {@code null} to name
   *     the route by its method, path and conditions.
   * @param rule the route.
   * @return this router.
   */
  LocalS3Router route(String operation, Route rule) {
    operations.put(rule, Objects.requireNonNullElseGet(operation,
        () -> rule.getMethod() + " " + rule.getPath() + " " + conditions(rule)));
    this.rules.putIfAbsent(rule.getMethod(), new HashMap<>());
    Map<String, List<Route>> pathRules = this.rules.get(rule.getMethod());
    pathRules.putIfAbsent(rule.getPath(), new ArrayList<>());
    List<Route> routes = pathRules.get(rule.getPath());
    routes.add(rule);
    return this;
  }

  @Override
  public HttpRequestHandler match(HttpRequest request) {
    if (requiresAuthentication(request)) {
      // A request whose head was verified before its body was received has only its body verified.
      AwsSignatureV4Verifier.VerificationResult result =
          signatureVerifier.verifyBody(request, receivedRequests.remove(request));
      if (!result.authenticated()) {
        return new AuthenticationFailureHandler(result);
      }
    }

    HttpRequestHandler handler = matchMethod(request.getMethod())
        .map(pathRules -> matchPath(pathRules, request))
        .map(rules -> matchHandler(rules, request))
        .orElse(notFoundHandler());
    return withCorsHeaders(request, handler);
  }

  /**
   * Verify the signature of a request before its body is received, so that a request with an invalid signature
   * doesn't get to upload its body. What was verified is kept, and handed to the complete request by
   * {@linkplain #requestReceived}, so that {@linkplain #match} only verifies what depends on the body: the payload hash
   * and the chunk signatures.
   */
  @Override
  public RequestHeadVerifier.Rejection verifyHead(HttpRequest head) {
    if (!requiresAuthentication(head)) {
      return null;
    }
    AwsSignatureV4Verifier.HeadVerification verification = signatureVerifier.verifyHeadForBody(head);
    AwsSignatureV4Verifier.VerificationResult result = verification.result();
    if (!result.authenticated()) {
      return new RequestHeadVerifier.Rejection(result.errorCode(), result.message());
    }
    headsBeingReceived.put(head, verification.verifiedHead());
    return null;
  }

  @Override
  public void requestReceived(HttpRequest head, HttpRequest request) {
    AwsSignatureV4Verifier.VerifiedHead verifiedHead = headsBeingReceived.remove(head);
    if (verifiedHead != null) {
      receivedRequests.put(request, verifiedHead);
    }
  }

  private boolean requiresAuthentication(HttpRequest request) {
    // Neither health checks nor the CORS preflight requests of browsers are signed.
    return signatureVerifier != null && !isHealthCheck(request) && !isPreflight(request);
  }

  /**
   * Add the CORS headers of the bucket to the response of an actual cross-origin request, before the handler runs;
   * {@linkplain com.robothy.s3.rest.netty.LocalS3HttpMessageHandler} keeps them if the handler fails. Preflight
   * requests are answered by their own handler.
   */
  private HttpRequestHandler withCorsHeaders(HttpRequest request, HttpRequestHandler handler) {
    if (corsResponseHeaders == null || handler == null || isPreflight(request)
        || request.header(HttpHeaderNames.ORIGIN.toString()).isEmpty()) {
      return handler;
    }
    return (req, resp) -> {
      corsResponseHeaders.apply(req, resp);
      handler.handle(req, resp);
    };
  }

  private static boolean isPreflight(HttpRequest request) {
    return HttpMethod.OPTIONS.equals(request.getMethod());
  }

  private boolean isHealthCheck(HttpRequest request) {
    HttpMethod method = request.getMethod();
    return (HttpMethod.GET.equals(method) || HttpMethod.HEAD.equals(method))
        && HEALTH_CHECK_PATH.equals(trimPath(request.getPath()));
  }

  Optional<Map<String, List<Route>>> matchMethod(HttpMethod method) {
    return Optional.ofNullable(this.rules.get(method));
  }

  List<Route> matchPath(Map<String, List<Route>> pathRules, HttpRequest request) {
    String path = request.getPath();
    String trimmedPath = trimPath(path);
    Optional<BucketRegion> bucketRegion = virtualHostParser.parse(request.getHeaders().get(HttpHeaderNames.HOST.toString()));
    boolean bucketNameInPath = !bucketRegion.isPresent() || !bucketRegion.get().getBucketName().isPresent();
    // A virtual-hosted request addresses the bucket of its Host, so its path is the bucket ("/") or an object key
    // rather than a path of the service, e.g. "/" is ListObjects there instead of ListBuckets. The health check is
    // the exception: it is answered without authentication wherever it is sent, see requiresAuthentication().
    boolean exactPathApplies = bucketNameInPath || HEALTH_CHECK_PATH.equals(trimmedPath);
    if (exactPathApplies && pathRules.containsKey(trimmedPath)) {
      return pathRules.get(trimmedPath);
    }

    Map<CharSequence, List<String>> params = request.getParams();

    String bucketName;
    String objectKey = null;
    if (bucketNameInPath) {
      int slashCount = StringUtils.countMatches(path, '/');
      if (slashCount == 1 || (slashCount == 2 && path.endsWith("/"))) { // bucket operation.
        bucketName = trimmedPath.substring(1);
      } else { // object operation.
        int secondSlashIdx = path.indexOf('/', 1);
        bucketName = path.substring(1, secondSlashIdx);
        objectKey = path.substring(secondSlashIdx + 1);
      }
    } else {
      bucketName = bucketRegion.get().getBucketName().get();
      if (!"/".equals(trimmedPath)) {
        objectKey = path.substring(1);
      }
    }

    setBucketNameAndObjectKeyToRequestParams(params, bucketName, objectKey);
    boolean isBucketOperation = Objects.isNull(objectKey);
    return getCandidateHandlers(pathRules, isBucketOperation);
  }

  String trimPath(String path) {
    if ("/".equals(path)) {
      return path;
    }

    path = path.trim();
    if (path.endsWith("/")) {
      path = path.substring(0, path.length() - 1);
    }
    return path;
  }

  void setBucketNameAndObjectKeyToRequestParams(Map<CharSequence, List<String>> params, String bucketName, String objectKey) {
    params.put("bucket", List.of(bucketName));
    if (Objects.nonNull(objectKey)) {
      params.put("key", List.of(objectKey));
    }
  }

  List<Route> getCandidateHandlers(Map<String, List<Route>> pathRules, boolean bucketOperation) {
    if (bucketOperation) {
      return pathRules.get(BUCKET_PATH);
    }

    return pathRules.get(BUCKET_KEY_PATH);
  }


  /**
   * The handler of the route that matches a request best. If several routes match it equally, the request is answered
   * with {@code InvalidRequest}, rather than by whichever of them was registered last.
   *
   * @return the handler; {@code null} if no route matches.
   */
  HttpRequestHandler matchHandler(List<Route> candidates, HttpRequest request) {
    List<Route> best = bestRoutes(candidates, request.getHeaders(), request.getParams());
    if (best.isEmpty()) {
      return null;
    }
    if (best.size() == 1) {
      return best.get(0).getHandler();
    }
    String matched = String.join(", ", best.stream().map(operations::get).toList());
    return (req, resp) -> {
      throw new LocalS3RequestException(S3ErrorCode.InvalidRequest,
          "The request matches more than one operation: " + matched + ".");
    };
  }

  /**
   * The routes that match the request with the highest priority.
   */
  private List<Route> bestRoutes(List<Route> candidates, Map<CharSequence, String> headers,
                                 Map<CharSequence, List<String>> params) {
    List<Route> best = new ArrayList<>();
    int bestPriority = -1;
    for (Route candidate : candidates) {
      int priority = calculatePriority(candidate, headers, params);
      if (priority < 0 || priority < bestPriority) {
        continue;
      }
      if (priority > bestPriority) {
        bestPriority = priority;
        best.clear();
      }
      best.add(candidate);
    }
    return best;
  }

  int calculatePriority(Route route, HttpRequest request) {
    return calculatePriority(route, request.getHeaders(), request.getParams());
  }

  private static int calculatePriority(Route route, Map<CharSequence, String> headers,
                                       Map<CharSequence, List<String>> params) {

    int priority = 0;

    if (Objects.isNull(route.getHeaderMatcher())) {
      priority |= (1 << 1);
    } else if (route.getHeaderMatcher().apply(headers)) {
      priority |= (1 << 3);
    } else {
      priority = -1;
    }

    if (Objects.isNull(route.getParamMatcher())) {
      priority |= (1 << 2);
    } else if (route.getParamMatcher().apply(params)) {
      priority |= (1 << 4);
    } else {
      priority = -1;
    }

    return priority;
  }

  /**
   * The registered routes by the operations they answer, e.g. to compare the operations of the router with the ones
   * that the documentation lists.
   *
   * @return the routes, ordered by operation.
   */
  Map<String, Route> routesByOperation() {
    Map<String, Route> routes = new TreeMap<>();
    operations.forEach((route, operation) -> routes.put(operation, route));
    return routes;
  }

  /**
   * Check that no route depends on the order the routes were registered in: for every route, the smallest request that
   * satisfies its conditions must be won by that route alone. Two routes that win such a request equally, e.g. two
   * routes with the same conditions, are ambiguous; a route that another one wins such a request from is shadowed, and
   * would never answer the requests it is meant for.
   *
   * <p>The conditions of the routes must be declared as {@linkplain ParamCondition} and {@linkplain HeaderCondition},
   * which tell the requests they match, rather than as functions, which don't.
   *
   * @throws IllegalStateException if a route is ambiguous, shadowed, or has a condition that can't be checked.
   */
  void verifyRoutes() {
    List<String> problems = new ArrayList<>();
    rules.forEach((method, pathRules) -> pathRules.forEach((path, routes) -> {
      if (routes.size() < 2) {
        return;
      }
      for (int i = 0; i < routes.size(); i++) {
        Route route = routes.get(i);
        if (!isDeclarative(route)) {
          problems.add(operations.get(route) + " has a condition that can't be checked; declare it with "
              + "ParamCondition or HeaderCondition.");
          continue;
        }
        Map<CharSequence, String> headers = route.getHeaderMatcher() instanceof HeaderCondition condition
            ? condition.minimalHeaders() : Map.of();
        Map<CharSequence, List<String>> params = route.getParamMatcher() instanceof ParamCondition condition
            ? condition.minimalParams() : Map.of();
        int own = calculatePriority(route, headers, params);
        for (int j = 0; j < routes.size(); j++) {
          Route other = routes.get(j);
          if (i == j || !isDeclarative(other)) {
            continue;
          }
          int priority = calculatePriority(other, headers, params);
          if (priority == own && i < j) {
            problems.add(operations.get(route) + " and " + operations.get(other) + " of " + method + " " + path
                + " both match " + conditions(route) + " equally.");
          } else if (priority > own) {
            problems.add(operations.get(route) + " of " + method + " " + path + " is shadowed by "
                + operations.get(other) + ", which wins " + conditions(route) + ".");
          }
        }
      }
    }));
    if (!problems.isEmpty()) {
      throw new IllegalStateException("The routes of LocalS3 are ambiguous:\n  " + String.join("\n  ", problems));
    }
  }

  private static boolean isDeclarative(Route route) {
    return (route.getHeaderMatcher() == null || route.getHeaderMatcher() instanceof HeaderCondition)
        && (route.getParamMatcher() == null || route.getParamMatcher() instanceof ParamCondition);
  }

  private static String conditions(Route route) {
    String params = route.getParamMatcher() == null ? "" : String.valueOf(route.getParamMatcher());
    String headers = route.getHeaderMatcher() == null ? "" : " [" + route.getHeaderMatcher() + "]";
    String conditions = (params + headers).strip();
    return conditions.isEmpty() ? "(no condition)" : conditions;
  }

}
