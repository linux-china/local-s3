package com.robothy.s3.rest.handler;

import com.robothy.netty.http.RouterHttpRequest;
import com.robothy.netty.http.RouterHttpRequestHandler;
import com.robothy.netty.router.AbstractRouter;
import com.robothy.netty.router.Route;
import com.robothy.netty.router.Router;
import com.robothy.s3.core.exception.LocalS3RequestException;
import com.robothy.s3.core.exception.S3ErrorCode;
import com.robothy.s3.rest.handler.iceberg.IcebergCatalogController;
import com.robothy.s3.rest.handler.s3tables.S3TablesController;
import com.robothy.s3.rest.handler.s3vectors.VectorResourceRequests;
import com.robothy.s3.rest.model.request.BucketRegion;
import com.robothy.s3.rest.netty.ChunkSignatures;
import com.robothy.s3.rest.netty.OperationHandler;
import com.robothy.s3.rest.netty.RequestBodies;
import com.robothy.s3.rest.netty.ReceivedRequest;
import com.robothy.s3.rest.netty.RequestHeadVerifier;
import com.robothy.s3.rest.utils.SigV4Requests;
import com.robothy.s3.rest.utils.VirtualHostParser;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;


/**
 * See {@code docs/architecture.md#the-path-of-a-request}: the most specific route wins, never the order of
 * registration, and {@linkplain #verifyRoutes()} checks that when the routes are registered.
 */
class LocalS3Router extends AbstractRouter implements RequestHeadVerifier {

  static final String BUCKET_PATH = "/{bucket}";

  static final String BUCKET_KEY_PATH = "/{bucket}/{key}";

  // An exact path, so it takes precedence over a bucket named _health; see docs/deployment.md#health-check.
  static final String HEALTH_CHECK_PATH = "/_health";

  // The ARN of a vector bucket or an index holds "/", so the decoded ARN is passed as the resourceArn parameter.
  static final String VECTOR_RESOURCE_TAGS_PATH = "/tags/{resourceArn}";

  // A key of a bucket named "tags" can start with it too, and is taken for an ARN: S3 Vectors has no other way to be
  // told apart from Amazon S3 by its path.
  static final String VECTOR_RESOURCE_TAGS_PREFIX = "/tags/arn:aws:s3vectors:";

  static final String ADMIN_OPS_PATH = "/_admin/";

  static final String AUTHENTICATION_FAILURE_OPERATION = "AuthenticationFailure";

  static final String NOT_FOUND_OPERATION = OperationHandler.NOT_FOUND_OPERATION;

  static final String AMBIGUOUS_OPERATION = "AmbiguousRequest";

  static final String SERVICE_PREFLIGHT_OPERATION = "ServiceCorsPreflight";

  private final Map<HttpMethod, Map<String, List<Route>>> rules = new HashMap<>();

  private final Map<Route, String> operations = new IdentityHashMap<>();

  private final AwsSignatureV4Verifier signatureVerifier;

  private final VirtualHostParser virtualHostParser;

  private final CorsResponseHeaders corsResponseHeaders;

  private StsController stsController;

  private SessionPolicyAuthorizer sessionPolicyAuthorizer;

  private KmsController kmsController;

  private IcebergCatalogController icebergController;

  private S3TablesController s3TablesController;

  private StaticWebsiteController websiteController;

  private ConsoleController consoleController;

  private CorsPreflightController servicePreflightController;

  LocalS3Router() {
    this(null, new VirtualHostParser(Set.of()));
  }

  LocalS3Router(String accessKeyId, String secretAccessKey) {
    this(new AwsSignatureV4Verifier(accessKeyId, secretAccessKey), new VirtualHostParser(Set.of()));
  }

  LocalS3Router(AwsSignatureV4Verifier signatureVerifier, VirtualHostParser virtualHostParser) {
    this(signatureVerifier, virtualHostParser, null);
  }

  LocalS3Router(AwsSignatureV4Verifier signatureVerifier, VirtualHostParser virtualHostParser,
                CorsResponseHeaders corsResponseHeaders) {
    this.signatureVerifier = signatureVerifier;
    this.virtualHostParser = Objects.requireNonNull(virtualHostParser);
    this.corsResponseHeaders = corsResponseHeaders;
  }

