package com.robothy.s3.rest.handler;

import com.robothy.netty.http.RouterHttpRequest;
import com.robothy.s3.core.util.S3ObjectUtils;
import com.robothy.netty.http.RouterHttpRequestHandler;
import com.robothy.netty.http.RouterHttpResponse;
import com.robothy.s3.core.exception.LocalS3Exception;
import com.robothy.s3.core.exception.vectors.LocalS3VectorException;
import com.robothy.s3.core.iceberg.IcebergCatalogException;
import com.robothy.s3.core.iceberg.IcebergCatalogService;
import com.robothy.s3.core.model.answers.ListObjectVersionsAns;
import com.robothy.s3.core.model.internal.ObjectChecksum;
import com.robothy.s3.core.model.internal.ObjectLock;
import com.robothy.s3.core.model.internal.ServerSideEncryption;
import com.robothy.s3.core.model.internal.SystemMetadata;
import com.robothy.s3.core.s3tables.S3TablesException;
import com.robothy.s3.core.s3tables.S3TablesService;
import com.robothy.s3.core.service.s3vectors.S3VectorsService;
import com.robothy.s3.datatypes.response.DeleteMarkerEntry;
import com.robothy.s3.datatypes.response.ObjectVersion;
import com.robothy.s3.datatypes.response.VersionItem;
import com.robothy.s3.rest.LocalS3Features;
import com.robothy.s3.core.model.Bucket;
import com.robothy.s3.core.model.answers.CompleteMultipartUploadAns;
import com.robothy.s3.core.model.answers.DeleteObjectAns;
import com.robothy.s3.core.model.answers.GetObjectAns;
import com.robothy.s3.core.model.answers.ListObjectsV2Ans;
import com.robothy.s3.core.model.answers.PutObjectAns;
import com.robothy.s3.core.model.answers.UploadPartAns;
import com.robothy.s3.core.model.request.CompleteMultipartUploadPartOption;
import com.robothy.s3.core.model.request.CreateMultipartUploadOptions;
import com.robothy.s3.core.model.request.GetObjectOptions;
import com.robothy.s3.core.model.request.PutObjectOptions;
import com.robothy.s3.core.model.request.UploadPartOptions;
import com.robothy.s3.core.service.BucketService;
import com.robothy.s3.core.service.ObjectService;
import com.robothy.s3.rest.LocalS3Config;
import com.robothy.s3.rest.constants.LocalS3Constants;
import com.robothy.s3.rest.model.request.DecodedAmzRequestBody;
import com.robothy.s3.rest.netty.RequestBodies;
import com.robothy.s3.rest.netty.StreamingRouterHttpResponse;
import com.robothy.s3.rest.service.BucketNameValidator;
import com.robothy.s3.rest.service.MultipartUploadPolicy;
import com.robothy.s3.rest.service.ServiceFactory;
import com.robothy.s3.rest.utils.ByteBufUtils;
import com.robothy.s3.rest.utils.RequestUtils;
import com.robothy.s3.rest.utils.ResponseUtils;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Serves the built-in console of a LocalS3 service, {@code GET /_admin/ui}, and the JSON endpoints under it that the
 * page calls. What the page does, and every endpoint with its parameters, is described in
 * {@code docs/deployment.md#console}.
 *
 * <p>Why the endpoints are guarded the way they are:
 * <ul>
 *   <li>A browser can't sign with AWS Signature Version 4, so they want HTTP Basic authentication with the credentials
 *   of the service instead; a service without credentials answers them to anyone, like it answers unsigned S3
 *   requests, see {@linkplain com.robothy.s3.rest.LocalS3#warnIfOpenToTheNetwork()}.</li>
 *   <li>The endpoints that change the data also want the {@linkplain #CONSOLE_HEADER} header, which a browser lets no
 *   other origin send without a preflight, which {@linkplain LocalS3Router} always rejects, even where the default CORS
 *   rule allows every header, so a page open elsewhere can't write through the console.</li>
 *   <li>They call the same services as the S3 operations, so the console shows and changes what a client sees.</li>
 * </ul>
 */
class ConsoleController implements RouterHttpRequestHandler {

  /**
   * Path of the console page. The endpoints it calls are below it, and {@linkplain LocalS3Router} answers every
   * request of this path and of the paths below it through this controller.
   */
  static final String PATH = "/_admin/ui";

  static final String PAGE_OPERATION = "ConsolePage";

  static final String BUCKETS_OPERATION = "ConsoleListBuckets";

  static final String OBJECTS_OPERATION = "ConsoleListObjects";

  static final String OBJECT_OPERATION = "ConsoleGetObject";

  static final String PUT_OBJECT_OPERATION = "ConsolePutObject";

  static final String DELETE_OBJECT_OPERATION = "ConsoleDeleteObject";

  static final String CREATE_BUCKET_OPERATION = "ConsoleCreateBucket";

  static final String SNIPPETS_OPERATION = "ConsoleConnectionSnippets";

  static final String PRESIGN_OPERATION = "ConsolePresign";

  static final String CREATE_MULTIPART_UPLOAD_OPERATION = "ConsoleCreateMultipartUpload";

  static final String UPLOAD_PART_OPERATION = "ConsoleUploadPart";

  static final String COMPLETE_MULTIPART_UPLOAD_OPERATION = "ConsoleCompleteMultipartUpload";

  static final String ABORT_MULTIPART_UPLOAD_OPERATION = "ConsoleAbortMultipartUpload";

  static final String FEATURES_OPERATION = "ConsoleFeatures";

  static final String METADATA_OPERATION = "ConsoleGetObjectMetadata";

  static final String VERSIONS_OPERATION = "ConsoleListObjectVersions";

  static final String CATALOGS_OPERATION = "ConsoleListCatalogs";

  static final String NAMESPACE_OPERATION = "ConsoleGetNamespace";

  static final String TABLE_OPERATION = "ConsoleGetTable";

  static final String VECTOR_BUCKETS_OPERATION = "ConsoleListVectorBuckets";

  static final String VECTOR_INDEXES_OPERATION = "ConsoleListVectorIndexes";

  static final String VECTORS_OPERATION = "ConsoleListVectors";

  static final String VECTOR_OPERATION = "ConsoleGetVector";

  static final Set<String> OPERATIONS = Set.of(PAGE_OPERATION, BUCKETS_OPERATION, OBJECTS_OPERATION, OBJECT_OPERATION,
      PUT_OBJECT_OPERATION, DELETE_OBJECT_OPERATION, CREATE_BUCKET_OPERATION, SNIPPETS_OPERATION,
      PRESIGN_OPERATION, CREATE_MULTIPART_UPLOAD_OPERATION, UPLOAD_PART_OPERATION, COMPLETE_MULTIPART_UPLOAD_OPERATION,
      ABORT_MULTIPART_UPLOAD_OPERATION, FEATURES_OPERATION, METADATA_OPERATION, VERSIONS_OPERATION,
      CATALOGS_OPERATION, NAMESPACE_OPERATION, TABLE_OPERATION, VECTOR_BUCKETS_OPERATION, VECTOR_INDEXES_OPERATION,
      VECTORS_OPERATION, VECTOR_OPERATION);

  /**
   * The methods of the console: reading the page and the data, creating a bucket, uploading an object, in one
   * request or in parts, and deleting one. A request of any other method addresses a bucket rather than the console,
   * see {@linkplain LocalS3Router}.
   */
  static final Set<HttpMethod> METHODS = Set.of(HttpMethod.GET, HttpMethod.PUT, HttpMethod.POST, HttpMethod.DELETE);

  /**
   * The header that the page sends with every request that changes a bucket. A browser sends a header of this name
   * from another origin only if this service allows it in a preflight, which it doesn't, so a request that carries
   * it was made by the console rather than by another page of the browser, see the class documentation.
   */
  static final String CONSOLE_HEADER = "X-LocalS3-Console";

  private static final String BUCKETS_PATH = PATH + "/buckets";

  private static final String OBJECTS_PATH = PATH + "/objects";

  private static final String OBJECT_PATH = PATH + "/object";

  private static final String BUCKET_PATH = PATH + "/bucket";

  private static final String SNIPPETS_PATH = PATH + "/snippets";

  private static final String PRESIGN_PATH = PATH + "/presign";

  private static final String MULTIPART_PATH = PATH + "/multipart";

  private static final String COMPLETE_PATH = MULTIPART_PATH + "/complete";

  private static final String FEATURES_PATH = PATH + "/features";

  private static final String METADATA_PATH = PATH + "/metadata";

  private static final String VERSIONS_PATH = PATH + "/versions";

  private static final String CATALOGS_PATH = PATH + "/catalogs";

  private static final String NAMESPACE_PATH = PATH + "/namespace";

  private static final String TABLE_PATH = PATH + "/table";

  private static final String VECTOR_BUCKETS_PATH = PATH + "/vector-buckets";

  private static final String VECTOR_INDEXES_PATH = PATH + "/vector-indexes";

  private static final String VECTORS_PATH = PATH + "/vectors";

  private static final String VECTOR_PATH = PATH + "/vector";

  /**
   * The HTML page, which is on the classpath beside this class. It is read once: the page of a build never changes.
   */
  private static final String PAGE_RESOURCE = "console.html";

  /**
   * The number of objects of a page of the listing. The page of the console asks for the next one when the listing
   * is truncated, so a bucket of many objects is walked rather than rendered at once.
   */
  private static final int PAGE_SIZE = 200;

  /**
   * The versions of an object that the console lists at most. A key rewritten in a loop by a test can have many more,
   * and the newest are the ones a person looks for.
   */
  private static final int MAX_VERSIONS = 1000;

  /**
   * How long a presigned URL of the console is valid when the page asks for no expiration.
   */
  private static final Duration DEFAULT_PRESIGN_EXPIRATION = Duration.ofHours(1);

  /**
   * The methods that the console presigns a URL for: reading an object, or uploading one in its place.
   */
  private static final Set<String> PRESIGN_METHODS = Set.of("GET", "PUT");

  /**
   * What an unauthorized response asks for, which is what makes a browser show its credentials prompt.
   */
  private static final String AUTHENTICATE = "Basic realm=\"LocalS3 console\", charset=\"UTF-8\"";

  /**
   * What the content of an object is served with, so that a stored page can't act as the console that previews it:
   * a sandboxed document gets an opaque origin of its own, so its scripts, which are allowed to run so that a report
   * renders as it was meant to, reach neither the endpoints of the console nor the credentials of the browser.
   */
  private static final String OBJECT_SANDBOX = "sandbox allow-scripts allow-forms allow-popups allow-downloads";

  private final BucketService bucketService;

  private final ObjectService objectService;

  private final ObjectMapper objectMapper;

  /**
   * Checks the name of a bucket to create against the naming rules of Amazon S3, like {@code CreateBucket} does.
   */
  private final BucketNameValidator bucketNameValidator;

  /**
   * The access key ID that the console asks for as the user name; {@code null} if the service has no credentials,
   * which serves the console to every request.
   */
  private final @Nullable String accessKeyId;

  private final @Nullable String secretAccessKey;

  private final ConnectionSnippets snippets;

  /**
   * The configuration of the service; {@code null} for a router of handlers alone.
   */
  private final @Nullable LocalS3Config config;

  /**
   * What completing an upload of the console applies, like {@code CompleteMultipartUpload} of the API does.
   */
  private final MultipartUploadPolicy multipartUploadPolicy;

  private final ConsoleExplorer explorer;

  private final byte[] page;

  ConsoleController(ServiceFactory serviceFactory, @Nullable String accessKeyId, @Nullable String secretAccessKey) {
    this.bucketService = serviceFactory.getInstance(BucketService.class);
    this.objectService = serviceFactory.getInstance(ObjectService.class);
    this.objectMapper = serviceFactory.getInstance(ObjectMapper.class);
    this.bucketNameValidator = serviceFactory.containsInstance(BucketNameValidator.class)
        ? serviceFactory.getInstance(BucketNameValidator.class)
        : new BucketNameValidator();
    this.accessKeyId = accessKeyId;
    this.secretAccessKey = secretAccessKey;
    this.config = serviceFactory.containsInstance(LocalS3Config.class)
        ? serviceFactory.getInstance(LocalS3Config.class) : null;
    this.snippets = new ConnectionSnippets(config);
    this.multipartUploadPolicy = serviceFactory.containsInstance(MultipartUploadPolicy.class)
        ? serviceFactory.getInstance(MultipartUploadPolicy.class)
        : MultipartUploadPolicy.of(true);
    LocalS3Features features = config == null ? LocalS3Features.defaults() : config.features();
    this.explorer = new ConsoleExplorer(
        serviceFactory.containsInstance(IcebergCatalogService.class)
            ? serviceFactory.getInstance(IcebergCatalogService.class) : null,
        features.s3Tables() && serviceFactory.containsInstance(S3TablesService.class)
            ? serviceFactory.getInstance(S3TablesService.class) : null,
        features.vector() && serviceFactory.containsInstance(S3VectorsService.class)
            ? serviceFactory.getInstance(S3VectorsService.class) : null);
    this.page = readPage();
  }

  /**
   * Whether a path is the console page or one of the endpoints it calls.
   *
   * @param path the path of a request, without its trailing slash.
   * @return {@code true} if this controller answers it.
   */
  static boolean isConsolePath(String path) {
    return PATH.equals(path) || path.startsWith(PATH + "/");
  }

  /**
   * The operation that a request of the console is recorded as, e.g. {@code ConsoleListObjects}.
   *
   * @param method the method of the request.
   * @param path the path of the request, without its trailing slash.
   * @return the operation.
   */
  static String operation(HttpMethod method, String path) {
    if (HttpMethod.POST.equals(method)) {
      return COMPLETE_PATH.equals(path) ? COMPLETE_MULTIPART_UPLOAD_OPERATION : CREATE_MULTIPART_UPLOAD_OPERATION;
    }
    if (HttpMethod.PUT.equals(method)) {
      return switch (path) {
        case BUCKET_PATH -> CREATE_BUCKET_OPERATION;
        case MULTIPART_PATH -> UPLOAD_PART_OPERATION;
        default -> PUT_OBJECT_OPERATION;
      };
    }
    if (HttpMethod.DELETE.equals(method)) {
      return MULTIPART_PATH.equals(path) ? ABORT_MULTIPART_UPLOAD_OPERATION : DELETE_OBJECT_OPERATION;
    }
    return switch (path) {
      case BUCKETS_PATH -> BUCKETS_OPERATION;
      case OBJECTS_PATH -> OBJECTS_OPERATION;
      case OBJECT_PATH -> OBJECT_OPERATION;
      case SNIPPETS_PATH -> SNIPPETS_OPERATION;
      case PRESIGN_PATH -> PRESIGN_OPERATION;
      case FEATURES_PATH -> FEATURES_OPERATION;
      case METADATA_PATH -> METADATA_OPERATION;
      case VERSIONS_PATH -> VERSIONS_OPERATION;
      case CATALOGS_PATH -> CATALOGS_OPERATION;
      case NAMESPACE_PATH -> NAMESPACE_OPERATION;
      case TABLE_PATH -> TABLE_OPERATION;
      case VECTOR_BUCKETS_PATH -> VECTOR_BUCKETS_OPERATION;
      case VECTOR_INDEXES_PATH -> VECTOR_INDEXES_OPERATION;
      case VECTORS_PATH -> VECTORS_OPERATION;
      case VECTOR_PATH -> VECTOR_OPERATION;
      default -> PAGE_OPERATION;
    };
  }

  @Override
  public void handle(RouterHttpRequest request, RouterHttpResponse response) throws Exception {
    if (!isAuthorized(request)) {
      unauthorized(response);
      return;
    }

    String path = trimPath(Objects.toString(request.getPath(), ""));
    HttpMethod method = request.getMethod();
    try {
      if (!HttpMethod.GET.equals(method)) {
        write(request, response, path, method);
        return;
      }
      switch (path) {
        case PATH -> page(response);
        case BUCKETS_PATH -> buckets(response);
        case OBJECTS_PATH -> objects(request, response);
        case OBJECT_PATH -> object(request, response);
        case SNIPPETS_PATH -> json(response, HttpResponseStatus.OK, snippets.all(request,
            request.parameter("bucket").orElse(null), request.parameter("key").orElse(null)));
        case PRESIGN_PATH -> presign(request, response);
        case FEATURES_PATH -> json(response, HttpResponseStatus.OK,
            new ConsoleFeatures(explorer.hasTables(), explorer.hasVectors()));
        case METADATA_PATH -> metadata(request, response);
        case VERSIONS_PATH -> versions(request, response);
        case CATALOGS_PATH -> json(response, HttpResponseStatus.OK, explorer.catalogs());
        case NAMESPACE_PATH -> json(response, HttpResponseStatus.OK, explorer.namespace(
            request.parameter("tableBucket").orElse(null), request.parameter("namespace").orElse(null)));
        case TABLE_PATH -> json(response, HttpResponseStatus.OK, explorer.table(
            request.parameter("tableBucket").orElse(null), request.parameter("namespace").orElse(null),
            required(request, "table")));
        case VECTOR_BUCKETS_PATH -> json(response, HttpResponseStatus.OK,
            Map.of("vectorBuckets", explorer.vectorBuckets()));
        case VECTOR_INDEXES_PATH -> json(response, HttpResponseStatus.OK,
            Map.of("indexes", explorer.vectorIndexes(required(request, "vectorBucket"))));
        case VECTORS_PATH -> json(response, HttpResponseStatus.OK, explorer.vectors(
            required(request, "vectorBucket"), required(request, "index"),
            request.parameter("nextToken").orElse(null)));
        case VECTOR_PATH -> json(response, HttpResponseStatus.OK, explorer.vector(
            required(request, "vectorBucket"), required(request, "index"), required(request, "key")));
        default -> error(response, HttpResponseStatus.NOT_FOUND, "NotFound",
            "The console has no endpoint " + path + ".");
      }
    } catch (LocalS3Exception e) {
      // What the S3 API would answer with an XML error, e.g. a bucket that was deleted while the page showed it.
      error(response, HttpResponseStatus.valueOf(e.getS3ErrorCode().httpStatus()), e.getS3ErrorCode().code(),
          Objects.toString(e.getMessage(), e.getS3ErrorCode().description()));
    } catch (IcebergCatalogException e) {
      error(response, HttpResponseStatus.valueOf(e.status()), e.type(), e.getMessage());
    } catch (S3TablesException e) {
      error(response, HttpResponseStatus.valueOf(e.status()), e.errorType(), e.getMessage());
    } catch (LocalS3VectorException e) {
      error(response, HttpResponseStatus.valueOf(e.getErrorType().getStatus()), e.getErrorType().getCode(),
          e.getMessage());
    } catch (NoSuchElementException e) {
      // A catalog, a table bucket or the vectors of a service that serves none of them.
      error(response, HttpResponseStatus.NOT_FOUND, "NotFound", e.getMessage());
    } catch (IllegalArgumentException e) {
      error(response, HttpResponseStatus.BAD_REQUEST, "InvalidRequest", e.getMessage());
    }
  }

  /*
   * The endpoints.
   */

  private void page(RouterHttpResponse response) {
    response.status(HttpResponseStatus.OK)
        .putHeader(HttpHeaderNames.CONTENT_TYPE.toString(), "text/html; charset=utf-8")
        .putHeader(HttpHeaderNames.CACHE_CONTROL.toString(), "no-store")
        .write(page);
    ResponseUtils.addDateHeader(response);
    ResponseUtils.addServerHeader(response);
  }

  private void buckets(RouterHttpResponse response) {
    List<ConsoleBucket> buckets = bucketService.listBuckets().stream()
        .sorted(Comparator.comparing(Bucket::getName))
        .map(bucket -> new ConsoleBucket(bucket.getName(), Instant.ofEpochMilli(bucket.getCreationDate()).toString(),
            bucket.regionOrDefault()))
        .toList();
    json(response, HttpResponseStatus.OK, new ConsoleBuckets(buckets));
  }

  private void objects(RouterHttpRequest request, RouterHttpResponse response) {
    String bucket = required(request, "bucket");
    String prefix = request.parameter("prefix").orElse(null);
    String continuationToken = request.parameter("continuation-token").orElse(null);
    // The delimiter is what turns the flat keys into the directories the page walks; the owners aren't shown.
    ListObjectsV2Ans listing = objectService.listObjectsV2(bucket, continuationToken, "/", null, false, PAGE_SIZE,
        prefix, null);

    List<ConsoleObject> objects = listing.getObjects().stream()
        .map(object -> new ConsoleObject(object.key(), object.size(),
            object.lastModified() == null ? null : object.lastModified().toString(), object.etag()))
        .toList();
    json(response, HttpResponseStatus.OK, new ConsoleObjects(bucket, prefix, listing.getCommonPrefixes(), objects,
        listing.isTruncated(), listing.getNextContinuationToken().orElse(null)));
  }

  private void object(RouterHttpRequest request, RouterHttpResponse response) {
    String bucket = required(request, "bucket");
    String key = required(request, "key");
    GetObjectAns object = objectService.getObject(bucket, key, GetObjectOptions.builder()
        .versionId(request.parameter("versionId").orElse(null))
        .build());
    if (object.isDeleteMarker()) {
      error(response, HttpResponseStatus.NOT_FOUND, "NoSuchKey", "The object was deleted.");
      return;
    }

    boolean download = request.parameter("download").isPresent();
    response.status(HttpResponseStatus.OK)
        .putHeader(HttpHeaderNames.CONTENT_TYPE.toString(), contentType(object, key))
        .putHeader(HttpHeaderNames.CONTENT_LENGTH.toString(), object.getSize())
        .putHeader(HttpHeaderNames.CONTENT_DISPOSITION.toString(), disposition(download, key))
        .putHeader(HttpHeaderNames.CACHE_CONTROL.toString(), "no-store")
        .putHeader(HttpHeaderNames.CONTENT_SECURITY_POLICY.toString(), OBJECT_SANDBOX)
        .putHeader(HttpHeaderNames.LAST_MODIFIED.toString(),
            ResponseUtils.toRfc1123DateTime(object.getLastModified()));
    ResponseUtils.addETag(response, object.getEtag());
    ResponseUtils.addDateHeader(response);
    ResponseUtils.addServerHeader(response);
    writeContent(response, object.getContent());
  }

  /**
   * What the {@code Properties} tab of the preview shows: the system and the user metadata of a version of an
   * object, and its tags, which {@code HeadObject} and {@code GetObjectTagging} answer as headers and XML.
   */
  private void metadata(RouterHttpRequest request, RouterHttpResponse response) {
    String bucket = required(request, "bucket");
    String key = required(request, "key");
    GetObjectAns object = objectService.headObject(bucket, key, GetObjectOptions.builder()
        .versionId(request.parameter("versionId").orElse(null))
        .build());
    if (object.isDeleteMarker()) {
      json(response, HttpResponseStatus.OK, new ConsoleMetadata(bucket, key, object.getVersionId(), true,
          0, null, Instant.ofEpochMilli(object.getLastModified()).toString(), null, null, Map.of(), Map.of(),
          List.of(), null, null, null, null, null));
      return;
    }

    SystemMetadata system = object.getSystemMetadata();
    Map<String, String> headers = new LinkedHashMap<>();
    if (system != null) {
      putIfPresent(headers, "Cache-Control", system.getCacheControl());
      putIfPresent(headers, "Content-Disposition", system.getContentDisposition());
      putIfPresent(headers, "Content-Encoding", system.getContentEncoding());
      putIfPresent(headers, "Content-Language", system.getContentLanguage());
      putIfPresent(headers, "Expires", system.getExpires());
      putIfPresent(headers, "x-amz-website-redirect-location", system.getWebsiteRedirectLocation());
    }
    List<ConsoleTag> tags = new ArrayList<>();
    if (object.getTagging() != null) {
      for (String[] tag : object.getTagging()) {
        tags.add(new ConsoleTag(tag[0], tag.length > 1 ? tag[1] : ""));
      }
    }
    ObjectChecksum checksum = object.getChecksum();
    ServerSideEncryption encryption = object.getServerSideEncryption();
    ObjectLock lock = object.getObjectLock();
    json(response, HttpResponseStatus.OK, new ConsoleMetadata(bucket, key, object.getVersionId(), false,
        object.getSize(), object.getEtag(), Instant.ofEpochMilli(object.getLastModified()).toString(),
        object.getContentType(), SystemMetadata.storageClassOf(system).name(), headers,
        object.getUserMetadata() == null ? Map.of() : new TreeMap<>(object.getUserMetadata()), tags,
        object.getPartsCount(),
        checksum == null || checksum.getAlgorithm() == null ? null
            : checksum.getAlgorithm().name() + (checksum.getValue() == null ? "" : " " + checksum.getValue()),
        encryption == null ? null
            : encryption.algorithm() + (encryption.kmsKeyId() == null ? "" : " " + encryption.kmsKeyId()),
        lock == null || !lock.hasRetention() ? null : lock.mode() + " until "
            + Instant.ofEpochMilli(lock.retainUntilDate()),
        lock == null ? null : lock.isLegalHoldOn()));
  }

  /**
   * The versions of an object, newest first, and the delete markers between them, which {@code ListObjectVersions}
   * answers for a prefix rather than for a key.
   */
  private void versions(RouterHttpRequest request, RouterHttpResponse response) {
    String bucket = required(request, "bucket");
    String key = required(request, "key");
    Boolean versioning = bucketService.getVersioningEnabled(bucket);
    List<ConsoleVersion> versions = new ArrayList<>();
    String keyMarker = null;
    String versionIdMarker = null;
    boolean truncated = false;
    // The versions of the key come first among those of the keys it is a prefix of, so walking stops at the next key.
    walk:
    while (true) {
      ListObjectVersionsAns page = objectService.listObjectVersions(bucket, null, keyMarker, PAGE_SIZE, key,
          versionIdMarker);
      for (VersionItem item : page.getVersions()) {
        if (!key.equals(item.key())) {
          break walk;
        }
        if (versions.size() == MAX_VERSIONS) {
          truncated = true;
          break walk;
        }
        versions.add(switch (item) {
          case ObjectVersion version -> new ConsoleVersion(version.versionId(), version.latest(), false,
              version.size(), version.etag(), Objects.toString(version.lastModified(), null));
          case DeleteMarkerEntry marker -> new ConsoleVersion(marker.versionId(), marker.latest(), true, 0, null,
              Objects.toString(marker.lastModified(), null));
        });
      }
      if (page.getNextKeyMarker().isEmpty() || !key.equals(page.getNextKeyMarker().get())) {
        break;
      }
      keyMarker = page.getNextKeyMarker().get();
      versionIdMarker = page.getNextVersionIdMarker().orElse(null);
    }
    json(response, HttpResponseStatus.OK, new ConsoleVersions(bucket, key,
        versioning == null ? "Unversioned" : versioning ? "Enabled" : "Suspended", versions, truncated));
  }

  private static void putIfPresent(Map<String, String> map, String name, @Nullable String value) {
    if (value != null && !value.isEmpty()) {
      map.put(name, value);
    }
  }

  /**
   * Sign a URL of an object with the credentials of the service, so that it is shared with a client that has none,
   * e.g. a browser or a teammate that an AI agent hands an artifact to. The URL is built on the host that the page
   * addressed, which is the one that reaches this service from the browser, and the scheme it arrived by. A service
   * without credentials answers unsigned requests, so it answers the plain URL of the object, which doesn't expire.
   *
   * <p>Like Amazon S3, signing doesn't touch the data, but the page shares the objects it lists, so a URL of an object
   * that doesn't exist is refused rather than handed out.
   */
  private void presign(RouterHttpRequest request, RouterHttpResponse response) {
    String bucket = required(request, "bucket");
    String key = required(request, "key");
    String method = request.parameter("method").orElse("GET").trim().toUpperCase(Locale.ROOT);
    if (!PRESIGN_METHODS.contains(method)) {
      throw new IllegalArgumentException("The console presigns GET and PUT alone, not " + method + ".");
    }
    Duration expiration = DEFAULT_PRESIGN_EXPIRATION;
    String expires = request.parameter("expires").orElse(null);
    if (expires != null) {
      try {
        expiration = Duration.ofSeconds(Long.parseLong(expires));
      } catch (NumberFormatException _) {
        throw new IllegalArgumentException("The expires parameter must be a number of seconds, not " + expires + ".");
      }
    }
    // Throws NoSuchBucket or NoSuchKey, which is answered like any other error of the console; a PUT URL uploads an
    // object that may not exist yet, so only its bucket is checked.
    if ("GET".equals(method)
        && objectService.headObject(bucket, key, GetObjectOptions.builder().build()).isDeleteMarker()) {
      error(response, HttpResponseStatus.NOT_FOUND, "NoSuchKey", "The object was deleted.");
      return;
    }
    if ("PUT".equals(method)) {
      bucketService.getBucket(bucket);
    }

    boolean signed = accessKeyId != null;
    AwsSignatureV4Presigner presigner = signed
        ? new AwsSignatureV4Presigner(accessKeyId, secretAccessKey)
        : AwsSignatureV4Presigner.unsigned();
    Instant now = Instant.now();
    String url = presigner.presign(ServiceEndpoints.of(request, config), method, bucket, key, expiration);
    json(response, HttpResponseStatus.OK, new ConsolePresignedUrl(url, method, signed,
        signed ? expiration.getSeconds() : null, signed ? now.plus(expiration).toString() : null));
  }

  /**
   * Create a bucket, upload an object, in one request or in parts, or delete one: the requests of the console that
   * change the data, and the only ones that want the {@linkplain #CONSOLE_HEADER} header.
   */
  private void write(RouterHttpRequest request, RouterHttpResponse response, String path, HttpMethod method) {
    boolean createsBucket = BUCKET_PATH.equals(path) && HttpMethod.PUT.equals(method);
    boolean multipart = MULTIPART_PATH.equals(path) || (COMPLETE_PATH.equals(path) && HttpMethod.POST.equals(method));
    boolean object = OBJECT_PATH.equals(path) && !HttpMethod.POST.equals(method);
    if (!createsBucket && !multipart && !object) {
      error(response, HttpResponseStatus.NOT_FOUND, "NotFound", "The console changes the data through "
          + OBJECT_PATH + ", " + MULTIPART_PATH + " and " + BUCKET_PATH + " alone; it deletes no bucket.");
      return;
    }
    if (request.header(CONSOLE_HEADER).isEmpty()) {
      error(response, HttpResponseStatus.FORBIDDEN, "AccessDenied",
          "A request that changes the data must carry the " + CONSOLE_HEADER + " header, which the console sends.");
      return;
    }
    if (createsBucket) {
      createBucket(request, response);
    } else if (multipart) {
      multipart(request, response, path, method);
    } else if (HttpMethod.PUT.equals(method)) {
      putObject(request, response);
    } else {
      deleteObject(request, response);
    }
  }

  /**
   * Create a bucket, in the default region and with the naming rules of Amazon S3, which is what
   * {@code CreateBucket} of the API applies as well.
   */
  private void createBucket(RouterHttpRequest request, RouterHttpResponse response) {
    String name = required(request, "bucket");
    bucketNameValidator.validate(name);
    Bucket bucket = bucketService.createBucket(name, LocalS3Constants.DEFAULT_LOCATION_CONSTRAINT);
    json(response, HttpResponseStatus.OK, new ConsoleBucket(bucket.getName(),
        Instant.ofEpochMilli(bucket.getCreationDate()).toString(), bucket.regionOrDefault()));
  }

  private void putObject(RouterHttpRequest request, RouterHttpResponse response) {
    String bucket = required(request, "bucket");
    String key = required(request, "key");
    // The body of a large upload was buffered in a file, or in the heap of an IN_MEMORY service, which the storage
    // takes over rather than copying.
    DecodedAmzRequestBody body = RequestUtils.getBody(request);
    PutObjectAns stored = objectService.putObject(bucket, key, PutObjectOptions.builder()
        .contentType(uploadContentType(request, key))
        .size(body.getDecodedContentLength())
        .content(body.getDecodedBody())
        .contentFile(body.getBodyFile())
        .heapContent(body.getHeapContent())
        .build());
    json(response, HttpResponseStatus.OK,
        new ConsoleUpload(key, stored.getSize(), stored.getEtag(), stored.getVersionId()));
  }

  /**
   * A step of the multipart upload of a large file: start it, store a part of it, complete it, or abort it. The page
   * uploads a file in parts once it is larger than what it sends in one request, which keeps every request below the
   * largest body the service accepts.
   */
  private void multipart(RouterHttpRequest request, RouterHttpResponse response, String path, HttpMethod method) {
    String bucket = required(request, "bucket");
    String key = required(request, "key");
    if (HttpMethod.POST.equals(method) && MULTIPART_PATH.equals(path)) {
      String uploadId = objectService.createMultipartUpload(bucket, key, CreateMultipartUploadOptions.builder()
          .contentType(uploadContentType(request, key))
          .build());
      json(response, HttpResponseStatus.OK, new ConsoleMultipartUpload(key, uploadId));
      return;
    }

    String uploadId = required(request, "uploadId");
    if (HttpMethod.PUT.equals(method)) {
      int partNumber = partNumber(request);
      DecodedAmzRequestBody body = RequestUtils.getBody(request);
      UploadPartAns part = objectService.uploadPart(bucket, key, uploadId, partNumber, UploadPartOptions.builder()
          .contentLength(body.getDecodedContentLength())
          .data(body.getDecodedBody())
          .dataFile(body.getBodyFile())
          .heapData(body.getHeapContent())
          .build());
      json(response, HttpResponseStatus.OK, new ConsolePart(partNumber, part.getEtag()));
    } else if (HttpMethod.POST.equals(method)) {
      ConsoleParts given;
      try (InputStream in = RequestBodies.inputStream(request.getBody())) {
        given = objectMapper.readValue(in, ConsoleParts.class);
      } catch (IOException | JacksonException e) {
        throw new IllegalArgumentException("The body must list the parts as {\"parts\": [{\"partNumber\": 1, "
            + "\"etag\": \"...\"}]}.", e);
      }
      if (given == null || given.parts() == null || given.parts().isEmpty()) {
        throw new IllegalArgumentException("The body must list the parts of the upload.");
      }
      List<CompleteMultipartUploadPartOption> parts = given.parts().stream()
          .map(part -> CompleteMultipartUploadPartOption.builder()
              .partNumber(part.partNumber())
              .etag(part.etag())
              .build())
          .toList();
      CompleteMultipartUploadAns stored = objectService.completeMultipartUpload(bucket, key, uploadId, parts,
          multipartUploadPolicy.minimumPartSize(), multipartUploadPolicy.compositeEtags());
      json(response, HttpResponseStatus.OK,
          new ConsoleUpload(key, stored.getSize(), stored.getEtag(), stored.getVersionId()));
    } else {
      objectService.abortMultipartUpload(bucket, key, uploadId);
      json(response, HttpResponseStatus.OK, new ConsoleMultipartUpload(key, uploadId));
    }
  }

  private void deleteObject(RouterHttpRequest request, RouterHttpResponse response) {
    String bucket = required(request, "bucket");
    String key = required(request, "key");
    DeleteObjectAns deleted = objectService.deleteObject(bucket, key, request.parameter("versionId").orElse(null));
    json(response, HttpResponseStatus.OK,
        new ConsoleDeletion(key, deleted.isDeleteMarker(), deleted.getVersionId()));
  }

  /**
   * The content type an uploaded file is stored with: the one the browser read off the file, or, when it knows none,
   * the content type of the extension of the key. An object stored with the right type is previewed and served as
   * what it is, here and by the S3 API.
   *
   * @return the content type; {@code null} to store the object without one.
   */
  private static @Nullable String uploadContentType(RouterHttpRequest request, String key) {
    String given = request.header(HttpHeaderNames.CONTENT_TYPE.toString()).orElse("").trim();
    if (!given.isEmpty() && !given.startsWith("application/octet-stream")) {
      return given;
    }
    return WebsiteContentTypes.of(key).orElse(given.isEmpty() ? null : given);
  }

  /*
   * Authentication.
   */

  /**
   * Whether a request carries the credentials of the service, as HTTP Basic authentication. A service that has no
   * credentials answers every request: it answers unsigned S3 requests too, so asking the console for a password it
   * doesn't have would guard nothing.
   */
  private boolean isAuthorized(RouterHttpRequest request) {
    return accessKeyId == null || ServiceCredentials.hasBasic(request, accessKeyId, secretAccessKey);
  }

  private void unauthorized(RouterHttpResponse response) {
    response.putHeader(HttpHeaderNames.WWW_AUTHENTICATE.toString(), AUTHENTICATE);
    error(response, HttpResponseStatus.UNAUTHORIZED, "AccessDenied",
        "The console wants the credentials of this service: the access key ID as the user name, and the secret "
            + "access key as the password.");
  }

  /*
   * Writing a response.
   */

  private void json(RouterHttpResponse response, HttpResponseStatus status, Object body) {
    response.status(status)
        .putHeader(HttpHeaderNames.CONTENT_TYPE.toString(), HttpHeaderValues.APPLICATION_JSON)
        .putHeader(HttpHeaderNames.CACHE_CONTROL.toString(), "no-store")
        .write(objectMapper.writeValueAsString(body));
    ResponseUtils.addDateHeader(response);
    ResponseUtils.addServerHeader(response);
  }

  /**
   * Answer an error as JSON, which is what the page shows; the S3 API answers its own requests with XML.
   */
  private void error(RouterHttpResponse response, HttpResponseStatus status, String code, String message) {
    json(response, status, Map.of("code", code, "message", Objects.toString(message, status.reasonPhrase())));
  }

  /**
   * Stream the content of an object when the response supports it, like {@code GetObject} does, so that previewing
   * a large object doesn't buffer it in memory.
   */
  private static void writeContent(RouterHttpResponse response, InputStream content) {
    if (response instanceof StreamingRouterHttpResponse streamingResponse) {
      streamingResponse.stream(content);
    } else {
      response.write(ByteBufUtils.fromInputStream(content));
    }
  }

  /**
   * The content type that an object is previewed with: the one it was stored with, or, for an object that was
   * stored without one, the content type of its extension, so that a browser renders a report or an image instead
   * of downloading it. {@linkplain StaticWebsiteController} guesses the same way.
   */
  private static String contentType(GetObjectAns object, String key) {
    String stored = object.getContentType();
    if (stored != null && !stored.startsWith("binary/octet-stream") && !stored.startsWith("application/octet-stream")) {
      return stored;
    }
    return WebsiteContentTypes.of(key).orElse("application/octet-stream");
  }

  /**
   * How the content of an object is presented: rendered in the page, or saved under the last segment of its key.
   * The name is given twice, as the ASCII of {@code filename} and as the UTF-8 of {@code filename*}, so that a key
   * that isn't ASCII is saved under its own name by a browser that reads either.
   */
  private static String disposition(boolean download, String key) {
    String name = key.substring(key.lastIndexOf('/') + 1);
    StringBuilder ascii = new StringBuilder(name.length());
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      // A quote or a backslash would end the quoted name, and anything outside of printable ASCII isn't allowed.
      ascii.append(c < ' ' || c > '~' || c == '"' || c == '\\' ? '_' : c);
    }
    return (download ? "attachment" : "inline") + "; filename=\"" + ascii + "\"; filename*=UTF-8''"
        + S3ObjectUtils.urlEncode(name, false);
  }

  /*
   * Reading a request.
   */

  private static String required(RouterHttpRequest request, String parameter) {
    String value = request.parameter(parameter).orElse("");
    if (value.isEmpty()) {
      throw new IllegalArgumentException("The " + parameter + " parameter is required.");
    }
    return value;
  }

  private static int partNumber(RouterHttpRequest request) {
    String value = required(request, "partNumber");
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException _) {
      throw new IllegalArgumentException("The partNumber parameter must be a number, not " + value + ".");
    }
  }

  /**
   * The path of a request without its trailing slash, so that {@code /_admin/ui/} is the page as well.
   */
  private static String trimPath(String path) {
    return path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
  }

  private static byte[] readPage() {
    try (InputStream page = ConsoleController.class.getResourceAsStream(PAGE_RESOURCE)) {
      if (page == null) {
        throw new IllegalStateException("The console page " + PAGE_RESOURCE + " is missing from the classpath.");
      }
      return page.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read the console page " + PAGE_RESOURCE + ".", e);
    }
  }

  /*
   * What the endpoints answer with. The page reads these, and nothing else does, so they carry what it shows rather
   * than the shape of the Amazon S3 documents.
   */

  /**
   * What the service serves besides its buckets, which decides the views of the page.
   *
   * @param tables whether it serves an Iceberg REST catalog or table buckets.
   * @param vectors whether it serves the S3 Vectors API.
   */
  private record ConsoleFeatures(boolean tables, boolean vectors) {
  }

  /**
   * The metadata of a version of an object.
   *
   * @param versionId the version; {@code null} in a bucket that was never versioned.
   * @param deleteMarker whether the version is a delete marker, which has no metadata but its date.
   * @param headers the system metadata that the object is served with, by the name of its header.
   * @param userMetadata the {@code x-amz-meta-*} metadata, by its name without the prefix.
   * @param checksum the algorithm of the checksum it was stored with, and the checksum.
   * @param encryption the server-side encryption that it was stored with, which LocalS3 records but doesn't apply.
   * @param retention the Object Lock retention; {@code null} if it has none.
   * @param legalHold whether a legal hold is on; {@code null} if it never had one.
   */
  private record ConsoleMetadata(String bucket, String key, @Nullable String versionId, boolean deleteMarker,
                                 long size, @Nullable String etag, String lastModified, @Nullable String contentType,
                                 @Nullable String storageClass, Map<String, String> headers,
                                 Map<String, String> userMetadata, List<ConsoleTag> tags, @Nullable Integer partsCount,
                                 @Nullable String checksum, @Nullable String encryption, @Nullable String retention,
                                 @Nullable Boolean legalHold) {
  }

  private record ConsoleTag(String key, String value) {
  }

  /**
   * The versions of an object.
   *
   * @param versioning {@code Enabled}, {@code Suspended}, or {@code Unversioned}: the versioning of the bucket.
   * @param versions the versions and the delete markers, newest first.
   * @param truncated whether the object has more versions than the console lists.
   */
  private record ConsoleVersions(String bucket, String key, String versioning, List<ConsoleVersion> versions,
                                 boolean truncated) {
  }

  /**
   * @param versionId the version; {@code null} for the version of a bucket that was never versioned.
   * @param latest whether it is the current version of the key.
   * @param deleteMarker whether it is a delete marker rather than a version of the object.
   */
  private record ConsoleVersion(@Nullable String versionId, boolean latest, boolean deleteMarker, long size,
                                @Nullable String etag, @Nullable String lastModified) {
  }

  /**
   * The buckets of the service.
   */
  private record ConsoleBuckets(List<ConsoleBucket> buckets) {
  }

  /**
   * @param name the name of the bucket.
   * @param creationDate when it was created, as an ISO 8601 instant.
   * @param region the region it was created in.
   */
  private record ConsoleBucket(String name, String creationDate, String region) {
  }

  /**
   * One page of the objects of a bucket under a prefix.
   *
   * @param bucket the bucket that was listed.
   * @param prefix the prefix that was listed; {@code null} for the root of the bucket.
   * @param prefixes the common prefixes under it, i.e. the directories of the page.
   * @param objects the objects directly under it.
   * @param truncated whether the bucket holds more than this page.
   * @param nextContinuationToken what the next page is asked for with; {@code null} if this is the last one.
   */
  private record ConsoleObjects(String bucket, @Nullable String prefix, List<String> prefixes,
                                List<ConsoleObject> objects, boolean truncated,
                                @Nullable String nextContinuationToken) {
  }

  /**
   * @param key the key of the object.
   * @param size its size in bytes.
   * @param lastModified when it was last modified, as an ISO 8601 instant.
   * @param etag its entity tag.
   */
  private record ConsoleObject(String key, long size, @Nullable String lastModified, @Nullable String etag) {
  }

  /**
   * A presigned URL of an object.
   *
   * @param url the URL.
   * @param method the HTTP method that it is signed for.
   * @param signed whether it is signed; a service without credentials hands out the plain URL of the object.
   * @param expiresIn how many seconds it is valid for; {@code null} if it isn't signed, which doesn't expire.
   * @param expiresAt when it expires, as an ISO 8601 instant; {@code null} if it isn't signed.
   */
  private record ConsolePresignedUrl(String url, String method, boolean signed, @Nullable Long expiresIn,
                                     @Nullable String expiresAt) {
  }

  /**
   * The object an upload stored.
   *
   * @param key the key it was stored under.
   * @param size its size in bytes.
   * @param etag its entity tag.
   * @param versionId the version that was stored; {@code null} in a bucket that was never versioned.
   */
  private record ConsoleUpload(String key, long size, @Nullable String etag, @Nullable String versionId) {
  }

  /**
   * A multipart upload that was started, or aborted.
   *
   * @param key the key the upload stores its object under.
   * @param uploadId what the parts of the upload, its completion and its abort are requested with.
   */
  private record ConsoleMultipartUpload(String key, String uploadId) {
  }

  /**
   * A part that was stored, which the page lists again when it completes the upload.
   *
   * @param partNumber the number of the part.
   * @param etag its entity tag.
   */
  private record ConsolePart(int partNumber, String etag) {
  }

  /**
   * The parts that complete an upload, in the order of their numbers.
   */
  private record ConsoleParts(List<ConsolePart> parts) {
  }

  /**
   * What a delete did.
   *
   * @param key the key that was deleted.
   * @param deleteMarker whether a delete marker was put on it, which is what a delete does in a versioned bucket.
   * @param versionId the version that was deleted, or the delete marker that was put; {@code null} in a bucket that
   *     was never versioned.
   */
  private record ConsoleDeletion(String key, boolean deleteMarker, @Nullable String versionId) {
  }

}
