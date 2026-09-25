package com.robothy.s3.rest.handler;

import static com.robothy.s3.core.model.IdentifiedBucketConfiguration.ANALYTICS;
import static com.robothy.s3.core.model.IdentifiedBucketConfiguration.INTELLIGENT_TIERING;
import static com.robothy.s3.core.model.IdentifiedBucketConfiguration.INVENTORY;
import static com.robothy.s3.core.model.IdentifiedBucketConfiguration.METRICS;
import static com.robothy.s3.core.model.StoredBucketConfiguration.ACCELERATE;
import static com.robothy.s3.core.model.StoredBucketConfiguration.LOGGING;
import static com.robothy.s3.core.model.StoredBucketConfiguration.OWNERSHIP_CONTROLS;
import static com.robothy.s3.core.model.StoredBucketConfiguration.REQUEST_PAYMENT;
import static com.robothy.s3.core.model.StoredBucketConfiguration.WEBSITE;
import static com.robothy.s3.rest.handler.LocalS3Router.BUCKET_KEY_PATH;
import static com.robothy.s3.rest.handler.LocalS3Router.BUCKET_PATH;
import static com.robothy.s3.rest.handler.LocalS3Router.HEALTH_CHECK_PATH;
import static com.robothy.s3.rest.handler.LocalS3Router.VECTOR_RESOURCE_TAGS_PATH;
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
import com.robothy.s3.core.service.ObjectService;
import com.robothy.s3.rest.LocalS3Config;
import com.robothy.s3.rest.LocalS3Website;
import com.robothy.s3.rest.admin.LocalS3Admin;
import com.robothy.s3.rest.constants.AmzHeaderNames;
import com.robothy.s3.core.iceberg.IcebergCatalogService;
import com.robothy.s3.rest.handler.iceberg.IcebergCatalogController;
import com.robothy.s3.rest.handler.iceberg.IcebergClientConfig;
import com.robothy.s3.core.s3tables.S3TablesService;
import com.robothy.s3.rest.handler.s3tables.S3TablesController;
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
import com.robothy.s3.rest.handler.s3vectors.ListTagsForResourceController;
import com.robothy.s3.rest.handler.s3vectors.ListVectorBucketsController;
import com.robothy.s3.rest.handler.s3vectors.ListVectorsController;
import com.robothy.s3.rest.handler.s3vectors.LocalS3VectorExceptionHandler;
import com.robothy.s3.rest.handler.s3vectors.PutVectorBucketPolicyController;
import com.robothy.s3.rest.handler.s3vectors.PutVectorsController;
import com.robothy.s3.rest.handler.s3vectors.QueryVectorsController;
import com.robothy.s3.rest.handler.s3vectors.TagResourceController;
import com.robothy.s3.rest.handler.s3vectors.UntagResourceController;
import com.robothy.s3.rest.service.ServiceFactory;
import com.robothy.s3.rest.utils.VirtualHostParser;
import io.netty.handler.codec.http.HttpMethod;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import tools.jackson.core.JacksonException;

public class LocalS3RouterFactory {

  /**
   * The operations whose requests aren't worth recording in the statistics of the requests: the health check, which a
   * probe requests every few seconds, the administration endpoints, which would record themselves, and the console,
   * which would report the browsing of a user as traffic of the application under test.
   */
  public static final Set<String> UNRECORDED_OPERATIONS = Stream.concat(
      Stream.of("HealthCheck", "HeadHealthCheck", AdminController.STATS_OPERATION,
          AdminController.REQUESTS_OPERATION, AdminController.RESET_OPERATION, AdminController.LIFECYCLE_OPERATION,
          AdminController.SNIPPETS_OPERATION, AdminController.SNIPPET_OPERATION),
      ConsoleController.OPERATIONS.stream()).collect(Collectors.toUnmodifiableSet());

  /**
   * How long the temporary credentials of the credentials route of an Iceberg table are valid for: the default of
   * {@code AssumeRole}. The {@code S3FileIO} of Iceberg takes new ones five minutes before they expire.
   */
  private static final Duration ICEBERG_CREDENTIALS_DURATION = Duration.ofHours(1);