  LocalS3Router sts(StsController stsController) {
    this.stsController = Objects.requireNonNull(stsController);
    return this;
  }

  // Whether or not the router verifies signatures.
  LocalS3Router sessionPolicies(SessionPolicyAuthorizer authorizer) {
    this.sessionPolicyAuthorizer = Objects.requireNonNull(authorizer);
    return this;
  }

  LocalS3Router kms(KmsController kmsController) {
    this.kmsController = Objects.requireNonNull(kmsController);
    return this;
  }

  // null leaves the paths of the catalog to the S3 routes, i.e. to a bucket named "iceberg".
  LocalS3Router iceberg(IcebergCatalogController icebergController) {
    this.icebergController = icebergController;
    return this;
  }

  // null leaves a request signed for s3tables to the S3 routes.
  LocalS3Router s3Tables(S3TablesController controller) {
    this.s3TablesController = controller;
    return this;
  }

  // null leaves every request to the S3 API.
  LocalS3Router website(StaticWebsiteController websiteController) {
    this.websiteController = websiteController;
    return this;
  }

  // A browser can't sign, so the controller guards the console with HTTP Basic authentication instead. null leaves
  // /_admin/ui to the S3 routes, i.e. to a bucket named "_admin".
  LocalS3Router console(ConsoleController consoleController) {
    this.consoleController = consoleController;
    return this;
  }

  LocalS3Router servicePreflight(CorsPreflightController preflightController) {
    this.servicePreflightController = preflightController;
    return this;
  }

  @Override
  public Router route(Route rule) {
    return route(null, rule);
  }

  /**
   * @param operation e.g. {@code GetBucketAcl}; {@code null} to name the route by its method, path and conditions.
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

  // Named by its operation, so that the request is recorded by it.
  @Override
  public RouterHttpRequestHandler match(RouterHttpRequest request) {
    // Before everything else: the console is a path of the service, and its own controller guards it.
    if (isConsoleRequest(request)) {
      return new OperationHandler(ConsoleController.operation(request.getMethod(), trimPath(request.getPath())),
          consoleController);
    }
    if (isServicePreflight(request)) {
      return new OperationHandler(SERVICE_PREFLIGHT_OPERATION, servicePreflightController::handleWithoutBucket);
    }
    if (stsController != null && StsController.isStsRequest(request)) {
      return withCorsHeaders(request, matchSts(request));
    }
    if (kmsController != null && KmsController.isKmsRequest(request)) {
      return withCorsHeaders(request, matchKms(request));
    }
    if (icebergController != null && IcebergCatalogController.isIcebergRequest(request)) {
      return withCorsHeaders(request, matchIceberg(request));
    }
    // After the catalog, whose own paths a client may sign for s3tables too, and before the S3 routes, whose paths
    // this API shares: only the credential scope of the request tells the two apart.
    if (s3TablesController != null && S3TablesController.isS3TablesRequest(request)) {
      return withCorsHeaders(request, matchS3Tables(request));
    }
    OperationHandler handler = matchMethod(request.getMethod())
        .map(pathRules -> matchPath(pathRules, request))
        .map(rules -> matchHandler(rules, request))
        .orElseGet(() -> new OperationHandler(NOT_FOUND_OPERATION, notFoundHandler()));
    handler = asListDirectoryBuckets(request, handler);

    // A bucket that is served as a static website answers an unsigned read of a browser, which the S3 routes above
    // would answer with a listing, with an XML error, or, of a service that requires signed requests, not at all.
    BucketKey website = websiteTarget(request);
    if (website != null && websiteController.isWebsiteRequest(request, website.bucket(), website.key())) {
      return withCorsHeaders(request, new OperationHandler(StaticWebsiteController.OPERATION, websiteController));
    }

    // The form of a browser upload carries its credentials, which its controller verifies; any other request that
    // looks like a form upload, e.g. one posted to an object, is verified like every request.
    boolean authenticatedByForm = isFormUpload(request) && handler != null
        && PostObjectController.OPERATION.equals(handler.operation());
    if (requiresAuthentication(request) && !authenticatedByForm && website == null) {
      // A request whose head was verified before its body was received has only its body verified.
      AwsSignatureV4Verifier.VerificationResult result = signatureVerifier.verifyBody(request, verifiedHead(request));
      if (!result.authenticated()) {
        return new OperationHandler(AUTHENTICATION_FAILURE_OPERATION, new AuthenticationFailureHandler(result));
      }
    }
    // Recorded as the operation that was denied, like Amazon S3 reports a 403 of it.
    if (sessionPolicyAuthorizer != null && handler != null && !authenticatedByForm && website == null
        && !sessionPolicyAuthorizer.allows(request, handler.operation())) {
      handler = new OperationHandler(handler.operation(), (req, resp) -> {
        throw new LocalS3RequestException(S3ErrorCode.AccessDenied);
      });
    }
    return withCorsHeaders(request, handler);
  }

  /**
   * The handler of {@code ListDirectoryBuckets}, if a request that the routes answer with {@code ListBuckets} is one.
   * Both are a {@code GET /}, and a {@code ListDirectoryBuckets} without parameters has nothing of its own but the
   * {@code s3express} service that the AWS SDKs sign it for, which a route can't declare. A {@code GET /} of a directory
   * bucket addressed by its host is routed to {@code ListObjects} before, and left as it is.
   */
  private OperationHandler asListDirectoryBuckets(RouterHttpRequest request, OperationHandler handler) {
    if (handler == null || !"ListBuckets".equals(handler.operation())
        || !"s3express".equals(SigV4Requests.signingService(request))) {
      return handler;
    }
    return operations.entrySet().stream()
        .filter(entry -> ListDirectoryBucketsController.OPERATION.equals(entry.getValue()))
        .findFirst()
        .map(entry -> new OperationHandler(entry.getValue(), entry.getKey().getHandler()))
        .orElse(handler);
  }

