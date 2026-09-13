package com.robothy.s3.rest.handler;

import static com.robothy.s3.rest.handler.LocalS3Router.BUCKET_KEY_PATH;
import static com.robothy.s3.rest.handler.LocalS3Router.BUCKET_PATH;
import com.robothy.netty.router.Route;
import com.robothy.netty.router.Router;
import com.robothy.s3.core.exception.LocalS3Exception;
import com.robothy.s3.core.exception.LocalS3InvalidArgumentException;
import com.robothy.s3.core.exception.vectors.LocalS3VectorException;
import com.robothy.s3.rest.constants.AmzHeaderNames;
import com.robothy.s3.rest.handler.s3vectors.LocalS3VectorExceptionHandler;
import com.robothy.s3.rest.service.ServiceFactory;
import com.robothy.s3.rest.utils.VirtualHostParser;
import com.robothy.s3.core.service.BucketService;
import io.netty.handler.codec.http.HttpMethod;
import java.util.Objects;
import java.util.Set;

public class LocalS3RouterFactory {

  /**
   * Create a new LocalS3Router instance.
   */
  public static Router create(ServiceFactory serviceFactory) {
    return create(serviceFactory, null, null);
  }

  /**
   * Create a new LocalS3Router instance with optional SigV4 authentication.
   *
   * <p>The routes are declared by the methods below, split by the resource they address and by whether they
   * read or write it. The split follows the key that {@linkplain LocalS3Router} dispatches on, i.e. the HTTP
   * method and the path of a route, so that every route of one (method, path) is registered by one method.
   *
   * <p>The conditions of a route are declared with {@linkplain ParamCondition} and {@linkplain HeaderCondition},
   * and the order the routes are registered in doesn't matter: once they are registered,
   * {@linkplain LocalS3Router#verifyRoutes()} fails the creation of the router if two routes of the same
   * (method, path) match the same request equally, or if a route can't be reached, so that a route that
   * overlaps another one is found when the service starts rather than by a request that reaches the wrong
   * handler. Operations whose requests are the same, e.g. {@code GetBucketLifecycle} and
   * {@code GetBucketLifecycleConfiguration}, are answered by one route.
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
    LocalS3Router router = new LocalS3Router(
        accessKeyId == null ? null : new AwsSignatureV4Verifier(accessKeyId, secretAccessKey), virtualHostParser,
        corsResponseHeaders);

    SharedControllers shared = SharedControllers.create(serviceFactory);
    serviceRoutes(router, serviceFactory);
    bucketReadRoutes(router, serviceFactory, shared);
    bucketWriteRoutes(router, serviceFactory, shared);
    objectReadRoutes(router, serviceFactory, shared);
    objectWriteRoutes(router, serviceFactory, shared);
    vectorRoutes(router, serviceFactory);
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
                                   ObjectTaggingController objectTagging) {

    static SharedControllers create(ServiceFactory serviceFactory) {
      return new SharedControllers(new BucketPolicyController(serviceFactory),
          new BucketReplicationController(serviceFactory),
          new BucketEncryptionController(serviceFactory),
          new ObjectTaggingController(serviceFactory));
    }
  }

  /**
   * The routes that aren't Amazon S3 operations: the health check that a container probe requests, and
   * the CORS preflight that a browser sends before a cross-origin request. Neither of them is signed.
   */
  private static void serviceRoutes(LocalS3Router router, ServiceFactory serviceFactory) {
    // The two health check routes answer through one controller, and so do the two preflight ones.
    HealthCheckController healthCheckController = new HealthCheckController();
    CorsPreflightController corsPreflightController = new CorsPreflightController(serviceFactory);

    Route HealthCheck = Route.builder()
        .method(HttpMethod.GET)
        .path(LocalS3Router.HEALTH_CHECK_PATH)
        .handler(healthCheckController)
        .build();
    Route HeadHealthCheck = Route.builder()
        .method(HttpMethod.HEAD)
        .path(LocalS3Router.HEALTH_CHECK_PATH)
        .handler(healthCheckController)
        .build();
    Route BucketCorsPreflight = Route.builder()
        .method(HttpMethod.OPTIONS)
        .path(BUCKET_PATH)
        .handler(corsPreflightController)
        .build();
    Route ObjectCorsPreflight = Route.builder()
        .method(HttpMethod.OPTIONS)
        .path(BUCKET_KEY_PATH)
        .handler(corsPreflightController)
        .build();

    router
        .route("HealthCheck", HealthCheck)
        .route("HeadHealthCheck", HeadHealthCheck)
        .route("BucketCorsPreflight", BucketCorsPreflight)
        .route("ObjectCorsPreflight", ObjectCorsPreflight)
        ;
  }