  /**
   * The operations that LocalS3 routes but doesn't implement. Each of them answers {@code 501 NotImplemented} with an
   * error that names the operation, so that a client fails clearly instead of appearing to succeed. Operations whose
   * requests are the same, e.g. {@code GetBucketNotification} and {@code GetBucketNotificationConfiguration}, share one
   * route.
   */
  private static final List<NotImplementedOperation> NOT_IMPLEMENTED_OPERATIONS = List.of(
      // Object retrieval and transformation.
      new NotImplementedOperation("GetObjectTorrent", GET, BUCKET_KEY_PATH, has("torrent")),
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
        ? new CorsResponseHeaders(serviceFactory.getInstance(BucketService.class),
            CorsResponseHeaders.defaultConfiguration(serviceFactory))
        : null;
    // The temporary credentials of the STS endpoint are verified with a key derived from the secret access key, so that
    // they remain valid across restarts.
    SessionCredentialIssuer sessionCredentialIssuer = new SessionCredentialIssuer(secretAccessKey, Clock.systemUTC());
    AwsSignatureV4Verifier signatureVerifier = accessKeyId == null ? null
        : new AwsSignatureV4Verifier(accessKeyId, secretAccessKey, sessionCredentialIssuer, Clock.systemUTC());
    LocalS3Router router = new LocalS3Router(signatureVerifier, virtualHostParser, corsResponseHeaders)
        .sts(new StsController(sessionCredentialIssuer))
        .kms(new KmsController())
        // Only a service that was configured with an Iceberg catalog has one registered, and only it serves the
        // routes: without one, /iceberg/... stays an ordinary bucket path.
        .iceberg(icebergController(serviceFactory, sessionCredentialIssuer))
        // Told apart from an S3 request by the service in its credential scope rather than by its path, which it
        // shares with the S3 routes; see S3TablesController.
        .s3Tables(s3TablesController(serviceFactory))
        .website(websiteController(serviceFactory))
        // The console reads the same services the S3 operations do; a service without them, e.g. a router of
        // handlers alone, serves none.
        .console(consoleController(serviceFactory, accessKeyId, secretAccessKey));

    Routes routes = new Routes(router);
    SharedControllers shared = SharedControllers.create(serviceFactory);
    serviceRoutes(routes, serviceFactory);
    bucketReadRoutes(routes, serviceFactory, shared, sessionCredentialIssuer);
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
        .exceptionHandler(JacksonException.class, new MalformedRequestBodyExceptionHandler())
        .exceptionHandler(Exception.class, new ExceptionHandler());
  }

  /**
   * The controller of the Iceberg REST catalog of a service that serves one.
   *
   * @param serviceFactory the services of the service.
   * @param sessionCredentialIssuer the issuer of the STS endpoint, whose temporary credentials the credentials route of
   *     a table answers, so that they are verified like the ones of {@code AssumeRole}.
   * @return the controller; {@code null} if the service serves no catalog.
   */
  private static IcebergCatalogController icebergController(ServiceFactory serviceFactory,
                                                            SessionCredentialIssuer sessionCredentialIssuer) {
    if (!serviceFactory.containsInstance(IcebergCatalogService.class)) {
      return null;
    }
    IcebergClientConfig clientConfig = serviceFactory.containsInstance(IcebergClientConfig.class)
        ? serviceFactory.getInstance(IcebergClientConfig.class)
        : new IcebergClientConfig(IcebergClientConfig.DEFAULT_REGION, null, null, false, false);
    S3TablesService s3Tables = serviceFactory.containsInstance(S3TablesService.class)
        ? serviceFactory.getInstance(S3TablesService.class) : null;
    return new IcebergCatalogController(serviceFactory.getInstance(IcebergCatalogService.class), clientConfig,
        s3Tables, () -> {
          // The credentials act as the root of the account of LocalS3, like those of its GetSessionToken.
          SessionCredentialIssuer.SessionCredentials credentials = sessionCredentialIssuer.issue(
              ICEBERG_CREDENTIALS_DURATION, "arn:aws:iam::" + StsController.ACCOUNT + ":root", StsController.ACCOUNT);
          return new IcebergClientConfig.SessionCredentials(credentials.accessKeyId(), credentials.secretAccessKey(),
              credentials.sessionToken(), credentials.session().expiration());
        });
  }