  // A rejected signature is answered in the error format of STS rather than of Amazon S3.
  private OperationHandler matchSts(RouterHttpRequest request) {
    if (requiresAuthentication(request)) {
      AwsSignatureV4Verifier.VerificationResult result = signatureVerifier.verify(request);
      if (!result.authenticated()) {
        return new OperationHandler(AUTHENTICATION_FAILURE_OPERATION,
            (req, resp) -> StsController.writeAuthenticationFailure(resp, result));
      }
    }
    return new OperationHandler(StsController.operation(request), stsController);
  }

  // A rejected signature is answered in the JSON error format of KMS rather than of Amazon S3.
  private OperationHandler matchKms(RouterHttpRequest request) {
    if (requiresAuthentication(request)) {
      AwsSignatureV4Verifier.VerificationResult result = signatureVerifier.verify(request);
      if (!result.authenticated()) {
        return new OperationHandler(AUTHENTICATION_FAILURE_OPERATION,
            (req, resp) -> KmsController.writeAuthenticationFailure(resp, result));
      }
    }
    return new OperationHandler(KmsController.operation(request), kmsController);
  }

  // The catalog authenticates with an OAuth2 bearer token, so only a request signed with SigV4 is verified; the S3
  // requests that the engine makes are verified either way. See docs/data-tools.md#limits.
  private OperationHandler matchIceberg(RouterHttpRequest request) {
    if (requiresAuthentication(request) && isAwsSigned(request)) {
      AwsSignatureV4Verifier.VerificationResult result = signatureVerifier.verify(request);
      if (!result.authenticated()) {
        return new OperationHandler(AUTHENTICATION_FAILURE_OPERATION, new AuthenticationFailureHandler(result));
      }
    }
    return new OperationHandler(IcebergCatalogController.operation(request), icebergController);
  }

  // A rejected signature is answered in the rest-json error format of the API rather than in XML.
  private OperationHandler matchS3Tables(RouterHttpRequest request) {
    if (requiresAuthentication(request)) {
      AwsSignatureV4Verifier.VerificationResult result = signatureVerifier.verify(request);
      if (!result.authenticated()) {
        return new OperationHandler(AUTHENTICATION_FAILURE_OPERATION, (req, resp) ->
            S3TablesController.writeError(resp, 403, "AccessDeniedException", result.message()));
      }
    }
    return new OperationHandler(S3TablesController.operation(request), s3TablesController);
  }

