package com.robothy.s3.rest.handler;

import static com.robothy.s3.rest.handler.LocalS3Router.BUCKET_KEY_PATH;
import static com.robothy.s3.rest.handler.LocalS3Router.BUCKET_PATH;
import static com.robothy.s3.rest.handler.LocalS3Router.HEALTH_CHECK_PATH;
import static com.robothy.s3.rest.handler.ParamCondition.equalTo;
import static com.robothy.s3.rest.handler.ParamCondition.has;
import static io.netty.handler.codec.http.HttpMethod.DELETE;
import static io.netty.handler.codec.http.HttpMethod.GET;
import static io.netty.handler.codec.http.HttpMethod.HEAD;
import static io.netty.handler.codec.http.HttpMethod.OPTIONS;
import static io.netty.handler.codec.http.HttpMethod.POST;
import static io.netty.handler.codec.http.HttpMethod.PUT;
import com.robothy.netty.http.HttpRequestHandler;
import com.robothy.netty.router.Route;
import com.robothy.netty.router.Router;
import com.robothy.s3.core.exception.LocalS3Exception;
import com.robothy.s3.core.exception.LocalS3InvalidArgumentException;
import com.robothy.s3.core.exception.vectors.LocalS3VectorException;
import com.robothy.s3.core.service.BucketService;
import com.robothy.s3.rest.admin.LocalS3Admin;
import com.robothy.s3.rest.constants.AmzHeaderNames;
import com.robothy.s3.rest.handler.s3vectors.CreateIndexController;
import com.robothy.s3.rest.handler.s3vectors.CreateVectorBucketController;
import com.robothy.s3.rest.handler.s3vectors.DeleteIndexController;
import com.robothy.s3.rest.handler.s3vectors.DeleteVectorBucketController;
import com.robothy.s3.rest.handler.s3vectors.DeleteVectorBucketPolicyController;
import com.robothy.s3.rest.handler.s3vectors.DeleteVectorsController;
import com.robothy.s3.rest.handler.s3vectors.GetIndexController;
import com.robothy.s3.rest.handler.s3vectors.GetVectorBucketController;
import com.robothy.s3.rest.handler.s3vectors.GetVectorBucketPolicyController;
import com.robothy.s3.rest.handler.s3vectors.GetVectorsController;
import com.robothy.s3.rest.handler.s3vectors.ListIndexesController;
import com.robothy.s3.rest.handler.s3vectors.ListVectorBucketsController;
import com.robothy.s3.rest.handler.s3vectors.ListVectorsController;
import com.robothy.s3.rest.handler.s3vectors.LocalS3VectorExceptionHandler;
import com.robothy.s3.rest.handler.s3vectors.PutVectorBucketPolicyController;
import com.robothy.s3.rest.handler.s3vectors.PutVectorsController;
import com.robothy.s3.rest.handler.s3vectors.QueryVectorsController;
import com.robothy.s3.rest.service.ServiceFactory;
import com.robothy.s3.rest.utils.VirtualHostParser;
import io.netty.handler.codec.http.HttpMethod;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public class LocalS3RouterFactory {

  /**
   * The operations whose requests aren't worth recording in the statistics of the requests: the health check, which a
   * probe requests every few seconds, and the administration endpoints, which would record themselves.
   */
  public static final Set<String> UNRECORDED_OPERATIONS = Set.of("HealthCheck", "HeadHealthCheck",
      AdminController.STATS_OPERATION, AdminController.REQUESTS_OPERATION, AdminController.RESET_OPERATION);

  /**
   * The operations that LocalS3 routes but doesn't implement. Each of them answers {@code 501 NotImplemented} with an
   * error that names the operation, so that a client fails clearly instead of appearing to succeed. Operations whose
   * requests are the same, e.g. {@code GetBucketNotification} and {@code GetBucketNotificationConfiguration}, share one
   * route.
   */
  private static final List<NotImplementedOperation> NOT_IMPLEMENTED_OPERATIONS = List.of(
      // Bucket configurations.
      new NotImplementedOperation("GetBucketAccelerateConfiguration", GET, BUCKET_PATH, has("accelerate")),
      new NotImplementedOperation("PutBucketAccelerateConfiguration", PUT, BUCKET_PATH, has("accelerate")),
      new NotImplementedOperation("GetBucketLogging", GET, BUCKET_PATH, has("logging")),
      new NotImplementedOperation("PutBucketLogging", PUT, BUCKET_PATH, has("logging")),
      new NotImplementedOperation("GetBucketNotificationConfiguration", GET, BUCKET_PATH, has("notification")),
      new NotImplementedOperation("PutBucketNotificationConfiguration", PUT, BUCKET_PATH, has("notification")),
      new NotImplementedOperation("GetBucketOwnershipControls", GET, BUCKET_PATH, has("ownershipControls")),
      new NotImplementedOperation("PutBucketOwnershipControls", PUT, BUCKET_PATH, has("ownershipControls")),
      new NotImplementedOperation("DeleteBucketOwnershipControls", DELETE, BUCKET_PATH, has("ownershipControls")),
      new NotImplementedOperation("GetBucketRequestPayment", GET, BUCKET_PATH, has("requestPayment")),
      new NotImplementedOperation("PutBucketRequestPayment", PUT, BUCKET_PATH, has("requestPayment")),
      new NotImplementedOperation("GetBucketWebsite", GET, BUCKET_PATH, has("website")),
      new NotImplementedOperation("PutBucketWebsite", PUT, BUCKET_PATH, has("website")),
      new NotImplementedOperation("DeleteBucketWebsite", DELETE, BUCKET_PATH, has("website")),
      // Bucket configurations of which a bucket has several, each named by an id: a GET with the id reads one, and a
      // GET without it lists them.
      new NotImplementedOperation("GetBucketAnalyticsConfiguration", GET, BUCKET_PATH, has("analytics", "id")),
      new NotImplementedOperation("ListBucketAnalyticsConfigurations", GET, BUCKET_PATH,
          has("analytics").andHasNot("id")),
      new NotImplementedOperation("PutBucketAnalyticsConfiguration", PUT, BUCKET_PATH, has("analytics")),
      new NotImplementedOperation("DeleteBucketAnalyticsConfiguration", DELETE, BUCKET_PATH, has("analytics")),
      new NotImplementedOperation("GetBucketIntelligentTieringConfiguration", GET, BUCKET_PATH,
          has("intelligent-tiering", "id")),
      new NotImplementedOperation("ListBucketIntelligentTieringConfigurations", GET, BUCKET_PATH,
          has("intelligent-tiering").andHasNot("id")),
      new NotImplementedOperation("PutBucketIntelligentTieringConfiguration", PUT, BUCKET_PATH,
          has("intelligent-tiering")),
      new NotImplementedOperation("DeleteBucketIntelligentTieringConfiguration", DELETE, BUCKET_PATH,
          has("intelligent-tiering")),
      new NotImplementedOperation("GetBucketInventoryConfiguration", GET, BUCKET_PATH, has("inventory", "id")),
      new NotImplementedOperation("ListBucketInventoryConfigurations", GET, BUCKET_PATH,
          has("inventory").andHasNot("id")),
      new NotImplementedOperation("PutBucketInventoryConfiguration", PUT, BUCKET_PATH, has("inventory")),
      new NotImplementedOperation("DeleteBucketInventoryConfiguration", DELETE, BUCKET_PATH, has("inventory")),
      new NotImplementedOperation("GetBucketMetricsConfiguration", GET, BUCKET_PATH, has("metrics", "id")),
      new NotImplementedOperation("ListBucketMetricsConfigurations", GET, BUCKET_PATH,
          has("metrics").andHasNot("id")),
      new NotImplementedOperation("PutBucketMetricsConfiguration", PUT, BUCKET_PATH, has("metrics")),
      new NotImplementedOperation("DeleteBucketMetricsConfiguration", DELETE, BUCKET_PATH, has("metrics")),
      // Object lock and retention.
      new NotImplementedOperation("GetObjectLegalHold", GET, BUCKET_KEY_PATH, has("legal-hold")),
      new NotImplementedOperation("PutObjectLegalHold", PUT, BUCKET_KEY_PATH, has("legal-hold")),
      new NotImplementedOperation("GetObjectLockConfiguration", GET, BUCKET_KEY_PATH, has("object-lock")),
      new NotImplementedOperation("PutObjectLockConfiguration", PUT, BUCKET_KEY_PATH, has("object-lock")),
      new NotImplementedOperation("GetObjectRetention", GET, BUCKET_KEY_PATH, has("retention")),
      new NotImplementedOperation("PutObjectRetention", PUT, BUCKET_KEY_PATH, has("retention")),
      // Object retrieval and transformation.
      new NotImplementedOperation("GetObjectTorrent", GET, BUCKET_KEY_PATH, has("torrent")),
      new NotImplementedOperation("RestoreObject", POST, BUCKET_KEY_PATH, has("restore")),
      new NotImplementedOperation("SelectObjectContent", POST, BUCKET_KEY_PATH, has("select")),
      new NotImplementedOperation("WriteGetObjectResponse", POST, "/WriteGetObjectResponse", null)
  );

  /**
   * Create a new LocalS3Router instance.
   */
  public static Router create(ServiceFactory serviceFactory) {
    return create(serviceFactory, null, null);
  }

  /**
   * Create a new LocalS3Router instance with optional SigV4 authentication.
   *
   * <p>The routes are declared one per line: the routes of the implemented operations by the methods below, split by
   * the resource they address and by whether they read or write it, and the routes of the operations that answer
   * {@code 501 NotImplemented} by {@linkplain #NOT_IMPLEMENTED_OPERATIONS}.
   *
   * <p>The conditions of a route are declared with {@linkplain ParamCondition} and {@linkplain HeaderCondition},
   * and the order the routes are registered in doesn't matter: once they are registered,
   * {@linkplain LocalS3Router#verifyRoutes()} fails the creation of the router if two routes of the same
   * (method, path) match the same request equally, or if a route can't be reached, so that a route that
   * overlaps another one is found when the service starts rather than by a request that reaches the wrong
   * handler.
   */
  public static Router create(ServiceFactory serviceFactory, String accessKeyId,
      String secretAccessKey) {
    Objects.requireNonNull(serviceFactory);
    if ((accessKeyId == null) != (secretAccessKey == null)) {
      throw new IllegalArgumentException("Both accessKeyId and secretAccessKey must be configured.");
    }

    // Without a registered parser, only the default virtual-host domains are recognized.
    VirtualHostParser virtualHostParser = serviceFactory.containsInstance(VirtualHostParser.class)
        ? serviceFactory.getInstance(VirtualHostParser.class)
        : new VirtualHostParser(Set.of());
    CorsResponseHeaders corsResponseHeaders = serviceFactory.containsInstance(BucketService.class)
        ? new CorsResponseHeaders(serviceFactory.getInstance(BucketService.class))
        : null;
    AwsSignatureV4Verifier signatureVerifier =
        accessKeyId == null ? null : new AwsSignatureV4Verifier(accessKeyId, secretAccessKey);
    LocalS3Router router = new LocalS3Router(signatureVerifier, virtualHostParser, corsResponseHeaders);

    Routes routes = new Routes(router);
    SharedControllers shared = SharedControllers.create(serviceFactory);
    serviceRoutes(routes, serviceFactory);
    bucketReadRoutes(routes, serviceFactory, shared);
    bucketWriteRoutes(routes, serviceFactory, shared, signatureVerifier);
    objectReadRoutes(routes, serviceFactory, shared);
    objectWriteRoutes(routes, serviceFactory, shared);
    vectorRoutes(routes, serviceFactory);
    for (NotImplementedOperation operation : NOT_IMPLEMENTED_OPERATIONS) {
      routes.add(operation.name(), operation.method(), operation.path(), operation.params(), null,
          new NotImplementedOperationController(serviceFactory, operation.name()));
    }
    router.verifyRoutes();

    return router
        .notFound(new NotFoundHandler())
        .exceptionHandler(LocalS3Exception.class, new LocalS3ExceptionHandler(serviceFactory))
        .exceptionHandler(LocalS3InvalidArgumentException.class, new LocalS3InvalidArgumentExceptionHandler())
        .exceptionHandler(LocalS3VectorException.class, new LocalS3VectorExceptionHandler(serviceFactory))
        .exceptionHandler(Exception.class, new ExceptionHandler());
  }

  /**
   * The controllers that routes of more than one group share, so that the operations of one resource, e.g.
   * the three of a bucket policy, are answered through the same instance rather than through one per route.
   */
  private record SharedControllers(BucketPolicyController bucketPolicy,
                                   BucketReplicationController bucketReplication,
                                   BucketEncryptionController bucketEncryption,
                                   BucketLifecycleController bucketLifecycle,
                                   ObjectTaggingController objectTagging) {

    static SharedControllers create(ServiceFactory serviceFactory) {
      return new SharedControllers(new BucketPolicyController(serviceFactory),
          new BucketReplicationController(serviceFactory),
          new BucketEncryptionController(serviceFactory),
          new BucketLifecycleController(serviceFactory),
          new ObjectTaggingController(serviceFactory));
    }
  }

  /**
   * The routes that aren't Amazon S3 operations: the health check that a container probe requests, the
   * CORS preflight that a browser sends before a cross-origin request, neither of which is signed, and the
   * administration endpoints of a running service, see {@linkplain AdminController}.
   */
  private static void serviceRoutes(Routes routes, ServiceFactory serviceFactory) {
    // The two health check routes answer through one controller, and so do the two preflight ones.
    HealthCheckController healthCheck = new HealthCheckController();
    CorsPreflightController corsPreflight = new CorsPreflightController(serviceFactory);

    routes
        .add("HealthCheck", GET, HEALTH_CHECK_PATH, healthCheck)
        .add("HeadHealthCheck", HEAD, HEALTH_CHECK_PATH, healthCheck)
        .add("BucketCorsPreflight", OPTIONS, BUCKET_PATH, corsPreflight)
        .add("ObjectCorsPreflight", OPTIONS, BUCKET_KEY_PATH, corsPreflight);

    // Only a running service has an administration; a router of handlers alone doesn't.
    if (serviceFactory.containsInstance(LocalS3Admin.class)) {
      AdminController admin = new AdminController(serviceFactory);
      routes
          .add(AdminController.STATS_OPERATION, GET, AdminController.STATS_PATH, admin::stats)
          .add(AdminController.REQUESTS_OPERATION, GET, AdminController.REQUESTS_PATH, admin::requests)
          .add(AdminController.RESET_OPERATION, POST, AdminController.RESET_PATH, admin::reset);
    }
  }

  /**
   * The operations that read a bucket, its configuration or the list of buckets, i.e. the {@code GET}
   * and {@code HEAD} requests addressed at one.
   */
  private static void bucketReadRoutes(Routes routes, ServiceFactory factory, SharedControllers shared) {
    routes
        .add("GetBucketAcl", GET, BUCKET_PATH, has("acl"), new GetBucketAclController(factory))
        .add("GetBucketCors", GET, BUCKET_PATH, has("cors"), new GetBucketCorsController(factory))
        .add("GetBucketEncryption", GET, BUCKET_PATH, has("encryption"), shared.bucketEncryption()::get)
        .add("GetBucketLifecycleConfiguration", GET, BUCKET_PATH, has("lifecycle"), shared.bucketLifecycle()::get)
        .add("GetBucketLocation", GET, BUCKET_PATH, has("location"), new GetBucketLocationController(factory))
        .add("GetBucketPolicy", GET, BUCKET_PATH, has("policy"), shared.bucketPolicy()::get)
        .add("GetBucketPolicyStatus", GET, BUCKET_PATH, has("policyStatus"),
            new GetBucketPolicyStatusController(factory))
        .add("GetBucketReplication", GET, BUCKET_PATH, has("replication"), shared.bucketReplication()::get)
        .add("GetBucketTagging", GET, BUCKET_PATH, has("tagging"), new GetBucketTaggingController(factory))
        .add("GetBucketVersioning", GET, BUCKET_PATH, has("versioning"), new GetBucketVersioningController(factory))
        .add("GetPublicAccessBlock", GET, BUCKET_PATH, has("publicAccessBlock"),
            new GetPublicAccessBlockController(factory))
        .add("HeadBucket", HEAD, BUCKET_PATH, new HeadBucketController(factory))
        .add("ListBuckets", GET, "/", new ListBucketsController(factory))
        .add("ListMultipartUploads", GET, BUCKET_PATH, has("uploads"), new ListMultipartUploadsController(factory))
        .add("ListObjects", GET, BUCKET_PATH, new ListObjectsController(factory))
        .add("ListObjectsV2", GET, BUCKET_PATH, equalTo("list-type", "2"), new ListObjectsV2Controller(factory))
        .add("ListObjectVersions", GET, BUCKET_PATH, has("versions"), new ListObjectVersionsController(factory));
  }

  /**
   * The operations that create, configure or delete a bucket, i.e. the {@code PUT}, {@code POST} and
   * {@code DELETE} requests addressed at one. {@code DeleteObjects} and {@code PostObject} are here as well: they
   * are posted to the bucket rather than to an object.
   *
   * @param signatureVerifier verifies the policy of a {@code PostObject} form, whose credentials are fields of the form
   *     rather than headers; {@code null} if the service doesn't require signed requests.
   */
  private static void bucketWriteRoutes(Routes routes, ServiceFactory factory, SharedControllers shared,
                                        AwsSignatureV4Verifier signatureVerifier) {
    routes
        .add("CreateBucket", PUT, BUCKET_PATH, new CreateBucketController(factory))
        .add("DeleteBucket", DELETE, BUCKET_PATH, new DeleteBucketController(factory))
        .add("DeleteBucketCors", DELETE, BUCKET_PATH, has("cors"), new DeleteBucketCorsController(factory))
        .add("DeleteBucketEncryption", DELETE, BUCKET_PATH, has("encryption"), shared.bucketEncryption()::delete)
        .add("DeleteBucketLifecycle", DELETE, BUCKET_PATH, has("lifecycle"), shared.bucketLifecycle()::delete)
        .add("DeleteBucketPolicy", DELETE, BUCKET_PATH, has("policy"), shared.bucketPolicy()::delete)
        .add("DeleteBucketReplication", DELETE, BUCKET_PATH, has("replication"), shared.bucketReplication()::delete)
        .add("DeleteBucketTagging", DELETE, BUCKET_PATH, has("tagging"), new DeleteBucketTaggingController(factory))
        .add("DeleteObjects", POST, BUCKET_PATH, has("delete"), new DeleteObjectsController(factory))
        .add("DeletePublicAccessBlock", DELETE, BUCKET_PATH, has("publicAccessBlock"),
            new DeletePublicAccessBlockController(factory))
        .add(PostObjectController.OPERATION, POST, BUCKET_PATH, new PostObjectController(factory, signatureVerifier))
        .add("PutBucketAcl", PUT, BUCKET_PATH, has("acl"), new PutBucketAclController(factory))
        .add("PutBucketCors", PUT, BUCKET_PATH, has("cors"), new PutBucketCorsController(factory))
        .add("PutBucketEncryption", PUT, BUCKET_PATH, has("encryption"), shared.bucketEncryption()::put)
        .add("PutBucketLifecycleConfiguration", PUT, BUCKET_PATH, has("lifecycle"), shared.bucketLifecycle()::put)
        .add("PutBucketPolicy", PUT, BUCKET_PATH, has("policy"), shared.bucketPolicy()::put)
        .add("PutBucketReplication", PUT, BUCKET_PATH, has("replication"), shared.bucketReplication()::put)
        .add("PutBucketTagging", PUT, BUCKET_PATH, has("tagging"), new PutBucketTaggingController(factory))
        .add("PutBucketVersioning", PUT, BUCKET_PATH, has("versioning"), new PutBucketVersioningController(factory))
        .add("PutPublicAccessBlock", PUT, BUCKET_PATH, has("publicAccessBlock"),
            new PutPublicAccessBlockController(factory));
  }

  /**
   * The operations that read an object, its metadata or its tags, i.e. the {@code GET} and {@code HEAD}
   * requests addressed at one. {@code ListParts} is here as well: it reads the parts of an upload.
   */
  private static void objectReadRoutes(Routes routes, ServiceFactory factory, SharedControllers shared) {
    routes
        .add("GetObject", GET, BUCKET_KEY_PATH, HeaderCondition.hasNot(AmzHeaderNames.X_AMZ_OBJECT_ATTRIBUTES),
            new GetObjectController(factory))
        .add("GetObjectAcl", GET, BUCKET_KEY_PATH, has("acl"), new GetObjectAclController(factory))
        .add("GetObjectAttributes", GET, BUCKET_KEY_PATH, HeaderCondition.has(AmzHeaderNames.X_AMZ_OBJECT_ATTRIBUTES),
            new GetObjectAttributesController(factory))
        .add("GetObjectTagging", GET, BUCKET_KEY_PATH, has("tagging"), shared.objectTagging()::get)
        .add("HeadObject", HEAD, BUCKET_KEY_PATH, new HeadObjectController(factory))
        .add("ListParts", GET, BUCKET_KEY_PATH, has("uploadId"), new ListPartsController(factory));
  }

  /**
   * The operations that store, copy or delete an object, i.e. the {@code PUT}, {@code POST} and
   * {@code DELETE} requests addressed at one, including the multipart upload of it.
   */
  private static void objectWriteRoutes(Routes routes, ServiceFactory factory, SharedControllers shared) {
    HeaderCondition copySource = HeaderCondition.has(AmzHeaderNames.X_AMZ_COPY_SOURCE);
    routes
        .add("AbortMultipartUpload", DELETE, BUCKET_KEY_PATH, has("uploadId"),
            new AbortMultipartUploadController(factory))
        .add("CompleteMultipartUpload", POST, BUCKET_KEY_PATH, new CompleteMultipartUploadController(factory))
        .add("CopyObject", PUT, BUCKET_KEY_PATH, copySource, new CopyObjectController(factory))
        .add("CreateMultipartUpload", POST, BUCKET_KEY_PATH, has("uploads"),
            new CreateMultipartUploadController(factory))
        .add("DeleteObject", DELETE, BUCKET_KEY_PATH, new DeleteObjectController(factory))
        .add("DeleteObjectTagging", DELETE, BUCKET_KEY_PATH, has("tagging"), shared.objectTagging()::delete)
        .add("PutObject", PUT, BUCKET_KEY_PATH, new PutObjectController(factory))
        .add("PutObjectAcl", PUT, BUCKET_KEY_PATH, has("acl"), new PutObjectAclController(factory))
        .add("PutObjectTagging", PUT, BUCKET_KEY_PATH, has("tagging"), shared.objectTagging()::put)
        .add("UploadPart", PUT, BUCKET_KEY_PATH, has("uploadId", "partNumber"), new UploadPartController(factory))
        .add("UploadPartCopy", PUT, BUCKET_KEY_PATH, has("uploadId", "partNumber"), copySource,
            new UploadPartCopyController(factory));
  }

  /**
   * The <a href="https://docs.aws.amazon.com/AmazonS3/latest/userguide/s3-vectors.html">S3 Vectors</a>
   * operations, each of which is posted to a path named after it rather than addressed at a bucket or an object.
   */
  private static void vectorRoutes(Routes routes, ServiceFactory factory) {
    routes
        .addVectorOperation("CreateVectorBucket", new CreateVectorBucketController(factory))
        .addVectorOperation("GetVectorBucket", new GetVectorBucketController(factory))
        .addVectorOperation("DeleteVectorBucket", new DeleteVectorBucketController(factory))
        .addVectorOperation("ListVectorBuckets", new ListVectorBucketsController(factory))
        .addVectorOperation("PutVectorBucketPolicy", new PutVectorBucketPolicyController(factory))
        .addVectorOperation("GetVectorBucketPolicy", new GetVectorBucketPolicyController(factory))
        .addVectorOperation("DeleteVectorBucketPolicy", new DeleteVectorBucketPolicyController(factory))
        .addVectorOperation("CreateIndex", new CreateIndexController(factory))
        .addVectorOperation("GetIndex", new GetIndexController(factory))
        .addVectorOperation("ListIndexes", new ListIndexesController(factory))
        .addVectorOperation("DeleteIndex", new DeleteIndexController(factory))
        .addVectorOperation("PutVectors", new PutVectorsController(factory))
        .addVectorOperation("QueryVectors", new QueryVectorsController(factory))
        .addVectorOperation("GetVectors", new GetVectorsController(factory))
        .addVectorOperation("DeleteVectors", new DeleteVectorsController(factory))
        .addVectorOperation("ListVectors", new ListVectorsController(factory));
  }

  /**
   * An operation that LocalS3 routes but doesn't implement.
   *
   * @param name the name of the operation, which the error answered to its requests names.
   * @param method the HTTP method of its requests.
   * @param path the path of its requests.
   * @param params the query parameters that tell its requests apart; {@code null} if the path alone does.
   */
  private record NotImplementedOperation(String name, HttpMethod method, String path, ParamCondition params) {
  }

  /**
   * Registers the routes of a router, one operation per call.
   */
  private record Routes(LocalS3Router router) {

    Routes add(String operation, HttpMethod method, String path, HttpRequestHandler handler) {
      return add(operation, method, path, null, null, handler);
    }

    Routes add(String operation, HttpMethod method, String path, ParamCondition params, HttpRequestHandler handler) {
      return add(operation, method, path, params, null, handler);
    }

    Routes add(String operation, HttpMethod method, String path, HeaderCondition headers,
               HttpRequestHandler handler) {
      return add(operation, method, path, null, headers, handler);
    }

    /**
     * Register the route of an operation.
     *
     * @param params the query parameters that a request of the operation has; {@code null} for any.
     * @param headers the headers that a request of the operation has; {@code null} for any.
     */
    Routes add(String operation, HttpMethod method, String path, ParamCondition params, HeaderCondition headers,
               HttpRequestHandler handler) {
      Route.Builder route = Route.builder().method(method).path(path).handler(handler);
      if (params != null) {
        route.paramMatcher(params);
      }
      if (headers != null) {
        route.headerMatcher(headers);
      }
      router.route(operation, route.build());
      return this;
    }

    Routes addVectorOperation(String operation, HttpRequestHandler handler) {
      return add(operation, POST, "/" + operation, handler);
    }

  }

}