  /**
   * The controller of the S3 Tables API of a service that holds table buckets.
   *
   * @param serviceFactory the services of the service.
   * @return the controller; {@code null} if the service serves no table buckets, e.g. a router of handlers alone.
   */
  private static S3TablesController s3TablesController(ServiceFactory serviceFactory) {
    return serviceFactory.containsInstance(S3TablesService.class)
        ? new S3TablesController(serviceFactory.getInstance(S3TablesService.class)) : null;
  }

  /**
   * The controller that serves the buckets of a service as static websites.
   *
   * @param serviceFactory the services of the service.
   * @return the controller; {@code null} if the service serves no website, or holds no data to serve one from, which
   *     is the case for a router of handlers alone.
   */
  private static StaticWebsiteController websiteController(ServiceFactory serviceFactory) {
    if (!serviceFactory.containsInstance(BucketService.class)
        || !serviceFactory.containsInstance(ObjectService.class)) {
      return null;
    }
    LocalS3Website website = serviceFactory.containsInstance(LocalS3Config.class)
        ? serviceFactory.getInstance(LocalS3Config.class).website()
        : LocalS3Website.defaults();
    return website.enabled() ? new StaticWebsiteController(serviceFactory, website) : null;
  }

  /**
   * The controller of the built-in console of a service.
   *
   * @param serviceFactory the services of the service.
   * @param accessKeyId the access key ID that the console asks for as the user name; {@code null} if the service
   *     has no credentials, which serves the console to every request.
   * @param secretAccessKey the secret access key of {@code accessKeyId}.
   * @return the controller; {@code null} if the service holds no data to show, which is the case for a router of
   *     handlers alone.
   */
  private static ConsoleController consoleController(ServiceFactory serviceFactory, String accessKeyId,
                                                     String secretAccessKey) {
    if (!serviceFactory.containsInstance(BucketService.class)
        || !serviceFactory.containsInstance(ObjectService.class)) {
      return null;
    }
    return new ConsoleController(serviceFactory, accessKeyId, secretAccessKey);
  }