  /**
   * Whether a request carries an AWS Signature Version 4, rather than another kind of credential such as the bearer
   * token of the Iceberg REST protocol.
   */
  private static boolean isAwsSigned(RouterHttpRequest request) {
    return request.header(HttpHeaderNames.AUTHORIZATION.toString())
        .map(authorization -> authorization.startsWith("AWS4-"))
        .orElseGet(() -> Objects.toString(request.getUri(), "").contains("X-Amz-Algorithm="));
  }

  /**
   * Verify the signature of a request before its body is received, so that a request with an invalid signature
   * doesn't get to upload its body. What was verified is the state of the accepted head, which the decoder of the
   * connection hands on with the complete request, see {@linkplain #requestReceived}, so that {@linkplain #match} only
   * verifies what depends on the body: the payload hash and the chunk signatures.
   */
  @Override
  public RequestHeadVerifier.Outcome verifyHead(RouterHttpRequest head) {
    // The credentials of a form upload are fields of its body, so it can only be verified once the body is received.
    // An STS or KMS request is small, and verified once it is received, so that a rejection is answered in the error
    // format of that service.
    if (!requiresAuthentication(head) || isFormUpload(head)
        || stsController != null && StsController.isStsRequest(head)
        || kmsController != null && KmsController.isKmsRequest(head)
        || icebergController != null && IcebergCatalogController.isIcebergRequest(head)
        || s3TablesController != null && S3TablesController.isS3TablesRequest(head)
        // An unsigned read that the static website answers, which match() dispatches without a signature.
        || websiteTarget(head) != null) {
      return null;
    }
    AwsSignatureV4Verifier.HeadVerification verification = signatureVerifier.verifyHeadForBody(head);
    AwsSignatureV4Verifier.VerificationResult result = verification.result();
    if (!result.authenticated()) {
      return new RequestHeadVerifier.Rejection(result.errorCode(), result.message());
    }
    return new RequestHeadVerifier.Accepted(verification.verifiedHead());
  }

  @Override
  public Object requestReceived(RouterHttpRequest head, RouterHttpRequest request, Object state) {
    if (!(state instanceof AwsSignatureV4Verifier.VerifiedHead verifiedHead)) {
      return null;
    }
    // An aws-chunked body that was decoded while it was received had its chunk signatures verified already.
    return RequestBodies.awsChunkedTrailer(request.getBody()).isPresent()
        ? AwsSignatureV4Verifier.VerifiedHead.COMPLETE : verifiedHead;
  }

  private static AwsSignatureV4Verifier.VerifiedHead verifiedHead(RouterHttpRequest request) {
    return ReceivedRequest.of(request)
        .map(ReceivedRequest::verification)
        .filter(AwsSignatureV4Verifier.VerifiedHead.class::isInstance)
        .map(AwsSignatureV4Verifier.VerifiedHead.class::cast)
        .orElse(null);
  }

  /**
   * Verify the chunk signatures of an {@code aws-chunked} body while it is received, with what {@linkplain #verifyHead}
   * verified. A request that isn't verified before its body is received, e.g. an STS request, is buffered as it is
   * received, and verified whole once it is.
   */
  @Override
  public ChunkSignatures chunkSignatures(RouterHttpRequest head, Object state) {
    if (!requiresAuthentication(head)) {
      return ChunkSignatures.UNVERIFIED;
    }
    return state instanceof AwsSignatureV4Verifier.VerifiedHead verifiedHead
        ? AwsSignatureV4Verifier.chunkSignatures(verifiedHead) : null;
  }

  private boolean requiresAuthentication(RouterHttpRequest request) {
    // Neither health checks nor the CORS preflight requests of browsers are signed, and the console is guarded with
    // HTTP Basic authentication instead, which is the only kind of credentials a browser can be asked for.
    return signatureVerifier != null && !isHealthCheck(request) && !isPreflight(request)
        && !isConsoleRequest(request);
  }

