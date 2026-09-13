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
   * method and the path of a route, so that every route of one (method, path) is registered by one method,
   * in one place, in one order. Keep it that way: the router keeps the LAST candidate of the highest
   * priority, so two routes of the same (method, path) whose matchers both accept a request are decided by
   * the order they were registered in, and moving one across methods would move it within its group.
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

    return router
        .notFound(new NotFoundHandler())
        .exceptionHandler(LocalS3Exception.class, new LocalS3ExceptionHandler(serviceFactory))
        .exceptionHandler(IllegalArgumentException.class, new IllegalArgumentExceptionHandler())
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
        .route(HealthCheck)
        .route(HeadHealthCheck)
        .route(BucketCorsPreflight)
        .route(ObjectCorsPreflight)
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
        .paramMatcher(params -> params.containsKey("accelerate"))
        .handler(new NotImplementedOperationController(serviceFactory, "GetBucketAccelerateConfiguration"))
        .build();
    Route GetBucketAcl = Route.builder()
        .method(HttpMethod.GET)
        .path(BUCKET_PATH)
        .paramMatcher(params -> params.containsKey("acl"))
        .handler(new GetBucketAclController(serviceFactory))
        .build();
    Route GetBucketAnalyticsConfiguration = Route.builder()
        .method(HttpMethod.GET)
        .path(BUCKET_PATH)
        .paramMatcher(params -> params.containsKey("analytics"))
        .handler(new NotImplementedOperationController(serviceFactory, "GetBucketAnalyticsConfiguration"))
        .build();
    Route GetBucketCors = Route.builder()
        .method(HttpMethod.GET)
        .path(BUCKET_PATH)
        .paramMatcher(params -> params.containsKey("cors"))
        .handler(new GetBucketCorsController(serviceFactory))
        .build();
    Route GetBucketEncryption = Route.builder()
        .method(HttpMethod.GET)
        .path(BUCKET_PATH)
        .paramMatcher(params -> params.containsKey("encryption"))
        .handler(shared.bucketEncryption()::get)
        .build();
    Route GetBucketIntelligentTieringConfiguration = Route.builder()
        .method(HttpMethod.GET)
        .path(BUCKET_PATH)
        .paramMatcher(params -> params.containsKey("intelligent-tiering"))
        .handler(new NotImplementedOperationController(serviceFactory, "GetBucketIntelligentTieringConfiguration"))
        .build();
    Route GetBucketInventoryConfiguration = Route.builder()
        .method(HttpMethod.GET)
        .path(BUCKET_PATH)
        .paramMatcher(params -> params.containsKey("inventory"))
        .handler(new NotImplementedOperationController(serviceFactory, "GetBucketInventoryConfiguration"))
        .build();
    Route GetBucketLifecycle = Route.builder()
        .method(HttpMethod.GET)
        .path(BUCKET_PATH)
        .paramMatcher(params -> params.containsKey("lifecycle"))
        .handler(new NotImplementedOperationController(serviceFactory, "GetBucketLifecycle"))
        .build();
    Route GetBucketLifecycleConfiguration = Route.builder()
        .method(HttpMethod.GET)
        .path(BUCKET_PATH)
        .paramMatcher(params -> params.containsKey("lifecycle"))
        .handler(new NotImplementedOperationController(serviceFactory, "GetBucketLifecycleConfiguration"))
        .build();
    Route GetBucketLocation = Route.builder()
        .method(HttpMethod.GET)
        .path(BUCKET_PATH)
        .paramMatcher(params -> params.containsKey("location"))
        .handler(new GetBucketLocationController(serviceFactory))
        .build();
    Route GetBucketLogging = Route.builder()
        .method(HttpMethod.GET)
        .path(BUCKET_PATH)
        .paramMatcher(params -> params.containsKey("logging"))
        .handler(new NotImplementedOperationController(serviceFactory, "GetBucketLogging"))
        .build();
    Route GetBucketMetricsConfiguration = Route.builder()
        .method(HttpMethod.GET)
        .path(BUCKET_PATH)
        .paramMatcher(params -> params.containsKey("metrics"))
        .handler(new NotImplementedOperationController(serviceFactory, "GetBucketMetricsConfiguration"))
        .build();
    Route GetBucketNotification = Route.builder()
        .method(HttpMethod.GET)
        .path(BUCKET_PATH)
        .paramMatcher(params -> params.containsKey("notification"))
        .handler(new NotImplementedOperationController(serviceFactory, "GetBucketNotification"))
        .build();
    Route GetBucketNotificationConfiguration = Route.builder()
        .method(HttpMethod.GET)
        .path(BUCKET_PATH)
        .paramMatcher(params -> params.containsKey("notification"))
        .handler(new NotImplementedOperationController(serviceFactory, "GetBucketNotificationConfiguration"))
        .build();
    Route GetBucketOwnershipControls = Route.builder()
        .method(HttpMethod.GET)
        .path(BUCKET_PATH)
        .paramMatcher(params -> params.containsKey("ownershipControls"))
        .handler(new NotImplementedOperationController(serviceFactory, "GetBucketOwnershipControls"))
        .build();
    Route GetBucketPolicy = Route.builder()
        .method(HttpMethod.GET)
        .path(BUCKET_PATH)
        .paramMatcher(params -> params.containsKey("policy"))
        .handler(shared.bucketPolicy()::get)
        .build();
    Route GetBucketPolicyStatus = Route.builder()
        .method(HttpMethod.GET)
        .path(BUCKET_PATH)
        .paramMatcher(params -> params.containsKey("policyStatus"))
        .handler(new GetBucketPolicyStatusController(serviceFactory))
        .build();
    Route GetBucketReplication = Route.builder()
        .method(HttpMethod.GET)
        .path(BUCKET_PATH)
        .paramMatcher(params -> params.containsKey("replication"))
        .handler(shared.bucketReplication()::get)
        .build();
    Route GetBucketRequestPayment = Route.builder()
        .method(HttpMethod.GET)
        .path(BUCKET_PATH)
        .paramMatcher(params -> params.containsKey("requestPayment"))
        .handler(new NotImplementedOperationController(serviceFactory, "GetBucketRequestPayment"))
        .build();
    Route GetBucketTagging = Route.builder()
        .method(HttpMethod.GET)
        .path(BUCKET_PATH)
        .paramMatcher(params -> params.containsKey("tagging"))
        .handler(new GetBucketTaggingController(serviceFactory))
        .build();
    Route GetBucketVersioning = Route.builder()
        .method(HttpMethod.GET)
        .path(BUCKET_PATH)
        .paramMatcher(params -> params.containsKey("versioning"))
        .handler(new GetBucketVersioningController(serviceFactory))
        .build();
    Route GetBucketWebsite = Route.builder()
        .method(HttpMethod.GET)
        .path(BUCKET_PATH)
        .paramMatcher(params -> params.containsKey("website"))
        .handler(new NotImplementedOperationController(serviceFactory, "GetBucketWebsite"))
        .build();
    Route GetPublicAccessBlock = Route.builder()
        .method(HttpMethod.GET)
        .path(BUCKET_PATH)
        .paramMatcher(params -> params.containsKey("publicAccessBlock"))
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
        .paramMatcher(params -> params.containsKey("analytics"))
        .handler(new NotImplementedOperationController(serviceFactory, "ListBucketAnalyticsConfigurations"))
        .build();
    Route ListBucketIntelligentTieringConfigurations = Route.builder()
        .method(HttpMethod.GET)
        .path(BUCKET_PATH)
        .paramMatcher(params -> params.containsKey("intelligent-tiering"))
        .handler(new NotImplementedOperationController(serviceFactory, "ListBucketIntelligentTieringConfigurations"))
        .build();
    Route ListBucketInventoryConfigurations = Route.builder()
        .method(HttpMethod.GET)
        .path(BUCKET_PATH)
        .paramMatcher(params -> params.containsKey("inventory"))
        .handler(new NotImplementedOperationController(serviceFactory, "ListBucketInventoryConfigurations"))
        .build();
    Route ListBucketMetricsConfigurations = Route.builder()
        .method(HttpMethod.GET)
        .path(BUCKET_PATH)
        .paramMatcher(params -> params.containsKey("metrics"))
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
        .paramMatcher(params -> params.containsKey("uploads"))
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
        .paramMatcher(params -> params.containsKey("list-type") && params.get("list-type").get(0).equals("2"))
        .handler(new ListObjectsV2Controller(serviceFactory))
        .build();
    Route ListObjectVersions = Route.builder()
        .method(HttpMethod.GET)
        .path(BUCKET_PATH)
        .paramMatcher(params -> params.containsKey("versions"))
        .handler(new ListObjectVersionsController(serviceFactory))
        .build();

    router
        .route(GetBucketAccelerateConfiguration)
        .route(GetBucketAcl)
        .route(GetBucketAnalyticsConfiguration)
        .route(GetBucketCors)
        .route(GetBucketEncryption)
        .route(GetBucketIntelligentTieringConfiguration)
        .route(GetBucketInventoryConfiguration)
        .route(GetBucketLifecycle)
        .route(GetBucketLifecycleConfiguration)
        .route(GetBucketLocation)
        .route(GetBucketLogging)
        .route(GetBucketMetricsConfiguration)
        .route(GetBucketNotification)
        .route(GetBucketNotificationConfiguration)
        .route(GetBucketOwnershipControls)
        .route(GetBucketPolicy)
        .route(GetBucketPolicyStatus)
        .route(GetBucketReplication)
        .route(GetBucketRequestPayment)
        .route(GetBucketTagging)
        .route(GetBucketVersioning)
        .route(GetBucketWebsite)
        .route(GetPublicAccessBlock)
        .route(HeadBucket)
        .route(ListBucketAnalyticsConfigurations)
        .route(ListBucketIntelligentTieringConfigurations)
        .route(ListBucketInventoryConfigurations)
        .route(ListBucketMetricsConfigurations)
        .route(ListBuckets)
        .route(ListMultipartUploads)
        .route(ListObjects)
        .route(ListObjectsV2)
        .route(ListObjectVersions)
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
        .paramMatcher(params -> params.containsKey("analytics"))
        .handler(new NotImplementedOperationController(serviceFactory, "DeleteBucketAnalyticsConfiguration"))
        .build();
    Route DeleteBucketCors = Route.builder()
        .method(HttpMethod.DELETE)
        .path(BUCKET_PATH)
        .paramMatcher(params -> params.containsKey("cors"))
        .handler(new DeleteBucketCorsController(serviceFactory))
        .build();
    Route DeleteBucketEncryption = Route.builder()
        .method(HttpMethod.DELETE)
        .path(BUCKET_PATH)
        .paramMatcher(params -> params.containsKey("encryption"))
        .handler(shared.bucketEncryption()::delete)
        .build();
    Route DeleteBucketIntelligentTieringConfiguration = Route.builder()
        .method(HttpMethod.DELETE)
        .path(BUCKET_PATH)
        .paramMatcher(params -> params.containsKey("intelligent-tiering"))
        .handler(new NotImplementedOperationController(serviceFactory, "DeleteBucketIntelligentTieringConfiguration"))
        .build();
    Route DeleteBucketInventoryConfiguration = Route.builder()
        .method(HttpMethod.DELETE)
        .path(BUCKET_PATH)
        .paramMatcher(params -> params.containsKey("inventory"))
        .handler(new NotImplementedOperationController(serviceFactory, "DeleteBucketInventoryConfiguration"))
        .build();
    Route DeleteBucketLifecycle = Route.builder()
        .method(HttpMethod.DELETE)
        .path(BUCKET_PATH)
        .paramMatcher(params -> params.containsKey("lifecycle"))
        .handler(new NotImplementedOperationController(serviceFactory, "DeleteBucketLifecycle"))
        .build();
    Route DeleteBucketMetricsConfiguration = Route.builder()
        .method(HttpMethod.DELETE)
        .path(BUCKET_PATH)
        .paramMatcher(params -> params.containsKey("metrics"))
        .handler(new NotImplementedOperationController(serviceFactory, "DeleteBucketMetricsConfiguration"))
        .build();
    Route DeleteBucketOwnershipControls = Route.builder()
        .method(HttpMethod.DELETE)
        .path(BUCKET_PATH)
        .paramMatcher(params -> params.containsKey("ownershipControls"))
        .handler(new NotImplementedOperationController(serviceFactory, "DeleteBucketOwnershipControls"))
        .build();
    Route DeleteBucketPolicy = Route.builder()
        .method(HttpMethod.DELETE)
        .path(BUCKET_PATH)
        .paramMatcher(params -> params.containsKey("policy"))
        .handler(shared.bucketPolicy()::delete)
        .build();
    Route DeleteBucketReplication = Route.builder()
        .method(HttpMethod.DELETE)
        .path(BUCKET_PATH)
        .paramMatcher(params -> params.containsKey("replication"))
        .handler(shared.bucketReplication()::delete)
        .build();
    Route DeleteBucketTagging = Route.builder()
        .method(HttpMethod.DELETE)
        .path(BUCKET_PATH)
        .paramMatcher(params -> params.containsKey("tagging"))
        .handler(new DeleteBucketTaggingController(serviceFactory))
        .build();
    Route DeleteBucketWebsite = Route.builder()
        .method(HttpMethod.DELETE)
        .path(BUCKET_PATH)
        .paramMatcher(params -> params.containsKey("website"))
        .handler(new NotImplementedOperationController(serviceFactory, "DeleteBucketWebsite"))
        .build();
    Route DeleteObjects = Route.builder()
        .method(HttpMethod.POST)
        .path(BUCKET_PATH)
        .paramMatcher(params -> params.containsKey("delete"))
        .handler(new DeleteObjectsController(serviceFactory))
        .build();
    Route DeletePublicAccessBlock = Route.builder()
        .method(HttpMethod.DELETE)
        .path(BUCKET_PATH)
        .paramMatcher(params -> params.containsKey("publicAccessBlock"))
        .handler(new DeletePublicAccessBlockController(serviceFactory))
        .build();
    Route PutBucketAccelerateConfiguration = Route.builder()
        .method(HttpMethod.PUT)
        .path(BUCKET_PATH)
        .paramMatcher(params -> params.containsKey("accelerate"))
        .handler(new NotImplementedOperationController(serviceFactory, "PutBucketAccelerateConfiguration"))
        .build();
    Route PutBucketAcl = Route.builder()
        .method(HttpMethod.PUT)
        .path(BUCKET_PATH)
        .paramMatcher(params -> params.containsKey("acl"))
        .handler(new PutBucketAclController(serviceFactory))
        .build();
    Route PutBucketAnalyticsConfiguration = Route.builder()
        .method(HttpMethod.PUT)
        .path(BUCKET_PATH)
        .paramMatcher(params -> params.containsKey("analytics"))
        .handler(new NotImplementedOperationController(serviceFactory, "PutBucketAnalyticsConfiguration"))
        .build();
    Route PutBucketCors = Route.builder()
        .method(HttpMethod.PUT)
        .path(BUCKET_PATH)
        .paramMatcher(params -> params.containsKey("cors"))
        .handler(new PutBucketCorsController(serviceFactory))
        .build();
    Route PutBucketEncryption = Route.builder().method(HttpMethod.PUT)
        .path(BUCKET_PATH)
        .paramMatcher(params -> params.containsKey("encryption"))
        .handler(shared.bucketEncryption()::put)
        .build();
    Route PutBucketIntelligentTieringConfiguration = Route.builder()
        .method(HttpMethod.PUT)
        .path(BUCKET_PATH)
        .paramMatcher(params -> params.containsKey("intelligent-tiering"))
        .handler(new NotImplementedOperationController(serviceFactory, "PutBucketIntelligentTieringConfiguration"))
        .build();
    Route PutBucketInventoryConfiguration = Route.builder()
        .method(HttpMethod.PUT)
        .path(BUCKET_PATH)
        .paramMatcher(params -> params.containsKey("inventory"))
        .handler(new NotImplementedOperationController(serviceFactory, "PutBucketInventoryConfiguration"))
        .build();
    Route PutBucketLifecycle = Route.builder()
        .method(HttpMethod.PUT)
        .path(BUCKET_PATH)
        .paramMatcher(params -> params.containsKey("lifecycle"))
        .handler(new NotImplementedOperationController(serviceFactory, "PutBucketLifecycle"))
        .build();
    Route PutBucketLifecycleConfiguration = Route.builder()
        .method(HttpMethod.PUT)
        .path(BUCKET_PATH)
        .paramMatcher(params -> params.containsKey("lifecycle"))
        .handler(new NotImplementedOperationController(serviceFactory, "PutBucketLifecycleConfiguration"))
        .build();
    Route PutBucketLogging = Route.builder()
        .method(HttpMethod.PUT)
        .path(BUCKET_PATH)
        .paramMatcher(params -> params.containsKey("logging"))
        .handler(new NotImplementedOperationController(serviceFactory, "PutBucketLogging"))
        .build();
    Route PutBucketMetricsConfiguration = Route.builder()
        .method(HttpMethod.PUT)
        .path(BUCKET_PATH)
        .paramMatcher(params -> params.containsKey("metrics"))
        .handler(new NotImplementedOperationController(serviceFactory, "PutBucketMetricsConfiguration"))
        .build();
    Route PutBucketNotification = Route.builder()
        .method(HttpMethod.PUT)
        .path(BUCKET_PATH)
        .paramMatcher(params -> params.containsKey("notification"))
        .handler(new NotImplementedOperationController(serviceFactory, "PutBucketNotification"))
        .build();
    Route PutBucketNotificationConfiguration = Route.builder()
        .method(HttpMethod.PUT)
        .path(BUCKET_PATH)
        .paramMatcher(params -> params.containsKey("notification"))
        .handler(new NotImplementedOperationController(serviceFactory, "PutBucketNotificationConfiguration"))
        .build();
    Route PutBucketOwnershipControls = Route.builder()
        .method(HttpMethod.PUT)
        .path(BUCKET_PATH)
        .paramMatcher(params -> params.containsKey("ownershipControls"))
        .handler(new NotImplementedOperationController(serviceFactory, "PutBucketOwnershipControls"))
        .build();
    Route PutBucketPolicy = Route.builder()
        .method(HttpMethod.PUT)
        .path(BUCKET_PATH)
        .paramMatcher(params -> params.containsKey("policy"))
        .handler(shared.bucketPolicy()::put)
        .build();
    Route PutBucketReplication = Route.builder().method(HttpMethod.PUT)
        .path(BUCKET_PATH)
        .paramMatcher(params -> params.containsKey("replication"))
        .handler(shared.bucketReplication()::put)
        .build();
    Route PutBucketRequestPayment = Route.builder()
        .method(HttpMethod.PUT)
        .path(BUCKET_PATH)
        .paramMatcher(params -> params.containsKey("requestPayment"))
        .handler(new NotImplementedOperationController(serviceFactory, "PutBucketRequestPayment"))
        .build();
    Route PutBucketTagging = Route.builder()
        .method(HttpMethod.PUT)
        .path(BUCKET_PATH)
        .paramMatcher(params -> params.containsKey("tagging"))
        .handler(new PutBucketTaggingController(serviceFactory))
        .build();
    Route PutBucketVersioning = Route.builder()
        .method(HttpMethod.PUT)
        .path(BUCKET_PATH)
        .paramMatcher(params -> params.containsKey("versioning"))
        .handler(new PutBucketVersioningController(serviceFactory))
        .build();
    Route PutBucketWebsite = Route.builder()
        .method(HttpMethod.PUT)
        .path(BUCKET_PATH)
        .paramMatcher(params -> params.containsKey("website"))
        .handler(new NotImplementedOperationController(serviceFactory, "PutBucketWebsite"))
        .build();
    Route PutPublicAccessBlock = Route.builder()
        .method(HttpMethod.PUT)
        .path(BUCKET_PATH)
        .paramMatcher(params -> params.containsKey("publicAccessBlock"))
        .handler(new PutPublicAccessBlockController(serviceFactory))
        .build();

    router
        .route(CreateBucket)
        .route(DeleteBucket)
        .route(DeleteBucketAnalyticsConfiguration)
        .route(DeleteBucketCors)
        .route(DeleteBucketEncryption)
        .route(DeleteBucketIntelligentTieringConfiguration)
        .route(DeleteBucketInventoryConfiguration)
        .route(DeleteBucketLifecycle)
        .route(DeleteBucketMetricsConfiguration)
        .route(DeleteBucketOwnershipControls)
        .route(DeleteBucketPolicy)
        .route(DeleteBucketReplication)
        .route(DeleteBucketTagging)
        .route(DeleteBucketWebsite)
        .route(DeleteObjects)
        .route(DeletePublicAccessBlock)
        .route(PutBucketAccelerateConfiguration)
        .route(PutBucketAcl)
        .route(PutBucketAnalyticsConfiguration)
        .route(PutBucketCors)
        .route(PutBucketEncryption)
        .route(PutBucketIntelligentTieringConfiguration)
        .route(PutBucketInventoryConfiguration)
        .route(PutBucketLifecycle)
        .route(PutBucketLifecycleConfiguration)
        .route(PutBucketLogging)
        .route(PutBucketMetricsConfiguration)
        .route(PutBucketNotification)
        .route(PutBucketNotificationConfiguration)
        .route(PutBucketOwnershipControls)
        .route(PutBucketPolicy)
        .route(PutBucketReplication)
        .route(PutBucketRequestPayment)
        .route(PutBucketTagging)
        .route(PutBucketVersioning)
        .route(PutBucketWebsite)
        .route(PutPublicAccessBlock)
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
        .headerMatcher(headers -> headers.keySet().stream()
            .anyMatch(name -> AmzHeaderNames.X_AMZ_OBJECT_ATTRIBUTES.equalsIgnoreCase(name.toString())))
        .handler(new GetObjectAttributesController(serviceFactory))
        .build();
    Route GetObject = Route.builder()
        .method(HttpMethod.GET)
        .path(BUCKET_KEY_PATH)
        .headerMatcher(headers -> headers.keySet().stream()
            .noneMatch(name -> AmzHeaderNames.X_AMZ_OBJECT_ATTRIBUTES.equalsIgnoreCase(name.toString())))
        .handler(new GetObjectController(serviceFactory))
        .build();
    Route GetObjectAcl = Route.builder()
        .method(HttpMethod.GET)
        .path(BUCKET_KEY_PATH)
        .paramMatcher(params -> params.containsKey("acl"))
        .handler(new GetObjectAclController(serviceFactory))
        .build();
    Route GetObjectLegalHold = Route.builder()
        .method(HttpMethod.GET)
        .path(BUCKET_KEY_PATH)
        .paramMatcher(params -> params.containsKey("legal-hold"))
        .handler(new NotImplementedOperationController(serviceFactory, "GetObjectLegalHold"))
        .build();
    Route GetObjectLockConfiguration = Route.builder()
        .method(HttpMethod.GET)
        .path(BUCKET_KEY_PATH)
        .paramMatcher(params -> params.containsKey("object-lock"))
        .handler(new NotImplementedOperationController(serviceFactory, "GetObjectLockConfiguration"))
        .build();
    Route GetObjectRetention = Route.builder()
        .method(HttpMethod.GET)
        .path(BUCKET_KEY_PATH)
        .paramMatcher(params -> params.containsKey("retention"))
        .handler(new NotImplementedOperationController(serviceFactory, "GetObjectRetention"))
        .build();
    Route GetObjectTagging = Route.builder()
        .method(HttpMethod.GET)
        .path(BUCKET_KEY_PATH)
        .paramMatcher(params -> params.containsKey("tagging"))
        .handler(shared.objectTagging()::get)
        .build();
    Route GetObjectTorrent = Route.builder()
        .method(HttpMethod.GET)
        .path(BUCKET_KEY_PATH)
        .paramMatcher(params -> params.containsKey("torrent"))
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
        .paramMatcher(params -> params.containsKey("uploadId"))
        .handler(new ListPartsController(serviceFactory))
        .build();

    router
        .route(GetObjectAttributes)
        .route(GetObject)
        .route(GetObjectAcl)
        .route(GetObjectLegalHold)
        .route(GetObjectLockConfiguration)
        .route(GetObjectRetention)
        .route(GetObjectTagging)
        .route(GetObjectTorrent)
        .route(HeadObject)
        .route(ListParts)
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
        .paramMatcher(params -> params.containsKey("uploadId"))
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
        .headerMatcher(headers -> headers.containsKey(AmzHeaderNames.X_AMZ_COPY_SOURCE))
        .handler(new CopyObjectController(serviceFactory))
        .build();
    Route CreateMultipartUpload = Route.builder()
        .method(HttpMethod.POST)
        .path(BUCKET_KEY_PATH)
        .paramMatcher(params -> params.containsKey("uploads"))
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
        .paramMatcher(params -> params.containsKey("tagging"))
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
        .paramMatcher(params -> params.containsKey("acl"))
        .handler(new PutObjectAclController(serviceFactory))
        .build();
    Route PutObjectLegalHold = Route.builder()
        .method(HttpMethod.PUT)
        .path(BUCKET_KEY_PATH)
        .paramMatcher(params -> params.containsKey("legal-hold"))
        .handler(new NotImplementedOperationController(serviceFactory, "PutObjectLegalHold"))
        .build();
    Route PutObjectLockConfiguration = Route.builder()
        .method(HttpMethod.PUT)
        .path(BUCKET_KEY_PATH)
        .paramMatcher(params -> params.containsKey("object-lock"))
        .handler(new NotImplementedOperationController(serviceFactory, "PutObjectLockConfiguration"))
        .build();
    Route PutObjectRetention = Route.builder()
        .method(HttpMethod.PUT)
        .path(BUCKET_KEY_PATH)
        .paramMatcher(params -> params.containsKey("retention"))
        .handler(new NotImplementedOperationController(serviceFactory, "PutObjectRetention"))
        .build();
    Route PutObjectTagging = Route.builder()
        .method(HttpMethod.PUT)
        .path(BUCKET_KEY_PATH)
        .paramMatcher(params -> params.containsKey("tagging"))
        .handler(shared.objectTagging()::put)
        .build();
    Route RestoreObject = Route.builder()
        .method(HttpMethod.POST)
        .path(BUCKET_KEY_PATH)
        .paramMatcher(params -> params.containsKey("restore"))
        .handler(new NotImplementedOperationController(serviceFactory, "RestoreObject"))
        .build();
    Route SelectObjectContent = Route.builder()
        .method(HttpMethod.POST)
        .path(BUCKET_KEY_PATH)
        .paramMatcher(params -> params.containsKey("select"))
        .handler(new NotImplementedOperationController(serviceFactory, "SelectObjectContent"))
        .build();
    Route UploadPart = Route.builder()
        .method(HttpMethod.PUT)
        .path(BUCKET_KEY_PATH)
        .paramMatcher(params -> params.containsKey("uploadId") && params.containsKey("partNumber"))
        .handler(new UploadPartController(serviceFactory))
        .build();
    Route UploadPartCopy = Route.builder()
        .method(HttpMethod.PUT)
        .path(BUCKET_KEY_PATH)
        .paramMatcher(params -> params.containsKey("uploadId") && params.containsKey("partNumber"))
        .headerMatcher(headers -> headers.containsKey(AmzHeaderNames.X_AMZ_COPY_SOURCE))
        .handler(new UploadPartCopyController(serviceFactory))
        .build();
    Route WriteGetObjectResponse = Route.builder()
        .method(HttpMethod.POST)
        .path("/WriteGetObjectResponse")
        .handler(new NotImplementedOperationController(serviceFactory, "WriteGetObjectResponse"))
        .build();

    router
        .route(AbortMultipartUpload)
        .route(CompleteMultipartUpload)
        .route(CopyObject)
        .route(CreateMultipartUpload)
        .route(DeleteObject)
        .route(DeleteObjectTagging)
        .route(PutObject)
        .route(PutObjectAcl)
        .route(PutObjectLegalHold)
        .route(PutObjectLockConfiguration)
        .route(PutObjectRetention)
        .route(PutObjectTagging)
        .route(RestoreObject)
        .route(SelectObjectContent)
        .route(UploadPart)
        .route(UploadPartCopy)
        .route(WriteGetObjectResponse)
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
        .route(CreateVectorBucket)
        .route(GetVectorBucket)
        .route(DeleteVectorBucket)
        .route(ListVectorBuckets)
        .route(PutVectorBucketPolicy)
        .route(GetVectorBucketPolicy)
        .route(DeleteVectorBucketPolicy)
        .route(CreateIndex)
        .route(GetIndex)
        .route(ListIndexes)
        .route(DeleteIndex)
        .route(PutVectors)
        .route(QueryVectors)
        .route(GetVectors)
        .route(DeleteVectors)
        .route(ListVectors)
        ;
  }

}