  /**
   * The operations that read a bucket, its configuration or the list of buckets, i.e. the {@code GET}
   * and {@code HEAD} requests addressed at one.
   */
  private static void bucketReadRoutes(LocalS3Router router, ServiceFactory serviceFactory, SharedControllers shared) {

    Route GetBucketAccelerateConfiguration = Route.builder()
        .method(HttpMethod.GET)
        .path(BUCKET_PATH)
        .paramMatcher(ParamCondition.has("accelerate"))
        .handler(new NotImplementedOperationController(serviceFactory, "GetBucketAccelerateConfiguration"))
        .build();
    Route GetBucketAcl = Route.builder()
        .method(HttpMethod.GET)
        .path(BUCKET_PATH)
        .paramMatcher(ParamCondition.has("acl"))
        .handler(new GetBucketAclController(serviceFactory))
        .build();
    Route GetBucketAnalyticsConfiguration = Route.builder()
        .method(HttpMethod.GET)
        .path(BUCKET_PATH)
        .paramMatcher(ParamCondition.has("analytics", "id"))
        .handler(new NotImplementedOperationController(serviceFactory, "GetBucketAnalyticsConfiguration"))
        .build();
    Route GetBucketCors = Route.builder()
        .method(HttpMethod.GET)
        .path(BUCKET_PATH)
        .paramMatcher(ParamCondition.has("cors"))
        .handler(new GetBucketCorsController(serviceFactory))
        .build();
    Route GetBucketEncryption = Route.builder()
        .method(HttpMethod.GET)
        .path(BUCKET_PATH)
        .paramMatcher(ParamCondition.has("encryption"))
        .handler(shared.bucketEncryption()::get)
        .build();
    Route GetBucketIntelligentTieringConfiguration = Route.builder()
        .method(HttpMethod.GET)
        .path(BUCKET_PATH)
        .paramMatcher(ParamCondition.has("intelligent-tiering", "id"))
        .handler(new NotImplementedOperationController(serviceFactory, "GetBucketIntelligentTieringConfiguration"))
        .build();
    Route GetBucketInventoryConfiguration = Route.builder()
        .method(HttpMethod.GET)
        .path(BUCKET_PATH)
        .paramMatcher(ParamCondition.has("inventory", "id"))
        .handler(new NotImplementedOperationController(serviceFactory, "GetBucketInventoryConfiguration"))
        .build();
    Route GetBucketLifecycleConfiguration = Route.builder()
        .method(HttpMethod.GET)
        .path(BUCKET_PATH)
        .paramMatcher(ParamCondition.has("lifecycle"))
        .handler(new NotImplementedOperationController(serviceFactory, "GetBucketLifecycleConfiguration"))
        .build();
    Route GetBucketLocation = Route.builder()
        .method(HttpMethod.GET)
        .path(BUCKET_PATH)
        .paramMatcher(ParamCondition.has("location"))
        .handler(new GetBucketLocationController(serviceFactory))
        .build();
    Route GetBucketLogging = Route.builder()
        .method(HttpMethod.GET)
        .path(BUCKET_PATH)
        .paramMatcher(ParamCondition.has("logging"))
        .handler(new NotImplementedOperationController(serviceFactory, "GetBucketLogging"))
        .build();
    Route GetBucketMetricsConfiguration = Route.builder()
        .method(HttpMethod.GET)
        .path(BUCKET_PATH)
        .paramMatcher(ParamCondition.has("metrics", "id"))
        .handler(new NotImplementedOperationController(serviceFactory, "GetBucketMetricsConfiguration"))
        .build();
    Route GetBucketNotificationConfiguration = Route.builder()
        .method(HttpMethod.GET)
        .path(BUCKET_PATH)
        .paramMatcher(ParamCondition.has("notification"))
        .handler(new NotImplementedOperationController(serviceFactory, "GetBucketNotificationConfiguration"))
        .build();
    Route GetBucketOwnershipControls = Route.builder()
        .method(HttpMethod.GET)
        .path(BUCKET_PATH)
        .paramMatcher(ParamCondition.has("ownershipControls"))
        .handler(new NotImplementedOperationController(serviceFactory, "GetBucketOwnershipControls"))
        .build();
    Route GetBucketPolicy = Route.builder()
        .method(HttpMethod.GET)
        .path(BUCKET_PATH)
        .paramMatcher(ParamCondition.has("policy"))
        .handler(shared.bucketPolicy()::get)
        .build();
    Route GetBucketPolicyStatus = Route.builder()
        .method(HttpMethod.GET)
        .path(BUCKET_PATH)
        .paramMatcher(ParamCondition.has("policyStatus"))
        .handler(new GetBucketPolicyStatusController(serviceFactory))
        .build();
    Route GetBucketReplication = Route.builder()
        .method(HttpMethod.GET)
        .path(BUCKET_PATH)
        .paramMatcher(ParamCondition.has("replication"))
        .handler(shared.bucketReplication()::get)
        .build();
    Route GetBucketRequestPayment = Route.builder()
        .method(HttpMethod.GET)
        .path(BUCKET_PATH)
        .paramMatcher(ParamCondition.has("requestPayment"))
        .handler(new NotImplementedOperationController(serviceFactory, "GetBucketRequestPayment"))
        .build();
    Route GetBucketTagging = Route.builder()
        .method(HttpMethod.GET)
        .path(BUCKET_PATH)
        .paramMatcher(ParamCondition.has("tagging"))
        .handler(new GetBucketTaggingController(serviceFactory))
        .build();
    Route GetBucketVersioning = Route.builder()
        .method(HttpMethod.GET)
        .path(BUCKET_PATH)
        .paramMatcher(ParamCondition.has("versioning"))
        .handler(new GetBucketVersioningController(serviceFactory))
        .build();
    Route GetBucketWebsite = Route.builder()
        .method(HttpMethod.GET)
        .path(BUCKET_PATH)
        .paramMatcher(ParamCondition.has("website"))
        .handler(new NotImplementedOperationController(serviceFactory, "GetBucketWebsite"))
        .build();
    Route GetPublicAccessBlock = Route.builder()
        .method(HttpMethod.GET)
        .path(BUCKET_PATH)
        .paramMatcher(ParamCondition.has("publicAccessBlock"))
        .handler(new GetPublicAccessBlockController(serviceFactory))
        .build();
    Route HeadBucket = Route.builder()
        .method(HttpMethod.HEAD)
        .path(BUCKET_PATH)
        .handler(new HeadBucketController(serviceFactory))
        .build();
    Route ListBucketAnalyticsConfigurations = Route.builder()
        .method(HttpMethod.GET)
        .path(BUCKET_PATH)
        .paramMatcher(ParamCondition.has("analytics").andHasNot("id"))
        .handler(new NotImplementedOperationController(serviceFactory, "ListBucketAnalyticsConfigurations"))
        .build();
    Route ListBucketIntelligentTieringConfigurations = Route.builder()
        .method(HttpMethod.GET)
        .path(BUCKET_PATH)
        .paramMatcher(ParamCondition.has("intelligent-tiering").andHasNot("id"))
        .handler(new NotImplementedOperationController(serviceFactory, "ListBucketIntelligentTieringConfigurations"))
        .build();
    Route ListBucketInventoryConfigurations = Route.builder()
        .method(HttpMethod.GET)
        .path(BUCKET_PATH)
        .paramMatcher(ParamCondition.has("inventory").andHasNot("id"))
        .handler(new NotImplementedOperationController(serviceFactory, "ListBucketInventoryConfigurations"))
        .build();
    Route ListBucketMetricsConfigurations = Route.builder()
        .method(HttpMethod.GET)
        .path(BUCKET_PATH)
        .paramMatcher(ParamCondition.has("metrics").andHasNot("id"))
        .handler(new NotImplementedOperationController(serviceFactory, "ListBucketMetricsConfigurations"))
        .build();
    Route ListBuckets = Route.builder()
        .method(HttpMethod.GET)
        .path("/")
        .handler(new ListBucketsController(serviceFactory))
        .build();
    Route ListMultipartUploads = Route.builder()
        .method(HttpMethod.GET)
        .path(BUCKET_PATH)
        .paramMatcher(ParamCondition.has("uploads"))
        .handler(new ListMultipartUploadsController(serviceFactory))
        .build();
    Route ListObjects = Route.builder()
        .method(HttpMethod.GET)
        .path(BUCKET_PATH)
        .handler(new ListObjectsController(serviceFactory))
        .build();
    Route ListObjectsV2 = Route.builder()
        .method(HttpMethod.GET)
        .path(BUCKET_PATH)
        .paramMatcher(ParamCondition.equalTo("list-type", "2"))
        .handler(new ListObjectsV2Controller(serviceFactory))
        .build();
    Route ListObjectVersions = Route.builder()
        .method(HttpMethod.GET)
        .path(BUCKET_PATH)
        .paramMatcher(ParamCondition.has("versions"))
        .handler(new ListObjectVersionsController(serviceFactory))
        .build();

    router
        .route("GetBucketAccelerateConfiguration", GetBucketAccelerateConfiguration)
        .route("GetBucketAcl", GetBucketAcl)
        .route("GetBucketAnalyticsConfiguration", GetBucketAnalyticsConfiguration)
        .route("GetBucketCors", GetBucketCors)
        .route("GetBucketEncryption", GetBucketEncryption)
        .route("GetBucketIntelligentTieringConfiguration", GetBucketIntelligentTieringConfiguration)
        .route("GetBucketInventoryConfiguration", GetBucketInventoryConfiguration)
        .route("GetBucketLifecycleConfiguration", GetBucketLifecycleConfiguration)
        .route("GetBucketLocation", GetBucketLocation)
        .route("GetBucketLogging", GetBucketLogging)
        .route("GetBucketMetricsConfiguration", GetBucketMetricsConfiguration)
        .route("GetBucketNotificationConfiguration", GetBucketNotificationConfiguration)
        .route("GetBucketOwnershipControls", GetBucketOwnershipControls)
        .route("GetBucketPolicy", GetBucketPolicy)
        .route("GetBucketPolicyStatus", GetBucketPolicyStatus)
        .route("GetBucketReplication", GetBucketReplication)
        .route("GetBucketRequestPayment", GetBucketRequestPayment)
        .route("GetBucketTagging", GetBucketTagging)
        .route("GetBucketVersioning", GetBucketVersioning)
        .route("GetBucketWebsite", GetBucketWebsite)
        .route("GetPublicAccessBlock", GetPublicAccessBlock)
        .route("HeadBucket", HeadBucket)
        .route("ListBucketAnalyticsConfigurations", ListBucketAnalyticsConfigurations)
        .route("ListBucketIntelligentTieringConfigurations", ListBucketIntelligentTieringConfigurations)
        .route("ListBucketInventoryConfigurations", ListBucketInventoryConfigurations)
        .route("ListBucketMetricsConfigurations", ListBucketMetricsConfigurations)
        .route("ListBuckets", ListBuckets)
        .route("ListMultipartUploads", ListMultipartUploads)
        .route("ListObjects", ListObjects)
        .route("ListObjectsV2", ListObjectsV2)
        .route("ListObjectVersions", ListObjectVersions)
        ;
  }