  /**
   * Whether a request is one of the built-in console, i.e. a request of {@linkplain ConsoleController#PATH} or of a
   * path below it, of one of {@linkplain ConsoleController#METHODS}, addressed at the service rather than at a
   * bucket.
   *
   * <p>A virtual-hosted request is never one: its path is the key of an object of the bucket of its {@code Host},
   * so {@code /_admin/ui} there asks for an object, which is read with a signature like every other object.
   */
  private boolean isConsoleRequest(RouterHttpRequest request) {
    if (consoleController == null || !ConsoleController.METHODS.contains(request.getMethod())) {
      return false;
    }
    Optional<BucketRegion> bucketRegion =
        virtualHostParser.parse(request.getHeaders().get(HttpHeaderNames.HOST.toString()));
    if (bucketRegion.isPresent() && bucketRegion.get().getBucketName().isPresent()) {
      return false;
    }
    return ConsoleController.isConsolePath(trimPath(Objects.toString(request.getPath(), "")));
  }

  /**
   * The bucket and the object key of a request that the static website answers without credentials, i.e. an unsigned
   * read of a bucket that is public or of a service that serves every bucket, see
   * {@linkplain StaticWebsiteController#servesAnonymously}.
   *
   * @param request the request.
   * @return the target of the request; {@code null} if it isn't answered anonymously, and so is verified like every
   *     request.
   */
  private BucketKey websiteTarget(RouterHttpRequest request) {
    if (websiteController == null) {
      return null;
    }
    BucketKey target = bucketAndKey(request);
    return target != null && websiteController.servesAnonymously(request, target.bucket(), target.key())
        ? target : null;
  }

  /**
   * The bucket and the object key that a request addresses, read off its path and its {@code Host} the way
   * {@linkplain #matchPath} reads them, but without touching the parameters of the request: it is read before a route
   * is matched, i.e. before the parameters are set.
   *
   * @param request the request.
   * @return the target; {@code null} if the request addresses no bucket, e.g. {@code ListBuckets}.
   */
  private BucketKey bucketAndKey(RouterHttpRequest request) {
    String path = Objects.toString(request.getPath(), "");
    String trimmedPath = trimPath(path);
    Optional<BucketRegion> bucketRegion =
        virtualHostParser.parse(request.getHeaders().get(HttpHeaderNames.HOST.toString()));
    if (bucketRegion.isPresent() && bucketRegion.get().getBucketName().isPresent()) {
      // A virtual-hosted request addresses the bucket of its Host, so its path is the object key.
      String bucket = bucketRegion.get().getBucketName().get();
      return new BucketKey(bucket, "/".equals(trimmedPath) ? "" : path.substring(1));
    }

    if (path.isEmpty() || "/".equals(trimmedPath) || trimmedPath.isEmpty()) {
      return null;
    }
    long slashCount = path.chars().filter(c -> c == '/').count();
    if (slashCount == 1 || (slashCount == 2 && path.endsWith("/"))) { // The bucket itself.
      return new BucketKey(trimmedPath.substring(1), "");
    }
    int secondSlashIdx = path.indexOf('/', 1);
    return new BucketKey(path.substring(1, secondSlashIdx), path.substring(secondSlashIdx + 1));
  }

  private record BucketKey(String bucket, String key) {
  }

  /**
   * Add the CORS headers of the bucket to the response of an actual cross-origin request, before the handler runs;
   * {@linkplain com.robothy.s3.rest.netty.LocalS3HttpMessageHandler} keeps them if the handler fails. Preflight
   * requests are answered by their own handler.
   */
  private OperationHandler withCorsHeaders(RouterHttpRequest request, OperationHandler handler) {
    if (corsResponseHeaders == null || handler == null || isPreflight(request)
        || request.header(HttpHeaderNames.ORIGIN.toString()).isEmpty()) {
      return handler;
    }
    return new OperationHandler(handler.operation(), (req, resp) -> {
      corsResponseHeaders.apply(req, resp);
      handler.handle(req, resp);
    });
  }

  /**
   * Whether a request looks like a browser form upload, {@code POST Object}: a {@code multipart/form-data} POST that
   * carries neither an {@code Authorization} header nor the signature of a presigned URL, since its credentials are
   * fields of the form.
   */
  private static boolean isFormUpload(RouterHttpRequest request) {
    return HttpMethod.POST.equals(request.getMethod())
        && request.header(HttpHeaderNames.CONTENT_TYPE.toString())
            .map(contentType -> contentType.trim().toLowerCase(Locale.ROOT).startsWith("multipart/form-data"))
            .orElse(false)
        && request.header(HttpHeaderNames.AUTHORIZATION.toString()).isEmpty()
        && !Objects.toString(request.getUri(), "").contains("X-Amz-Algorithm=");
  }

