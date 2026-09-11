package com.robothy.s3.rest.handler;

import com.robothy.netty.http.HttpRequest;
import com.robothy.netty.http.HttpRequestHandler;
import com.robothy.netty.router.AbstractRouter;
import com.robothy.netty.router.Route;
import com.robothy.netty.router.Router;
import com.robothy.s3.rest.model.request.BucketRegion;
import com.robothy.s3.rest.utils.VirtualHostParser;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;

class LocalS3Router extends AbstractRouter {

  static final String BUCKET_PATH = "/{bucket}";

  static final String BUCKET_KEY_PATH = "/{bucket}/{key}";

  /**
   * Path of the health check. It is answered without authentication, so that container and Kubernetes
   * probes can use it; as an exact path, it takes precedence over a bucket named {@code _health}.
   */
  static final String HEALTH_CHECK_PATH = "/_health";

  private final Map<HttpMethod, Map<String, List<Route>>> rules = new HashMap<>();

  private final AwsSignatureV4Verifier signatureVerifier;

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
    this.rules.putIfAbsent(rule.getMethod(), new HashMap<>());
    Map<String, List<Route>> pathRules = this.rules.get(rule.getMethod());
    pathRules.putIfAbsent(rule.getPath(), new ArrayList<>());
    List<Route> routes = pathRules.get(rule.getPath());
    routes.add(rule);
    return this;
  }

  @Override
  public HttpRequestHandler match(HttpRequest request) {
    // Neither health checks nor the CORS preflight requests of browsers are signed.
    if (signatureVerifier != null && !isHealthCheck(request) && !isPreflight(request)) {
      AwsSignatureV4Verifier.VerificationResult result = signatureVerifier.verify(request);
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
    if (pathRules.containsKey(trimmedPath)) {
      return pathRules.get(trimmedPath);
    }

    Map<CharSequence, List<String>> params = request.getParams();

    Optional<BucketRegion> bucketRegion = virtualHostParser.parse(request.getHeaders().get(HttpHeaderNames.HOST.toString()));
    boolean bucketNameInPath = !bucketRegion.isPresent() || !bucketRegion.get().getBucketName().isPresent();
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


  HttpRequestHandler matchHandler(List<Route> candidates, HttpRequest request) {
    HttpRequestHandler result = null;
    int priority = 0;
    for (Route candidate : candidates) {
      int currentPriority = calculatePriority(candidate, request);
      if (currentPriority >= priority) {
        priority = currentPriority;
        result = candidate.getHandler();
      }
    }
    return result;
  }

  int calculatePriority(Route route, HttpRequest request) {

    int priority = 0;

    if (Objects.isNull(route.getHeaderMatcher())) {
      priority |= (1 << 1);
    } else if (route.getHeaderMatcher().apply(request.getHeaders())) {
      priority |= (1 << 3);
    } else {
      priority = -1;
    }

    if (Objects.isNull(route.getParamMatcher())) {
      priority |= (1 << 2);
    } else if (route.getParamMatcher().apply(request.getParams())) {
      priority |= (1 << 4);
    } else {
      priority = -1;
    }

    return priority;
  }

}