  /**
   * The operations that create, configure or delete a bucket, i.e. the {@code PUT}, {@code POST} and
   * {@code DELETE} requests addressed at one. {@code DeleteObjects} is here as well: it is posted to the
   * bucket rather than to an object.
   */
  private static void bucketWriteRoutes(LocalS3Router router, ServiceFactory serviceFactory, SharedControllers shared) {

    Route CreateBucket = Route.builder()
        .method(HttpMethod.PUT)
        .path(BUCKET_PATH)
        .handler(new CreateBucketController(serviceFactory))
        .build();
    Route DeleteBucket = Route.builder()
        .method(HttpMethod.DELETE)
        .path(BUCKET_PATH)
        .handler(new DeleteBucketController(serviceFactory))
        .build();
    Route DeleteBucketAnalyticsConfiguration = Route.builder()
        .method(HttpMethod.DELETE)
        .path(BUCKET_PATH)
        .paramMatcher(ParamCondition.has("analytics"))
        .handler(new NotImplementedOperationController(serviceFactory, "DeleteBucketAnalyticsConfiguration"))
        .build();
    Route DeleteBucketCors = Route.builder()
        .method(HttpMethod.DELETE)
        .path(BUCKET_PATH)
        .paramMatcher(ParamCondition.has("cors"))
        .handler(new DeleteBucketCorsController(serviceFactory))
        .build();
    Route DeleteBucketEncryption = Route.builder()
        .method(HttpMethod.DELETE)
        .path(BUCKET_PATH)
        .paramMatcher(ParamCondition.has("encryption"))
        .handler(shared.bucketEncryption()::delete)
        .build();
    Route DeleteBucketIntelligentTieringConfiguration = Route.builder()
        .method(HttpMethod.DELETE)
        .path(BUCKET_PATH)
        .paramMatcher(ParamCondition.has("intelligent-tiering"))
        .handler(new NotImplementedOperationController(serviceFactory, "DeleteBucketIntelligentTieringConfiguration"))
        .build();
    Route DeleteBucketInventoryConfiguration = Route.builder()
        .method(HttpMethod.DELETE)
        .path(BUCKET_PATH)
        .paramMatcher(ParamCondition.has("inventory"))
        .handler(new NotImplementedOperationController(serviceFactory, "DeleteBucketInventoryConfiguration"))
        .build();
    Route DeleteBucketLifecycle = Route.builder()
        .method(HttpMethod.DELETE)
        .path(BUCKET_PATH)
        .paramMatcher(ParamCondition.has("lifecycle"))
        .handler(new NotImplementedOperationController(serviceFactory, "DeleteBucketLifecycle"))
        .build();
    Route DeleteBucketMetricsConfiguration = Route.builder()
        .method(HttpMethod.DELETE)
        .path(BUCKET_PATH)
        .paramMatcher(ParamCondition.has("metrics"))
        .handler(new NotImplementedOperationController(serviceFactory, "DeleteBucketMetricsConfiguration"))
        .build();
    Route DeleteBucketOwnershipControls = Route.builder()
        .method(HttpMethod.DELETE)
        .path(BUCKET_PATH)
        .paramMatcher(ParamCondition.has("ownershipControls"))
        .handler(new NotImplementedOperationController(serviceFactory, "DeleteBucketOwnershipControls"))
        .build();
    Route DeleteBucketPolicy = Route.builder()
        .method(HttpMethod.DELETE)
        .path(BUCKET_PATH)
        .paramMatcher(ParamCondition.has("policy"))
        .handler(shared.bucketPolicy()::delete)
        .build();
    Route DeleteBucketReplication = Route.builder()
        .method(HttpMethod.DELETE)
        .path(BUCKET_PATH)
        .paramMatcher(ParamCondition.has("replication"))
        .handler(shared.bucketReplication()::delete)
        .build();
    Route DeleteBucketTagging = Route.builder()
        .method(HttpMethod.DELETE)
        .path(BUCKET_PATH)
        .paramMatcher(ParamCondition.has("tagging"))
        .handler(new DeleteBucketTaggingController(serviceFactory))
        .build();
    Route DeleteBucketWebsite = Route.builder()
        .method(HttpMethod.DELETE)
        .path(BUCKET_PATH)
        .paramMatcher(ParamCondition.has("website"))
        .handler(new NotImplementedOperationController(serviceFactory, "DeleteBucketWebsite"))
        .build();
    Route DeleteObjects = Route.builder()
        .method(HttpMethod.POST)
        .path(BUCKET_PATH)
        .paramMatcher(ParamCondition.has("delete"))
        .handler(new DeleteObjectsController(serviceFactory))
        .build();
    Route DeletePublicAccessBlock = Route.builder()
        .method(HttpMethod.DELETE)
        .path(BUCKET_PATH)
        .paramMatcher(ParamCondition.has("publicAccessBlock"))
        .handler(new DeletePublicAccessBlockController(serviceFactory))
        .build();
    Route PutBucketAccelerateConfiguration = Route.builder()
        .method(HttpMethod.PUT)
        .path(BUCKET_PATH)
        .paramMatcher(ParamCondition.has("accelerate"))
        .handler(new NotImplementedOperationController(serviceFactory, "PutBucketAccelerateConfiguration"))
        .build();
    Route PutBucketAcl = Route.builder()
        .method(HttpMethod.PUT)
        .path(BUCKET_PATH)
        .paramMatcher(ParamCondition.has("acl"))
        .handler(new PutBucketAclController(serviceFactory))
        .build();
    Route PutBucketAnalyticsConfiguration = Route.builder()
        .method(HttpMethod.PUT)
        .path(BUCKET_PATH)
        .paramMatcher(ParamCondition.has("analytics"))
        .handler(new NotImplementedOperationController(serviceFactory, "PutBucketAnalyticsConfiguration"))
        .build();
    Route PutBucketCors = Route.builder()
        .method(HttpMethod.PUT)
        .path(BUCKET_PATH)
        .paramMatcher(ParamCondition.has("cors"))
        .handler(new PutBucketCorsController(serviceFactory))
        .build();
    Route PutBucketEncryption = Route.builder().method(HttpMethod.PUT)
        .path(BUCKET_PATH)
        .paramMatcher(ParamCondition.has("encryption"))
        .handler(shared.bucketEncryption()::put)
        .build();
    Route PutBucketIntelligentTieringConfiguration = Route.builder()
        .method(HttpMethod.PUT)
        .path(BUCKET_PATH)
        .paramMatcher(ParamCondition.has("intelligent-tiering"))
        .handler(new NotImplementedOperationController(serviceFactory, "PutBucketIntelligentTieringConfiguration"))
        .build();
    Route PutBucketInventoryConfiguration = Route.builder()
        .method(HttpMethod.PUT)
        .path(BUCKET_PATH)
        .paramMatcher(ParamCondition.has("inventory"))
        .handler(new NotImplementedOperationController(serviceFactory, "PutBucketInventoryConfiguration"))
        .build();
    Route PutBucketLifecycleConfiguration = Route.builder()
        .method(HttpMethod.PUT)
        .path(BUCKET_PATH)
        .paramMatcher(ParamCondition.has("lifecycle"))
        .handler(new NotImplementedOperationController(serviceFactory, "PutBucketLifecycleConfiguration"))
        .build();
    Route PutBucketLogging = Route.builder()
        .method(HttpMethod.PUT)
        .path(BUCKET_PATH)
        .paramMatcher(ParamCondition.has("logging"))
        .handler(new NotImplementedOperationController(serviceFactory, "PutBucketLogging"))
        .build();
    Route PutBucketMetricsConfiguration = Route.builder()
        .method(HttpMethod.PUT)
        .path(BUCKET_PATH)
        .paramMatcher(ParamCondition.has("metrics"))
        .handler(new NotImplementedOperationController(serviceFactory, "PutBucketMetricsConfiguration"))
        .build();
    Route PutBucketNotificationConfiguration = Route.builder()
        .method(HttpMethod.PUT)
        .path(BUCKET_PATH)
        .paramMatcher(ParamCondition.has("notification"))
        .handler(new NotImplementedOperationController(serviceFactory, "PutBucketNotificationConfiguration"))
        .build();
    Route PutBucketOwnershipControls = Route.builder()
        .method(HttpMethod.PUT)
        .path(BUCKET_PATH)
        .paramMatcher(ParamCondition.has("ownershipControls"))
        .handler(new NotImplementedOperationController(serviceFactory, "PutBucketOwnershipControls"))
        .build();
    Route PutBucketPolicy = Route.builder()
        .method(HttpMethod.PUT)
        .path(BUCKET_PATH)
        .paramMatcher(ParamCondition.has("policy"))
        .handler(shared.bucketPolicy()::put)
        .build();
    Route PutBucketReplication = Route.builder().method(HttpMethod.PUT)
        .path(BUCKET_PATH)
        .paramMatcher(ParamCondition.has("replication"))
        .handler(shared.bucketReplication()::put)
        .build();
    Route PutBucketRequestPayment = Route.builder()
        .method(HttpMethod.PUT)
        .path(BUCKET_PATH)
        .paramMatcher(ParamCondition.has("requestPayment"))
        .handler(new NotImplementedOperationController(serviceFactory, "PutBucketRequestPayment"))
        .build();
    Route PutBucketTagging = Route.builder()
        .method(HttpMethod.PUT)
        .path(BUCKET_PATH)
        .paramMatcher(ParamCondition.has("tagging"))
        .handler(new PutBucketTaggingController(serviceFactory))
        .build();
    Route PutBucketVersioning = Route.builder()
        .method(HttpMethod.PUT)
        .path(BUCKET_PATH)
        .paramMatcher(ParamCondition.has("versioning"))
        .handler(new PutBucketVersioningController(serviceFactory))
        .build();
    Route PutBucketWebsite = Route.builder()
        .method(HttpMethod.PUT)
        .path(BUCKET_PATH)
        .paramMatcher(ParamCondition.has("website"))
        .handler(new NotImplementedOperationController(serviceFactory, "PutBucketWebsite"))
        .build();
    Route PutPublicAccessBlock = Route.builder()
        .method(HttpMethod.PUT)
        .path(BUCKET_PATH)
        .paramMatcher(ParamCondition.has("publicAccessBlock"))
        .handler(new PutPublicAccessBlockController(serviceFactory))
        .build();

    router
        .route("CreateBucket", CreateBucket)
        .route("DeleteBucket", DeleteBucket)
        .route("DeleteBucketAnalyticsConfiguration", DeleteBucketAnalyticsConfiguration)
        .route("DeleteBucketCors", DeleteBucketCors)
        .route("DeleteBucketEncryption", DeleteBucketEncryption)
        .route("DeleteBucketIntelligentTieringConfiguration", DeleteBucketIntelligentTieringConfiguration)
        .route("DeleteBucketInventoryConfiguration", DeleteBucketInventoryConfiguration)
        .route("DeleteBucketLifecycle", DeleteBucketLifecycle)
        .route("DeleteBucketMetricsConfiguration", DeleteBucketMetricsConfiguration)
        .route("DeleteBucketOwnershipControls", DeleteBucketOwnershipControls)
        .route("DeleteBucketPolicy", DeleteBucketPolicy)
        .route("DeleteBucketReplication", DeleteBucketReplication)
        .route("DeleteBucketTagging", DeleteBucketTagging)
        .route("DeleteBucketWebsite", DeleteBucketWebsite)
        .route("DeleteObjects", DeleteObjects)
        .route("DeletePublicAccessBlock", DeletePublicAccessBlock)
        .route("PutBucketAccelerateConfiguration", PutBucketAccelerateConfiguration)
        .route("PutBucketAcl", PutBucketAcl)
        .route("PutBucketAnalyticsConfiguration", PutBucketAnalyticsConfiguration)
        .route("PutBucketCors", PutBucketCors)
        .route("PutBucketEncryption", PutBucketEncryption)
        .route("PutBucketIntelligentTieringConfiguration", PutBucketIntelligentTieringConfiguration)
        .route("PutBucketInventoryConfiguration", PutBucketInventoryConfiguration)
        .route("PutBucketLifecycleConfiguration", PutBucketLifecycleConfiguration)
        .route("PutBucketLogging", PutBucketLogging)
        .route("PutBucketMetricsConfiguration", PutBucketMetricsConfiguration)
        .route("PutBucketNotificationConfiguration", PutBucketNotificationConfiguration)
        .route("PutBucketOwnershipControls", PutBucketOwnershipControls)
        .route("PutBucketPolicy", PutBucketPolicy)
        .route("PutBucketReplication", PutBucketReplication)
        .route("PutBucketRequestPayment", PutBucketRequestPayment)
        .route("PutBucketTagging", PutBucketTagging)
        .route("PutBucketVersioning", PutBucketVersioning)
        .route("PutBucketWebsite", PutBucketWebsite)
        .route("PutPublicAccessBlock", PutPublicAccessBlock)
        ;
  }