  /**
   * The controllers that routes of more than one group share, so that the operations of one resource, e.g.
   * the three of a bucket policy, are answered through the same instance rather than through one per route.
   */
  private record SharedControllers(BucketPolicyController bucketPolicy,
                                   BucketReplicationController bucketReplication,
                                   BucketEncryptionController bucketEncryption,
                                   BucketLifecycleController bucketLifecycle,
                                   BucketNotificationController bucketNotification,
                                   BucketStoredConfigurationController storedConfiguration,
                                   ObjectTaggingController objectTagging,
                                   ObjectLockController objectLock) {

    static SharedControllers create(ServiceFactory serviceFactory) {
      return new SharedControllers(new BucketPolicyController(serviceFactory),
          new BucketReplicationController(serviceFactory),
          new BucketEncryptionController(serviceFactory),
          new BucketLifecycleController(serviceFactory),
          new BucketNotificationController(serviceFactory),
          new BucketStoredConfigurationController(serviceFactory),
          new ObjectTaggingController(serviceFactory),
          new ObjectLockController(serviceFactory));
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
    // The default CORS rule of a service also answers the preflight requests that address no bucket.
    if (corsPreflight.hasDefaultConfiguration()) {
      routes.router().servicePreflight(corsPreflight);
    }

    // Only a running service has an administration; a router of handlers alone doesn't.
    if (serviceFactory.containsInstance(LocalS3Admin.class)) {
      AdminController admin = new AdminController(serviceFactory);
      routes
          .add(AdminController.STATS_OPERATION, GET, AdminController.STATS_PATH, admin::stats)
          .add(AdminController.REQUESTS_OPERATION, GET, AdminController.REQUESTS_PATH, admin::requests)
          .add(AdminController.RESET_OPERATION, POST, AdminController.RESET_PATH, admin::reset)
          .add(AdminController.LIFECYCLE_OPERATION, POST, AdminController.LIFECYCLE_PATH, admin::lifecycle)
          .add(AdminController.SNIPPETS_OPERATION, GET, AdminController.SNIPPETS_PATH, admin::snippets);
      // A path of its own per snippet, which is exact and therefore wins over GetObject of a bucket named _admin.
      for (String id : ConnectionSnippets.IDS) {
        routes.add(AdminController.SNIPPET_OPERATION, GET, AdminController.SNIPPETS_PATH + "/" + id,
            admin.snippet(id));
      }
    }
  }

  /**
   * The operations that read a bucket, its configuration or the list of buckets, i.e. the {@code GET}
   * and {@code HEAD} requests addressed at one. {@code CreateSession} of S3 Express One Zone is here as well: it is
   * a {@code GET} of a bucket, which answers credentials rather than reading the bucket.
   *
   * @param sessionCredentialIssuer issues the credentials of an S3 Express One Zone session.
   */
  private static void bucketReadRoutes(Routes routes, ServiceFactory factory, SharedControllers shared,
                                       SessionCredentialIssuer sessionCredentialIssuer) {
    routes
        .add("CreateSession", GET, BUCKET_PATH, has("session"),
            new CreateSessionController(sessionCredentialIssuer))
        .add("GetBucketAccelerateConfiguration", GET, BUCKET_PATH, has("accelerate"),
            shared.storedConfiguration().get(ACCELERATE))
        .add("GetBucketAcl", GET, BUCKET_PATH, has("acl"), new GetBucketAclController(factory))
        .add("GetBucketAnalyticsConfiguration", GET, BUCKET_PATH, has("analytics", "id"),
            shared.storedConfiguration().get(ANALYTICS))
        .add("GetBucketCors", GET, BUCKET_PATH, has("cors"), new GetBucketCorsController(factory))
        .add("GetBucketEncryption", GET, BUCKET_PATH, has("encryption"), shared.bucketEncryption()::get)
        .add("GetBucketIntelligentTieringConfiguration", GET, BUCKET_PATH, has("intelligent-tiering", "id"),
            shared.storedConfiguration().get(INTELLIGENT_TIERING))
        .add("GetBucketInventoryConfiguration", GET, BUCKET_PATH, has("inventory", "id"),
            shared.storedConfiguration().get(INVENTORY))
        .add("GetBucketLifecycleConfiguration", GET, BUCKET_PATH, has("lifecycle"), shared.bucketLifecycle()::get)
        .add("GetBucketLocation", GET, BUCKET_PATH, has("location"), new GetBucketLocationController(factory))
        .add("GetBucketLogging", GET, BUCKET_PATH, has("logging"), shared.storedConfiguration().get(LOGGING))
        .add("GetBucketMetricsConfiguration", GET, BUCKET_PATH, has("metrics", "id"),
            shared.storedConfiguration().get(METRICS))
        .add("GetBucketNotificationConfiguration", GET, BUCKET_PATH, has("notification"),
            shared.bucketNotification()::get)
        .add("GetObjectLockConfiguration", GET, BUCKET_PATH, has("object-lock"), shared.objectLock()::getConfiguration)
        .add("GetBucketOwnershipControls", GET, BUCKET_PATH, has("ownershipControls"),
            shared.storedConfiguration().get(OWNERSHIP_CONTROLS))
        .add("GetBucketPolicy", GET, BUCKET_PATH, has("policy"), shared.bucketPolicy()::get)
        .add("GetBucketPolicyStatus", GET, BUCKET_PATH, has("policyStatus"),
            new GetBucketPolicyStatusController(factory))
        .add("GetBucketReplication", GET, BUCKET_PATH, has("replication"), shared.bucketReplication()::get)
        .add("GetBucketRequestPayment", GET, BUCKET_PATH, has("requestPayment"),
            shared.storedConfiguration().get(REQUEST_PAYMENT))
        .add("GetBucketTagging", GET, BUCKET_PATH, has("tagging"), new GetBucketTaggingController(factory))
        .add("GetBucketVersioning", GET, BUCKET_PATH, has("versioning"), new GetBucketVersioningController(factory))
        .add("GetBucketWebsite", GET, BUCKET_PATH, has("website"), shared.storedConfiguration().get(WEBSITE))
        .add("GetPublicAccessBlock", GET, BUCKET_PATH, has("publicAccessBlock"),
            new GetPublicAccessBlockController(factory))
        .add("HeadBucket", HEAD, BUCKET_PATH, new HeadBucketController(factory))
        .add("ListBucketAnalyticsConfigurations", GET, BUCKET_PATH, has("analytics").andHasNot("id"),
            shared.storedConfiguration().list(ANALYTICS))
        .add("ListBucketIntelligentTieringConfigurations", GET, BUCKET_PATH,
            has("intelligent-tiering").andHasNot("id"), shared.storedConfiguration().list(INTELLIGENT_TIERING))
        .add("ListBucketInventoryConfigurations", GET, BUCKET_PATH, has("inventory").andHasNot("id"),
            shared.storedConfiguration().list(INVENTORY))
        .add("ListBucketMetricsConfigurations", GET, BUCKET_PATH, has("metrics").andHasNot("id"),
            shared.storedConfiguration().list(METRICS))
        .add("ListBuckets", GET, "/", new ListBucketsController(factory))
        // A request without the parameter is told apart by its signing name, see LocalS3Router#match.
        .add(ListDirectoryBucketsController.OPERATION, GET, "/", has("max-directory-buckets"),
            new ListDirectoryBucketsController(factory))
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
        .add("DeleteBucketAnalyticsConfiguration", DELETE, BUCKET_PATH, has("analytics"),
            shared.storedConfiguration().delete(ANALYTICS))
        .add("DeleteBucketCors", DELETE, BUCKET_PATH, has("cors"), new DeleteBucketCorsController(factory))
        .add("DeleteBucketEncryption", DELETE, BUCKET_PATH, has("encryption"), shared.bucketEncryption()::delete)
        .add("DeleteBucketIntelligentTieringConfiguration", DELETE, BUCKET_PATH, has("intelligent-tiering"),
            shared.storedConfiguration().delete(INTELLIGENT_TIERING))
        .add("DeleteBucketInventoryConfiguration", DELETE, BUCKET_PATH, has("inventory"),
            shared.storedConfiguration().delete(INVENTORY))
        .add("DeleteBucketLifecycle", DELETE, BUCKET_PATH, has("lifecycle"), shared.bucketLifecycle()::delete)
        .add("DeleteBucketMetricsConfiguration", DELETE, BUCKET_PATH, has("metrics"),
            shared.storedConfiguration().delete(METRICS))
        .add("DeleteBucketOwnershipControls", DELETE, BUCKET_PATH, has("ownershipControls"),
            shared.storedConfiguration().delete(OWNERSHIP_CONTROLS))
        .add("DeleteBucketPolicy", DELETE, BUCKET_PATH, has("policy"), shared.bucketPolicy()::delete)
        .add("DeleteBucketReplication", DELETE, BUCKET_PATH, has("replication"), shared.bucketReplication()::delete)
        .add("DeleteBucketTagging", DELETE, BUCKET_PATH, has("tagging"), new DeleteBucketTaggingController(factory))
        .add("DeleteBucketWebsite", DELETE, BUCKET_PATH, has("website"), shared.storedConfiguration().delete(WEBSITE))
        .add("DeleteObjects", POST, BUCKET_PATH, has("delete"), new DeleteObjectsController(factory))
        .add("DeletePublicAccessBlock", DELETE, BUCKET_PATH, has("publicAccessBlock"),
            new DeletePublicAccessBlockController(factory))
        .add(PostObjectController.OPERATION, POST, BUCKET_PATH, new PostObjectController(factory, signatureVerifier))
        .add("PutBucketAccelerateConfiguration", PUT, BUCKET_PATH, has("accelerate"),
            shared.storedConfiguration().put(ACCELERATE))
        .add("PutBucketAcl", PUT, BUCKET_PATH, has("acl"), new PutBucketAclController(factory))
        .add("PutBucketAnalyticsConfiguration", PUT, BUCKET_PATH, has("analytics"),
            shared.storedConfiguration().put(ANALYTICS))
        .add("PutBucketCors", PUT, BUCKET_PATH, has("cors"), new PutBucketCorsController(factory))
        .add("PutBucketEncryption", PUT, BUCKET_PATH, has("encryption"), shared.bucketEncryption()::put)
        .add("PutBucketIntelligentTieringConfiguration", PUT, BUCKET_PATH, has("intelligent-tiering"),
            shared.storedConfiguration().put(INTELLIGENT_TIERING))
        .add("PutBucketInventoryConfiguration", PUT, BUCKET_PATH, has("inventory"),
            shared.storedConfiguration().put(INVENTORY))
        .add("PutBucketLifecycleConfiguration", PUT, BUCKET_PATH, has("lifecycle"), shared.bucketLifecycle()::put)
        .add("PutBucketLogging", PUT, BUCKET_PATH, has("logging"), shared.storedConfiguration().put(LOGGING))
        .add("PutBucketMetricsConfiguration", PUT, BUCKET_PATH, has("metrics"),
            shared.storedConfiguration().put(METRICS))
        .add("PutBucketNotificationConfiguration", PUT, BUCKET_PATH, has("notification"),
            shared.bucketNotification()::put)
        .add("PutBucketOwnershipControls", PUT, BUCKET_PATH, has("ownershipControls"),
            shared.storedConfiguration().put(OWNERSHIP_CONTROLS))
        .add("PutBucketPolicy", PUT, BUCKET_PATH, has("policy"), shared.bucketPolicy()::put)
        .add("PutBucketReplication", PUT, BUCKET_PATH, has("replication"), shared.bucketReplication()::put)
        .add("PutBucketRequestPayment", PUT, BUCKET_PATH, has("requestPayment"),
            shared.storedConfiguration().put(REQUEST_PAYMENT))
        .add("PutBucketTagging", PUT, BUCKET_PATH, has("tagging"), new PutBucketTaggingController(factory))
        .add("PutBucketVersioning", PUT, BUCKET_PATH, has("versioning"), new PutBucketVersioningController(factory))
        .add("PutBucketWebsite", PUT, BUCKET_PATH, has("website"), shared.storedConfiguration().put(WEBSITE))
        .add("PutObjectLockConfiguration", PUT, BUCKET_PATH, has("object-lock"), shared.objectLock()::putConfiguration)
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
        .add("GetObjectLegalHold", GET, BUCKET_KEY_PATH, has("legal-hold"), shared.objectLock()::getLegalHold)
        .add("GetObjectRetention", GET, BUCKET_KEY_PATH, has("retention"), shared.objectLock()::getRetention)
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
        .add("PutObjectLegalHold", PUT, BUCKET_KEY_PATH, has("legal-hold"), shared.objectLock()::putLegalHold)
        .add("PutObjectRetention", PUT, BUCKET_KEY_PATH, has("retention"), shared.objectLock()::putRetention)
        .add("PutObjectTagging", PUT, BUCKET_KEY_PATH, has("tagging"), shared.objectTagging()::put)
        .add("RenameObject", PUT, BUCKET_KEY_PATH, has("renameObject"), new RenameObjectController(factory))
        .add("RestoreObject", POST, BUCKET_KEY_PATH, has("restore"), new RestoreObjectController(factory))
        .add("UploadPart", PUT, BUCKET_KEY_PATH, has("uploadId", "partNumber"), new UploadPartController(factory))
        .add("UploadPartCopy", PUT, BUCKET_KEY_PATH, has("uploadId", "partNumber"), copySource,
            new UploadPartCopyController(factory));
  }

  /**
   * The <a href="https://docs.aws.amazon.com/AmazonS3/latest/userguide/s3-vectors.html">S3 Vectors</a>
   * operations, each of which is posted to a path named after it rather than addressed at a bucket or an object, except
   * the tagging operations, which address a vector bucket or an index by its ARN.
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
        .addVectorOperation("ListVectors", new ListVectorsController(factory))
        .add("TagResource", POST, VECTOR_RESOURCE_TAGS_PATH, new TagResourceController(factory))
        .add("UntagResource", DELETE, VECTOR_RESOURCE_TAGS_PATH, new UntagResourceController(factory))
        .add("ListTagsForResource", GET, VECTOR_RESOURCE_TAGS_PATH, new ListTagsForResourceController(factory));
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