  /**
   * Whether a request is a CORS preflight that addresses no bucket, which the default CORS rule of the service answers:
   * one of the service itself, e.g. {@code OPTIONS /}, or of the Iceberg REST catalog or the S3 Tables API, whose paths
   * aren't buckets.
   */
  private boolean isServicePreflight(RouterHttpRequest request) {
    if (servicePreflightController == null || !isPreflight(request)) {
      return false;
    }
    return (icebergController != null && IcebergCatalogController.isIcebergRequest(request))
        || (s3TablesController != null && S3TablesController.isS3TablesRequest(request))
        || bucketAndKey(request) == null;
  }

  private static boolean isPreflight(RouterHttpRequest request) {
    return HttpMethod.OPTIONS.equals(request.getMethod());
  }

  private boolean isHealthCheck(RouterHttpRequest request) {
    HttpMethod method = request.getMethod();
    return (HttpMethod.GET.equals(method) || HttpMethod.HEAD.equals(method))
        && HEALTH_CHECK_PATH.equals(trimPath(request.getPath()));
  }

  Optional<Map<String, List<Route>>> matchMethod(HttpMethod method) {
    return Optional.ofNullable(this.rules.get(method));
  }

  List<Route> matchPath(Map<String, List<Route>> pathRules, RouterHttpRequest request) {
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

    if (bucketNameInPath && path.startsWith(VECTOR_RESOURCE_TAGS_PREFIX)
        && pathRules.containsKey(VECTOR_RESOURCE_TAGS_PATH)) {
      request.putParameter(VectorResourceRequests.RESOURCE_ARN_PARAMETER, List.of(path.substring("/tags/".length())));
      return pathRules.get(VECTOR_RESOURCE_TAGS_PATH);
    }

    String bucketName;
    String objectKey = null;
    if (bucketNameInPath) {
      long slashCount = path.chars().filter(c -> c == '/').count();
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

    setBucketNameAndObjectKeyToRequestParams(request, bucketName, objectKey);
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

  void setBucketNameAndObjectKeyToRequestParams(RouterHttpRequest request, String bucketName, String objectKey) {
    request.putParameter("bucket", List.of(bucketName));
    if (Objects.nonNull(objectKey)) {
      request.putParameter("key", List.of(objectKey));
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
   * @return the handler, named by its operation; {@code null} if no route matches.
   */
  OperationHandler matchHandler(List<Route> candidates, RouterHttpRequest request) {
    List<Route> best = bestRoutes(candidates, request.getHeaders(), request.getParams());
    if (best.isEmpty()) {
      return null;
    }
    if (best.size() == 1) {
      return new OperationHandler(operations.get(best.getFirst()), best.getFirst().getHandler());
    }
    String matched = String.join(", ", best.stream().map(operations::get).toList());
    return new OperationHandler(AMBIGUOUS_OPERATION, (req, resp) -> {
      throw new LocalS3RequestException(S3ErrorCode.InvalidRequest,
          "The request matches more than one operation: " + matched + ".");
    });
  }

  /**
   * The routes that match the request with the highest priority.
   */
  private List<Route> bestRoutes(List<Route> candidates, Map<String, String> headers,
                                 Map<String, List<String>> params) {
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

  int calculatePriority(Route route, RouterHttpRequest request) {
    return calculatePriority(route, request.getHeaders(), request.getParams());
  }

  private static int calculatePriority(Route route, Map<String, String> headers,
                                       Map<String, List<String>> params) {

    int priority = 0;

    if (Objects.isNull(route.getHeaderMatcher())) {
      priority |= (1 << 1);
    } else if (route.getHeaderMatcher().test(headers)) {
      priority |= (1 << 3);
    } else {
      priority = -1;
    }

    if (Objects.isNull(route.getParamMatcher())) {
      priority |= (1 << 2);
    } else if (route.getParamMatcher().test(params)) {
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
        Map<String, String> headers = route.getHeaderMatcher() instanceof HeaderCondition condition
            ? condition.minimalHeaders() : Map.of();
        Map<String, List<String>> params = route.getParamMatcher() instanceof ParamCondition condition
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