  /**
   * The operations that read an object, its metadata or its tags, i.e. the {@code GET} and {@code HEAD}
   * requests addressed at one. {@code ListParts} is here as well: it reads the parts of an upload.
   */
  private static void objectReadRoutes(LocalS3Router router, ServiceFactory serviceFactory, SharedControllers shared) {

    Route GetObjectAttributes = Route.builder()
        .method(HttpMethod.GET)
        .path(BUCKET_KEY_PATH)
        .headerMatcher(HeaderCondition.has(AmzHeaderNames.X_AMZ_OBJECT_ATTRIBUTES))
        .handler(new GetObjectAttributesController(serviceFactory))
        .build();
    Route GetObject = Route.builder()
        .method(HttpMethod.GET)
        .path(BUCKET_KEY_PATH)
        .headerMatcher(HeaderCondition.hasNot(AmzHeaderNames.X_AMZ_OBJECT_ATTRIBUTES))
        .handler(new GetObjectController(serviceFactory))
        .build();
    Route GetObjectAcl = Route.builder()
        .method(HttpMethod.GET)
        .path(BUCKET_KEY_PATH)
        .paramMatcher(ParamCondition.has("acl"))
        .handler(new GetObjectAclController(serviceFactory))
        .build();
    Route GetObjectLegalHold = Route.builder()
        .method(HttpMethod.GET)
        .path(BUCKET_KEY_PATH)
        .paramMatcher(ParamCondition.has("legal-hold"))
        .handler(new NotImplementedOperationController(serviceFactory, "GetObjectLegalHold"))
        .build();
    Route GetObjectLockConfiguration = Route.builder()
        .method(HttpMethod.GET)
        .path(BUCKET_KEY_PATH)
        .paramMatcher(ParamCondition.has("object-lock"))
        .handler(new NotImplementedOperationController(serviceFactory, "GetObjectLockConfiguration"))
        .build();
    Route GetObjectRetention = Route.builder()
        .method(HttpMethod.GET)
        .path(BUCKET_KEY_PATH)
        .paramMatcher(ParamCondition.has("retention"))
        .handler(new NotImplementedOperationController(serviceFactory, "GetObjectRetention"))
        .build();
    Route GetObjectTagging = Route.builder()
        .method(HttpMethod.GET)
        .path(BUCKET_KEY_PATH)
        .paramMatcher(ParamCondition.has("tagging"))
        .handler(shared.objectTagging()::get)
        .build();
    Route GetObjectTorrent = Route.builder()
        .method(HttpMethod.GET)
        .path(BUCKET_KEY_PATH)
        .paramMatcher(ParamCondition.has("torrent"))
        .handler(new NotImplementedOperationController(serviceFactory, "GetObjectTorrent"))
        .build();
    Route HeadObject = Route.builder()
        .method(HttpMethod.HEAD)
        .path(BUCKET_KEY_PATH)
        .handler(new HeadObjectController(serviceFactory))
        .build();
    Route ListParts = Route.builder()
        .method(HttpMethod.GET)
        .path(BUCKET_KEY_PATH)
        .paramMatcher(ParamCondition.has("uploadId"))
        .handler(new ListPartsController(serviceFactory))
        .build();

    router
        .route("GetObjectAttributes", GetObjectAttributes)
        .route("GetObject", GetObject)
        .route("GetObjectAcl", GetObjectAcl)
        .route("GetObjectLegalHold", GetObjectLegalHold)
        .route("GetObjectLockConfiguration", GetObjectLockConfiguration)
        .route("GetObjectRetention", GetObjectRetention)
        .route("GetObjectTagging", GetObjectTagging)
        .route("GetObjectTorrent", GetObjectTorrent)
        .route("HeadObject", HeadObject)
        .route("ListParts", ListParts)
        ;
  }

