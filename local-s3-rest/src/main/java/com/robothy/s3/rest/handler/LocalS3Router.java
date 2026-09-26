package com.robothy.s3.rest.handler;

import com.robothy.netty.http.HttpRequest;
import com.robothy.netty.http.HttpRequestHandler;
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
import com.robothy.s3.rest.netty.RequestHeadVerifier;
import com.robothy.s3.rest.utils.SigV4Requests;
import com.robothy.s3.rest.utils.VirtualHostParser;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.WeakHashMap;


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

  /**
   * Path of the S3 Vectors tagging operations, which address a vector bucket or an index by its ARN. The ARN holds
   * {@code /}, so a request is told to be one of them by {@linkplain #VECTOR_RESOURCE_TAGS_PREFIX}, and its decoded ARN
   * is passed as the {@code resourceArn} parameter.
   */
  static final String VECTOR_RESOURCE_TAGS_PATH = "/tags/{resourceArn}";

  /**
   * The start of the decoded path of a request of an S3 Vectors tagging operation. The path of an object of a bucket
   * named {@code tags} can start with it too; such a key is taken for an ARN, since S3 Vectors has no other way to be
   * told apart from Amazon S3 by its path.
   */
  static final String VECTOR_RESOURCE_TAGS_PREFIX = "/tags/arn:aws:s3vectors:";

  /**
   * admin ops path
   */
  static final String ADMIN_OPS_PATH = "/_admin/";

  /**
   * The operation of a request whose signature is rejected.
   */
  static final String AUTHENTICATION_FAILURE_OPERATION = "AuthenticationFailure";

  /**
   * The operation of a request that no route matches.
   */
  static final String NOT_FOUND_OPERATION = OperationHandler.NOT_FOUND_OPERATION;

  /**
   * The operation of a request that several routes match equally.
   */
  static final String AMBIGUOUS_OPERATION = "AmbiguousRequest";

  /**
   * The operation of a CORS preflight request that addresses no bucket, which the default CORS rule answers.
   */
  static final String SERVICE_PREFLIGHT_OPERATION = "ServiceCorsPreflight";

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

  /**
   * Answers the STS requests; {@code null} if the router has no STS endpoint.
   */
  private StsController stsController;

  /**
   * Authorizes the S3 requests of temporary credentials by their session policy; {@code null} if the router has no
   * STS endpoint, whose credentials alone carry one.
   */
  private SessionPolicyAuthorizer sessionPolicyAuthorizer;

  /**
   * Answers the KMS requests; {@code null} if the router has no KMS endpoint.
   */
  private KmsController kmsController;

  /**
   * Answers the Iceberg REST catalog requests; {@code null} if the service serves no catalog.
   */
  private IcebergCatalogController icebergController;

  /**
   * Answers the S3 Tables requests; {@code null} if the service serves no table buckets.
   */
  private S3TablesController s3TablesController;

  /**
   * Serves the buckets as static websites; {@code null} if the service serves none.
   */
  private StaticWebsiteController websiteController;

  /**
   * Serves the built-in console; {@code null} if the service serves none, e.g. a router of handlers alone.
   */
  private ConsoleController consoleController;

  /**
   * Answers the CORS preflight requests that address no bucket, e.g. of the Iceberg REST catalog, by the default CORS
   * rule of the service; {@code null} if the service has no default rule.
   */
  private CorsPreflightController servicePreflightController;

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

  /**
   * Answer the STS requests, see {@linkplain StsController#isStsRequest}, with a controller.
   *
   * @param stsController the controller.
   * @return this router.
   */
  LocalS3Router sts(StsController stsController) {
    this.stsController = Objects.requireNonNull(stsController);
    return this;
  }

  /**
   * Authorize the S3 requests signed with temporary credentials by the session policy of the credentials, see
   * {@linkplain SessionPolicyAuthorizer}, whether or not the router verifies signatures.
   *
   * @param authorizer the authorizer.
   * @return this router.
   */
  LocalS3Router sessionPolicies(SessionPolicyAuthorizer authorizer) {
    this.sessionPolicyAuthorizer = Objects.requireNonNull(authorizer);
    return this;
  }

  /**
   * Answer the KMS requests, see {@linkplain KmsController#isKmsRequest}, with a controller.
   *
   * @param kmsController the controller.
   * @return this router.
   */
  LocalS3Router kms(KmsController kmsController) {
    this.kmsController = Objects.requireNonNull(kmsController);
    return this;
  }

  /**
   * Answer the Iceberg REST catalog requests, see {@linkplain IcebergCatalogController#isIcebergRequest}, with a
   * controller.
   *
   * @param icebergController the controller; {@code null} to serve no catalog, which leaves its paths to the S3
   *     routes, i.e. to a bucket named {@code iceberg}.
   * @return this router.
   */
  LocalS3Router iceberg(IcebergCatalogController icebergController) {
    this.icebergController = icebergController;
    return this;
  }

  /**
   * Answer the S3 Tables requests, see {@linkplain S3TablesController#isS3TablesRequest}, with a controller.
   *
   * @param controller the controller; {@code null} to serve no table buckets, which leaves a request signed for
   *     {@code s3tables} to the S3 routes.
   * @return this router.
   */
  LocalS3Router s3Tables(S3TablesController controller) {
    this.s3TablesController = controller;
    return this;
  }

  /**
   * Serve the buckets as static websites, see {@linkplain StaticWebsiteController}: the unsigned {@code GET} and
   * {@code HEAD} requests of the buckets that allow them are answered with the semantics of a website rather than
   * those of the S3 API, and without a signature.
   *
   * @param websiteController the controller; {@code null} to serve no website, which leaves every request to the S3
   *     API.
   * @return this router.
   */
  LocalS3Router website(StaticWebsiteController websiteController) {
    this.websiteController = websiteController;
    return this;
  }

  /**
   * Serve the built-in console under {@linkplain ConsoleController#PATH}, see {@linkplain ConsoleController}. Its
   * requests come from a browser, which can't sign them, so they are guarded with HTTP Basic authentication by the
   * controller rather than verified as signatures here.
   *
   * @param consoleController the controller; {@code null} to serve no console, which leaves {@code /_admin/ui} to
   *     the S3 routes, i.e. to a bucket named {@code _admin}.
   * @return this router.
   */
  LocalS3Router console(ConsoleController consoleController) {
    this.consoleController = consoleController;
    return this;
  }

  /**
   * Answer the CORS preflight requests that address no bucket, e.g. {@code OPTIONS /} or of the Iceberg REST catalog
   * and the S3 Tables API, by the default CORS rule of the service.
   *
   * @param preflightController the controller; {@code null} to leave them to the routes, which answer no such request.
   * @return this router.
   */
  LocalS3Router servicePreflight(CorsPreflightController preflightController) {
    this.servicePreflightController = preflightController;
    return this;
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

  /**
   * The handler of a request, as an {@linkplain OperationHandler} that names the operation it answers, e.g.
   * {@code PutObject}, so that the request is recorded by its operation.
   */
  @Override
  public HttpRequestHandler match(HttpRequest request) {
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
        .orElseGet(() -> notFoundHandler() == null ? null
            : new OperationHandler(NOT_FOUND_OPERATION, notFoundHandler()));
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
      AwsSignatureV4Verifier.VerificationResult result =
          signatureVerifier.verifyBody(request, receivedRequests.remove(request));
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
  private OperationHandler asListDirectoryBuckets(HttpRequest request, OperationHandler handler) {
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

  /**
   * The handler of an STS request, which isn't addressed at a bucket whatever its host. A rejected signature is answered
   * in the error format of STS rather than the one of Amazon S3.
   */
  private OperationHandler matchSts(HttpRequest request) {
    if (requiresAuthentication(request)) {
      AwsSignatureV4Verifier.VerificationResult result = signatureVerifier.verify(request);
      if (!result.authenticated()) {
        return new OperationHandler(AUTHENTICATION_FAILURE_OPERATION,
            (req, resp) -> StsController.writeAuthenticationFailure(resp, result));
      }
    }
    return new OperationHandler(StsController.operation(request), stsController);
  }

  /**
   * The handler of a KMS request, which isn't addressed at a bucket whatever its host. A rejected signature is answered
   * in the JSON error format of KMS rather than the one of Amazon S3.
   */
  private OperationHandler matchKms(HttpRequest request) {
    if (requiresAuthentication(request)) {
      AwsSignatureV4Verifier.VerificationResult result = signatureVerifier.verify(request);
      if (!result.authenticated()) {
        return new OperationHandler(AUTHENTICATION_FAILURE_OPERATION,
            (req, resp) -> KmsController.writeAuthenticationFailure(resp, result));
      }
    }
    return new OperationHandler(KmsController.operation(request), kmsController);
  }

  /**
   * The handler of an Iceberg REST catalog request. The catalog speaks its own protocol, whose credentials are an
   * OAuth2 bearer token rather than an AWS signature, so its requests aren't verified: a client that signs them with
   * SigV4, which the Iceberg client does only when it is configured to, is verified like any other request, and one
   * that doesn't is answered as it is. The S3 requests that the engine then makes are verified either way, which is
   * where the credentials of a service actually guard something.
   */
  private OperationHandler matchIceberg(HttpRequest request) {
    if (requiresAuthentication(request) && isAwsSigned(request)) {
      AwsSignatureV4Verifier.VerificationResult result = signatureVerifier.verify(request);
      if (!result.authenticated()) {
        return new OperationHandler(AUTHENTICATION_FAILURE_OPERATION, new AuthenticationFailureHandler(result));
      }
    }
    return new OperationHandler(IcebergCatalogController.operation(request), icebergController);
  }

  /**
   * The handler of an S3 Tables request, which isn't addressed at a bucket whatever its path: its credential scope
   * says so, and the signature is then verified for that same service. A rejected signature is answered in the
   * {@code rest-json} error format of the API rather than in the XML of Amazon S3.
   */
  private OperationHandler matchS3Tables(HttpRequest request) {
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
  private static boolean isAwsSigned(HttpRequest request) {
    return request.header(HttpHeaderNames.AUTHORIZATION.toString())
        .map(authorization -> authorization.startsWith("AWS4-"))
        .orElseGet(() -> Objects.toString(request.getUri(), "").contains("X-Amz-Algorithm="));
  }

  /**
   * Verify the signature of a request before its body is received, so that a request with an invalid signature
   * doesn't get to upload its body. What was verified is kept, and handed to the complete request by
   * {@linkplain #requestReceived}, so that {@linkplain #match} only verifies what depends on the body: the payload hash
   * and the chunk signatures.
   */
  @Override
  public RequestHeadVerifier.Rejection verifyHead(HttpRequest head) {
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
    headsBeingReceived.put(head, verification.verifiedHead());
    return null;
  }

  @Override
  public void requestReceived(HttpRequest head, HttpRequest request) {
    AwsSignatureV4Verifier.VerifiedHead verifiedHead = headsBeingReceived.remove(head);
    if (verifiedHead != null) {
      // An aws-chunked body that was decoded while it was received had its chunk signatures verified already.
      receivedRequests.put(request, RequestBodies.awsChunkedTrailer(request.getBody()).isPresent()
          ? AwsSignatureV4Verifier.VerifiedHead.COMPLETE : verifiedHead);
    }
  }

  /**
   * Verify the chunk signatures of an {@code aws-chunked} body while it is received, with what {@linkplain #verifyHead}
   * verified. A request that isn't verified before its body is received, e.g. an STS request, is buffered as it is
   * received, and verified whole once it is.
   */
  @Override
  public ChunkSignatures chunkSignatures(HttpRequest head) {
    if (!requiresAuthentication(head)) {
      return ChunkSignatures.UNVERIFIED;
    }
    AwsSignatureV4Verifier.VerifiedHead verifiedHead = headsBeingReceived.get(head);
    return verifiedHead == null ? null : AwsSignatureV4Verifier.chunkSignatures(verifiedHead);
  }

  private boolean requiresAuthentication(HttpRequest request) {
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
  private boolean isConsoleRequest(HttpRequest request) {
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
  private BucketKey websiteTarget(HttpRequest request) {
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
  private BucketKey bucketAndKey(HttpRequest request) {
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

  /**
   * The bucket and the object key that a request addresses.
   *
   * @param bucket the bucket name.
   * @param key the object key; empty for a request that addresses the bucket itself.
   */
  private record BucketKey(String bucket, String key) {
  }

  /**
   * Add the CORS headers of the bucket to the response of an actual cross-origin request, before the handler runs;
   * {@linkplain com.robothy.s3.rest.netty.LocalS3HttpMessageHandler} keeps them if the handler fails. Preflight
   * requests are answered by their own handler.
   */
  private OperationHandler withCorsHeaders(HttpRequest request, OperationHandler handler) {
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
  private static boolean isFormUpload(HttpRequest request) {
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
  private boolean isServicePreflight(HttpRequest request) {
    if (servicePreflightController == null || !isPreflight(request)) {
      return false;
    }
    return (icebergController != null && IcebergCatalogController.isIcebergRequest(request))
        || (s3TablesController != null && S3TablesController.isS3TablesRequest(request))
        || bucketAndKey(request) == null;
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
    if (bucketNameInPath && path.startsWith(VECTOR_RESOURCE_TAGS_PREFIX)
        && pathRules.containsKey(VECTOR_RESOURCE_TAGS_PATH)) {
      params.put(VectorResourceRequests.RESOURCE_ARN_PARAMETER, List.of(path.substring("/tags/".length())));
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
   * @return the handler, named by its operation; {@code null} if no route matches.
   */
  OperationHandler matchHandler(List<Route> candidates, HttpRequest request) {
    List<Route> best = bestRoutes(candidates, request.getHeaders(), request.getParams());
    if (best.isEmpty()) {
      return null;
    }
    if (best.size() == 1) {
      return new OperationHandler(operations.get(best.get(0)), best.get(0).getHandler());
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