  /**
   * The operations that store, copy or delete an object, i.e. the {@code PUT}, {@code POST} and
   * {@code DELETE} requests addressed at one, including the multipart upload of it.
   */
  private static void objectWriteRoutes(LocalS3Router router, ServiceFactory serviceFactory, SharedControllers shared) {

    Route AbortMultipartUpload = Route.builder()
        .method(HttpMethod.DELETE)
        .path(BUCKET_KEY_PATH)
        .paramMatcher(ParamCondition.has("uploadId"))
        .handler(new AbortMultipartUploadController(serviceFactory))
        .build();
    Route CompleteMultipartUpload = Route.builder()
        .method(HttpMethod.POST)
        .path(BUCKET_KEY_PATH)
        .handler(new CompleteMultipartUploadController(serviceFactory))
        .build();
    Route CopyObject = Route.builder()
        .method(HttpMethod.PUT)
        .path(BUCKET_KEY_PATH)
        .headerMatcher(HeaderCondition.has(AmzHeaderNames.X_AMZ_COPY_SOURCE))
        .handler(new CopyObjectController(serviceFactory))
        .build();
    Route CreateMultipartUpload = Route.builder()
        .method(HttpMethod.POST)
        .path(BUCKET_KEY_PATH)
        .paramMatcher(ParamCondition.has("uploads"))
        .handler(new CreateMultipartUploadController(serviceFactory))
        .build();
    Route DeleteObject = Route.builder()
        .method(HttpMethod.DELETE)
        .path(BUCKET_KEY_PATH)
        .handler(new DeleteObjectController(serviceFactory))
        .build();
    Route DeleteObjectTagging = Route.builder()
        .method(HttpMethod.DELETE)
        .path(BUCKET_KEY_PATH)
        .paramMatcher(ParamCondition.has("tagging"))
        .handler(shared.objectTagging()::delete)
        .build();
    Route PutObject = Route.builder()
        .method(HttpMethod.PUT)
        .path(BUCKET_KEY_PATH)
        .handler(new PutObjectController(serviceFactory))
        .build();
    Route PutObjectAcl = Route.builder()
        .method(HttpMethod.PUT)
        .path(BUCKET_KEY_PATH)
        .paramMatcher(ParamCondition.has("acl"))
        .handler(new PutObjectAclController(serviceFactory))
        .build();
    Route PutObjectLegalHold = Route.builder()
        .method(HttpMethod.PUT)
        .path(BUCKET_KEY_PATH)
        .paramMatcher(ParamCondition.has("legal-hold"))
        .handler(new NotImplementedOperationController(serviceFactory, "PutObjectLegalHold"))
        .build();
    Route PutObjectLockConfiguration = Route.builder()
        .method(HttpMethod.PUT)
        .path(BUCKET_KEY_PATH)
        .paramMatcher(ParamCondition.has("object-lock"))
        .handler(new NotImplementedOperationController(serviceFactory, "PutObjectLockConfiguration"))
        .build();
    Route PutObjectRetention = Route.builder()
        .method(HttpMethod.PUT)
        .path(BUCKET_KEY_PATH)
        .paramMatcher(ParamCondition.has("retention"))
        .handler(new NotImplementedOperationController(serviceFactory, "PutObjectRetention"))
        .build();
    Route PutObjectTagging = Route.builder()
        .method(HttpMethod.PUT)
        .path(BUCKET_KEY_PATH)
        .paramMatcher(ParamCondition.has("tagging"))
        .handler(shared.objectTagging()::put)
        .build();
    Route RestoreObject = Route.builder()
        .method(HttpMethod.POST)
        .path(BUCKET_KEY_PATH)
        .paramMatcher(ParamCondition.has("restore"))
        .handler(new NotImplementedOperationController(serviceFactory, "RestoreObject"))
        .build();
    Route SelectObjectContent = Route.builder()
        .method(HttpMethod.POST)
        .path(BUCKET_KEY_PATH)
        .paramMatcher(ParamCondition.has("select"))
        .handler(new NotImplementedOperationController(serviceFactory, "SelectObjectContent"))
        .build();
    Route UploadPart = Route.builder()
        .method(HttpMethod.PUT)
        .path(BUCKET_KEY_PATH)
        .paramMatcher(ParamCondition.has("uploadId", "partNumber"))
        .handler(new UploadPartController(serviceFactory))
        .build();
    Route UploadPartCopy = Route.builder()
        .method(HttpMethod.PUT)
        .path(BUCKET_KEY_PATH)
        .paramMatcher(ParamCondition.has("uploadId", "partNumber"))
        .headerMatcher(HeaderCondition.has(AmzHeaderNames.X_AMZ_COPY_SOURCE))
        .handler(new UploadPartCopyController(serviceFactory))
        .build();
    Route WriteGetObjectResponse = Route.builder()
        .method(HttpMethod.POST)
        .path("/WriteGetObjectResponse")
        .handler(new NotImplementedOperationController(serviceFactory, "WriteGetObjectResponse"))
        .build();

    router
        .route("AbortMultipartUpload", AbortMultipartUpload)
        .route("CompleteMultipartUpload", CompleteMultipartUpload)
        .route("CopyObject", CopyObject)
        .route("CreateMultipartUpload", CreateMultipartUpload)
        .route("DeleteObject", DeleteObject)
        .route("DeleteObjectTagging", DeleteObjectTagging)
        .route("PutObject", PutObject)
        .route("PutObjectAcl", PutObjectAcl)
        .route("PutObjectLegalHold", PutObjectLegalHold)
        .route("PutObjectLockConfiguration", PutObjectLockConfiguration)
        .route("PutObjectRetention", PutObjectRetention)
        .route("PutObjectTagging", PutObjectTagging)
        .route("RestoreObject", RestoreObject)
        .route("SelectObjectContent", SelectObjectContent)
        .route("UploadPart", UploadPart)
        .route("UploadPartCopy", UploadPartCopy)
        .route("WriteGetObjectResponse", WriteGetObjectResponse)
        ;
  }

  /**
   * The <a href="https://docs.aws.amazon.com/AmazonS3/latest/userguide/s3-vectors.html">S3 Vectors</a>
   * operations, which are posted to a path of their own rather than addressed at a bucket or an object.
   */
  private static void vectorRoutes(LocalS3Router router, ServiceFactory serviceFactory) {

    Route CreateVectorBucket = Route.builder()
        .method(HttpMethod.POST)
        .path("/CreateVectorBucket")
        .handler(new com.robothy.s3.rest.handler.s3vectors.CreateVectorBucketController(serviceFactory))
        .build();
    Route GetVectorBucket = Route.builder()
        .method(HttpMethod.POST)
        .path("/GetVectorBucket")
        .handler(new com.robothy.s3.rest.handler.s3vectors.GetVectorBucketController(serviceFactory))
        .build();
    Route DeleteVectorBucket = Route.builder()
        .method(HttpMethod.POST)
        .path("/DeleteVectorBucket")
        .handler(new com.robothy.s3.rest.handler.s3vectors.DeleteVectorBucketController(serviceFactory))
        .build();
    Route ListVectorBuckets = Route.builder()
        .method(HttpMethod.POST)
        .path("/ListVectorBuckets")
        .handler(new com.robothy.s3.rest.handler.s3vectors.ListVectorBucketsController(serviceFactory))
        .build();
    Route PutVectorBucketPolicy = Route.builder()
        .method(HttpMethod.POST)
        .path("/PutVectorBucketPolicy")
        .handler(new com.robothy.s3.rest.handler.s3vectors.PutVectorBucketPolicyController(serviceFactory))
        .build();
    Route GetVectorBucketPolicy = Route.builder()
        .method(HttpMethod.POST)
        .path("/GetVectorBucketPolicy")
        .handler(new com.robothy.s3.rest.handler.s3vectors.GetVectorBucketPolicyController(serviceFactory))
        .build();
    Route DeleteVectorBucketPolicy = Route.builder()
        .method(HttpMethod.POST)
        .path("/DeleteVectorBucketPolicy")
        .handler(new com.robothy.s3.rest.handler.s3vectors.DeleteVectorBucketPolicyController(serviceFactory))
        .build();
    Route CreateIndex = Route.builder()
        .method(HttpMethod.POST)
        .path("/CreateIndex")
        .handler(new com.robothy.s3.rest.handler.s3vectors.CreateIndexController(serviceFactory))
        .build();
    Route GetIndex = Route.builder()
        .method(HttpMethod.POST)
        .path("/GetIndex")
        .handler(new com.robothy.s3.rest.handler.s3vectors.GetIndexController(serviceFactory))
        .build();
    Route ListIndexes = Route.builder()
        .method(HttpMethod.POST)
        .path("/ListIndexes")
        .handler(new com.robothy.s3.rest.handler.s3vectors.ListIndexesController(serviceFactory))
        .build();
    Route DeleteIndex = Route.builder()
        .method(HttpMethod.POST)
        .path("/DeleteIndex")
        .handler(new com.robothy.s3.rest.handler.s3vectors.DeleteIndexController(serviceFactory))
        .build();
    Route PutVectors = Route.builder()
        .method(HttpMethod.POST)
        .path("/PutVectors")
        .handler(new com.robothy.s3.rest.handler.s3vectors.PutVectorsController(serviceFactory))
        .build();
    Route QueryVectors = Route.builder()
        .method(HttpMethod.POST)
        .path("/QueryVectors")
        .handler(new com.robothy.s3.rest.handler.s3vectors.QueryVectorsController(serviceFactory))
        .build();
    Route GetVectors = Route.builder()
        .method(HttpMethod.POST)
        .path("/GetVectors")
        .handler(new com.robothy.s3.rest.handler.s3vectors.GetVectorsController(serviceFactory))
        .build();
    Route DeleteVectors = Route.builder()
        .method(HttpMethod.POST)
        .path("/DeleteVectors")
        .handler(new com.robothy.s3.rest.handler.s3vectors.DeleteVectorsController(serviceFactory))
        .build();
    Route ListVectors = Route.builder()
        .method(HttpMethod.POST)
        .path("/ListVectors")
        .handler(new com.robothy.s3.rest.handler.s3vectors.ListVectorsController(serviceFactory))
        .build();

    router
        .route("CreateVectorBucket", CreateVectorBucket)
        .route("GetVectorBucket", GetVectorBucket)
        .route("DeleteVectorBucket", DeleteVectorBucket)
        .route("ListVectorBuckets", ListVectorBuckets)
        .route("PutVectorBucketPolicy", PutVectorBucketPolicy)
        .route("GetVectorBucketPolicy", GetVectorBucketPolicy)
        .route("DeleteVectorBucketPolicy", DeleteVectorBucketPolicy)
        .route("CreateIndex", CreateIndex)
        .route("GetIndex", GetIndex)
        .route("ListIndexes", ListIndexes)
        .route("DeleteIndex", DeleteIndex)
        .route("PutVectors", PutVectors)
        .route("QueryVectors", QueryVectors)
        .route("GetVectors", GetVectors)
        .route("DeleteVectors", DeleteVectors)
        .route("ListVectors", ListVectors)
        ;
  }

}
