package com.robothy.s3.rest.handler;

import com.robothy.netty.http.RouterHttpRequest;
import com.robothy.netty.http.RouterHttpRequestHandler;
import com.robothy.netty.http.RouterHttpResponse;
import com.robothy.s3.core.exception.InvalidRangeException;
import com.robothy.s3.core.exception.LocalS3Exception;
import com.robothy.s3.core.model.Bucket;
import com.robothy.s3.core.model.answers.DeleteObjectAns;
import com.robothy.s3.core.model.answers.GetObjectAns;
import com.robothy.s3.core.model.answers.ListObjectsV2Ans;
import com.robothy.s3.core.model.answers.PutObjectAns;
import com.robothy.s3.core.model.request.GetObjectOptions;
import com.robothy.s3.core.model.request.PutObjectOptions;
import com.robothy.s3.core.model.request.Range;
import com.robothy.s3.core.service.BucketService;
import com.robothy.s3.core.service.ObjectService;
import com.robothy.s3.core.util.S3ObjectUtils;
import com.robothy.s3.core.util.UrlPaths;
import com.robothy.s3.rest.LocalS3Config;
import com.robothy.s3.rest.constants.LocalS3Constants;
import com.robothy.s3.rest.netty.RequestBodies;
import com.robothy.s3.rest.service.BucketNameValidator;
import com.robothy.s3.rest.service.ServiceFactory;
import com.robothy.s3.rest.utils.ResponseUtils;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Serves the built-in MCP server of a LocalS3 service, {@code POST /_mcp}: the Streamable HTTP transport of the Model
 * Context Protocol, answered with plain JSON rather than an event stream. The tools, the resources and the guards are
 * described in {@code docs/deployment.md#mcp-server}.
 *
 * <p>Why it is built the way it is:
 * <ul>
 *   <li>It is JSON-RPC over the Jackson mapper the service already has, so serving agents adds no dependency.</li>
 *   <li>Every tool answers within its request and nothing is pushed to a client, so there is no session and no
 *   stream: a {@code GET} for one is refused with {@code 405}, which the transport allows.</li>
 *   <li>An agent can't sign with AWS Signature Version 4, so it is guarded with the credentials of the service as
 *   HTTP Basic authentication or as a bearer token, the headers an MCP client can be configured with.</li>
 *   <li>The transport asks a server to check the {@code Origin} of a request, so that a page that a browser opens
 *   elsewhere can't reach a server on the loopback address by rebinding its DNS name.</li>
 * </ul>
 */
class McpController implements RouterHttpRequestHandler {

  static final String PATH = "/_mcp";

  static final String OPERATION = "Mcp";

  /**
   * The methods of the transport: {@code POST} carries the messages, and {@code GET}, which opens a stream, and
   * {@code DELETE}, which ends a session, are answered with {@code 405} since the server has neither.
   */
  static final Set<HttpMethod> METHODS = Set.of(HttpMethod.GET, HttpMethod.POST, HttpMethod.DELETE);

  /**
   * The versions of the protocol that the server speaks, the latest first. A client that asks for another one is
   * answered with the latest, which it may then refuse, as the lifecycle of the protocol prescribes.
   */
  static final List<String> PROTOCOL_VERSIONS = List.of("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05");

  private static final String JSONRPC = "2.0";

  private static final String PROTOCOL_VERSION_HEADER = "MCP-Protocol-Version";

  private static final int PARSE_ERROR = -32700;

  private static final int INVALID_REQUEST = -32600;

  private static final int METHOD_NOT_FOUND = -32601;

  private static final int INVALID_PARAMS = -32602;

  private static final int RESOURCE_NOT_FOUND = -32002;

  /**
   * How much of an object a read returns when the agent asks for no limit, and the most it returns at all: the
   * content lands in the context of a model, so a large object is read a range at a time.
   */
  private static final int DEFAULT_READ_BYTES = 256 * 1024;

  private static final int MAX_READ_BYTES = 4 * 1024 * 1024;

  private static final int DEFAULT_LIST_KEYS = 100;

  private static final int MAX_LIST_KEYS = 1000;

  private static final Duration DEFAULT_PRESIGN_EXPIRATION = Duration.ofHours(1);

  private static final String S3_SCHEME = "s3://";

  private static final String AUTHENTICATE = "Basic realm=\"LocalS3 MCP\", charset=\"UTF-8\"";

  private static final String INSTRUCTIONS = "LocalS3 is a local Amazon S3 compatible object store. Use it to keep "
      + "artifacts: list_buckets and list_objects to find them, read_object to read one (text, or base64 for binary, "
      + "a range at a time for a large one), put_object to store one, presign to hand a URL of one to a person or "
      + "another tool, and delete_object to remove one. Objects are also resources, s3://<bucket>/<key>.";

  private static final List<Map<String, Object>> TOOLS = List.of(
      tool("list_buckets", "List buckets", "List the buckets of the service.",
          Map.of(), List.of(), readOnly()),
      tool("list_objects", "List objects",
          "List the objects of a bucket under a prefix, a page at a time. With the default delimiter '/', the "
              + "objects directly under the prefix are listed, and the folders below it as commonPrefixes; an empty "
              + "delimiter lists every object below the prefix.",
          properties(
              "bucket", string("The bucket."),
              "prefix", string("Only keys that start with this, e.g. 'reports/'."),
              "delimiter", string("Groups keys into commonPrefixes. Default '/'; '' for none."),
              "max_keys", integer("The most objects to list, 1 to " + MAX_LIST_KEYS + ". Default "
                  + DEFAULT_LIST_KEYS + "."),
              "continuation_token", string("The nextContinuationToken of the previous page.")),
          List.of("bucket"), readOnly()),
      tool("read_object", "Read an object",
          "Read the content of an object: as text if it is text, otherwise base64-encoded. At most max_bytes are "
              + "returned; a larger object is truncated, and the rest is read with range.",
          properties(
              "bucket", string("The bucket."),
              "key", string("The key of the object."),
              "range", string("A byte range like the HTTP Range header, e.g. 'bytes=0-1023', 'bytes=4096-' or "
                  + "'bytes=-512' for the last 512 bytes."),
              "max_bytes", integer("The most bytes to return, up to " + MAX_READ_BYTES + ". Default "
                  + DEFAULT_READ_BYTES + "."),
              "version_id", string("A version of the object, in a versioned bucket.")),
          List.of("bucket", "key"), readOnly()),
      tool("put_object", "Store an object",
          "Store an object, replacing one of the same key. The content is text, or base64 with encoding 'base64'. "
              + "Without content_type, it is stored with the content type of the extension of its key.",
          properties(
              "bucket", string("The bucket."),
              "key", string("The key to store the object under, e.g. 'reports/summary.md'."),
              "content", string("The content of the object."),
              "encoding", enumeration("How content is encoded. Default 'utf-8'.", "utf-8", "base64"),
              "content_type", string("The content type, e.g. 'text/markdown; charset=utf-8'.")),
          List.of("bucket", "key", "content"), annotations(false, true, true)),
      tool("presign", "Presign a URL",
          "Create a presigned URL that reads (GET) or uploads (PUT) an object without credentials, e.g. to hand a "
              + "report to a person. A service without credentials answers the plain URL, which doesn't expire.",
          properties(
              "bucket", string("The bucket."),
              "key", string("The key of the object."),
              "method", enumeration("What the URL does. Default 'GET'.", "GET", "PUT"),
              "expires_in", integer("How many seconds the URL is valid, 1 to 604800. Default 3600.")),
          List.of("bucket", "key"), readOnly()),
      tool("delete_object", "Delete an object",
          "Delete an object. In a versioned bucket it puts a delete marker, unless version_id names the version to "
              + "delete.",
          properties(
              "bucket", string("The bucket."),
              "key", string("The key of the object."),
              "version_id", string("The version to delete, in a versioned bucket.")),
          List.of("bucket", "key"), annotations(false, true, true)),
      tool("create_bucket", "Create a bucket",
          "Create a bucket, under the naming rules of Amazon S3, e.g. to keep the artifacts of a task in.",
          properties("bucket", string("The name of the bucket.")),
          List.of("bucket"), annotations(false, false, false)));

  private static final Set<String> TOOL_NAMES = TOOLS.stream()
      .map(tool -> (String) tool.get("name"))
      .collect(Collectors.toUnmodifiableSet());

  private static final int RESOURCE_PAGE_SIZE = 100;

  private final BucketService bucketService;

  private final ObjectService objectService;

  private final ObjectMapper objectMapper;

  private final BucketNameValidator bucketNameValidator;

  private final @Nullable String accessKeyId;

  private final @Nullable String secretAccessKey;

  private final @Nullable LocalS3Config config;

  McpController(ServiceFactory serviceFactory, @Nullable String accessKeyId, @Nullable String secretAccessKey) {
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
  }

  @Override
  public void handle(RouterHttpRequest request, RouterHttpResponse response) {
    if (!isAllowedOrigin(request)) {
      json(response, HttpResponseStatus.FORBIDDEN, error(null, INVALID_REQUEST,
          "The MCP server answers requests from a page of localhost or of its own host alone."));
      return;
    }
    if (!isAuthorized(request)) {
      response.putHeader(HttpHeaderNames.WWW_AUTHENTICATE.toString(), AUTHENTICATE);
      json(response, HttpResponseStatus.UNAUTHORIZED, error(null, INVALID_REQUEST,
          "The MCP server wants the credentials of this service: 'Authorization: Bearer <secret access key>', or "
              + "HTTP Basic authentication with the access key ID and the secret access key."));
      return;
    }
    if (!HttpMethod.POST.equals(request.getMethod())) {
      response.putHeader(HttpHeaderNames.ALLOW.toString(), HttpMethod.POST.name());
      json(response, HttpResponseStatus.METHOD_NOT_ALLOWED, error(null, INVALID_REQUEST,
          "The MCP server answers every message in the response to its POST; it opens no stream and keeps no "
              + "session."));
      return;
    }
    String version = request.header(PROTOCOL_VERSION_HEADER).map(String::trim).orElse(null);
    if (version != null && !PROTOCOL_VERSIONS.contains(version)) {
      json(response, HttpResponseStatus.BAD_REQUEST, error(null, INVALID_REQUEST,
          "Unsupported protocol version " + version + "; supported: " + PROTOCOL_VERSIONS + "."));
      return;
    }

    JsonNode message;
    try (InputStream in = RequestBodies.inputStream(request.getBody())) {
      message = objectMapper.readTree(in);
    } catch (IOException | JacksonException e) {
      json(response, HttpResponseStatus.BAD_REQUEST, error(null, PARSE_ERROR, "The body isn't JSON."));
      return;
    }

    // A batch was allowed by the 2025-03-26 version of the protocol; answering one costs nothing.
    Object answer;
    if (message != null && message.isArray()) {
      List<Map<String, Object>> answers = new ArrayList<>();
      for (JsonNode each : message) {
        Map<String, Object> one = process(each, request);
        if (one != null) {
          answers.add(one);
        }
      }
      answer = answers.isEmpty() ? null : answers;
    } else {
      answer = process(message, request);
    }

    if (answer == null) {
      // A notification, or a response of the client, which the transport acknowledges without a body.
      response.status(HttpResponseStatus.ACCEPTED)
          .putHeader(HttpHeaderNames.CONTENT_LENGTH.toString(), 0);
      ResponseUtils.addDateHeader(response);
      ResponseUtils.addServerHeader(response);
      return;
    }
    json(response, HttpResponseStatus.OK, answer);
  }

  /*
   * JSON-RPC.
   */

  /**
   * @return the response to a request; {@code null} for a notification or a response, which are answered with none.
   */
  private @Nullable Map<String, Object> process(@Nullable JsonNode message, RouterHttpRequest request) {
    if (message == null || !message.isObject()) {
      return error(null, INVALID_REQUEST, "A message must be a JSON-RPC object.");
    }
    JsonNode id = message.get("id");
    JsonNode method = message.get("method");
    if (method == null || !method.isString()) {
      // A response to a request of the server, which sends none, is ignored.
      return message.has("result") || message.has("error")
          ? null : error(id, INVALID_REQUEST, "A request must name its method.");
    }
    if (id == null || id.isNull()) {
      // Notifications, e.g. notifications/initialized, change nothing on a server without sessions.
      return null;
    }

    JsonNode params = message.path("params");
    try {
      Object result = switch (method.asString()) {
        case "initialize" -> initialize(params);
        case "ping" -> Map.of();
        case "tools/list" -> Map.of("tools", TOOLS);
        case "tools/call" -> callTool(params, request);
        case "resources/list" -> listResources(params);
        case "resources/templates/list" -> Map.of("resourceTemplates", List.of(ordered(
            "uriTemplate", S3_SCHEME + "{bucket}/{key}",
            "name", "object",
            "title", "An object of LocalS3",
            "description", "The content of an object, by its bucket and key.")));
        case "resources/read" -> readResource(params);
        default -> throw new McpError(METHOD_NOT_FOUND, "Method not found: " + method.asString());
      };
      return ordered("jsonrpc", JSONRPC, "id", id, "result", result);
    } catch (McpError e) {
      return error(id, e.code, e.getMessage());
    } catch (LocalS3Exception e) {
      // E.g. a resource of a bucket that doesn't exist.
      return error(id, RESOURCE_NOT_FOUND, describe(e));
    }
  }

  private Map<String, Object> initialize(JsonNode params) {
    String requested = params.path("protocolVersion").asString("");
    String version = PROTOCOL_VERSIONS.contains(requested) ? requested : PROTOCOL_VERSIONS.getFirst();
    return ordered(
        "protocolVersion", version,
        "capabilities", ordered(
            "tools", Map.of("listChanged", false),
            "resources", ordered("subscribe", false, "listChanged", false)),
        "serverInfo", ordered(
            "name", LocalS3Constants.SERVER_NAME,
            "title", "LocalS3",
            "version", Objects.requireNonNullElse(McpController.class.getPackage().getImplementationVersion(),
                "dev")),
        "instructions", INSTRUCTIONS);
  }

  /*
   * Tools.
   */

  private Map<String, Object> callTool(JsonNode params, RouterHttpRequest request) {
    String name = params.path("name").asString("");
    if (!TOOL_NAMES.contains(name)) {
      throw new McpError(INVALID_PARAMS, "Unknown tool: " + name);
    }
    JsonNode arguments = params.path("arguments");
    // A failure of a tool is its result rather than an error of the protocol, so that the model reads it and
    // corrects its call, e.g. a key that doesn't exist.
    try {
      return switch (name) {
        case "list_buckets" -> structured(listBuckets());
        case "list_objects" -> structured(listObjects(arguments));
        case "read_object" -> readObjectTool(arguments);
        case "put_object" -> structured(putObject(arguments));
        case "presign" -> structured(presign(arguments, request));
        case "delete_object" -> structured(deleteObject(arguments));
        default -> structured(createBucket(arguments));
      };
    } catch (LocalS3Exception e) {
      return toolError(describe(e));
    } catch (IllegalArgumentException e) {
      return toolError("InvalidArgument: " + e.getMessage());
    }
  }

  private Map<String, Object> listBuckets() {
    List<Map<String, Object>> buckets = bucketService.listBuckets().stream()
        .sorted(Comparator.comparing(Bucket::getName))
        .map(bucket -> ordered(
            "name", bucket.getName(),
            "creationDate", Instant.ofEpochMilli(bucket.getCreationDate()).toString(),
            "region", bucket.regionOrDefault()))
        .toList();
    return Map.of("buckets", buckets);
  }

  private Map<String, Object> listObjects(JsonNode arguments) {
    String bucket = required(arguments, "bucket");
    String prefix = optional(arguments, "prefix");
    String delimiter = arguments.has("delimiter") ? arguments.path("delimiter").asString("") : "/";
    int maxKeys = (int) number(arguments, "max_keys", DEFAULT_LIST_KEYS);
    if (maxKeys < 1 || maxKeys > MAX_LIST_KEYS) {
      throw new IllegalArgumentException("max_keys must be between 1 and " + MAX_LIST_KEYS + ".");
    }
    ListObjectsV2Ans listing = objectService.listObjectsV2(bucket, optional(arguments, "continuation_token"),
        delimiter.isEmpty() ? null : delimiter, null, false, maxKeys, prefix, null);

    List<Map<String, Object>> objects = listing.getObjects().stream()
        .map(object -> ordered(
            "key", object.key(),
            "size", object.size(),
            "lastModified", object.lastModified() == null ? null : object.lastModified().toString(),
            "etag", object.etag(),
            "uri", uri(bucket, object.key())))
        .toList();
    return ordered(
        "bucket", bucket,
        "prefix", prefix,
        "commonPrefixes", listing.getCommonPrefixes(),
        "objects", objects,
        "truncated", listing.isTruncated(),
        "nextContinuationToken", listing.getNextContinuationToken().orElse(null));
  }

  private Map<String, Object> readObjectTool(JsonNode arguments) {
    String bucket = required(arguments, "bucket");
    String key = required(arguments, "key");
    long maxBytes = number(arguments, "max_bytes", DEFAULT_READ_BYTES);
    if (maxBytes < 1 || maxBytes > MAX_READ_BYTES) {
      throw new IllegalArgumentException("max_bytes must be between 1 and " + MAX_READ_BYTES + ".");
    }
    String range = optional(arguments, "range");
    Range parsed = null;
    if (range != null) {
      parsed = Range.tryParse(range).orElseThrow(() -> new IllegalArgumentException(
          "range must be a single byte range like 'bytes=0-1023', not " + range + "."));
    }
    ObjectContent object = read(bucket, key, optional(arguments, "version_id"), parsed, (int) maxBytes);

    List<Map<String, Object>> content = new ArrayList<>();
    if (object.text()) {
      content.add(text(new String(object.bytes(), StandardCharsets.UTF_8)));
    } else {
      String data = Base64.getEncoder().encodeToString(object.bytes());
      content.add(text("Binary object (" + object.contentType() + ", " + object.size()
          + " bytes); the content is base64-encoded."));
      content.add(object.contentType().startsWith("image/")
          ? ordered("type", "image", "data", data, "mimeType", object.contentType())
          : ordered("type", "resource", "resource", ordered(
              "uri", uri(bucket, key), "mimeType", object.contentType(), "blob", data)));
    }
    if (object.truncated()) {
      content.add(text("[Truncated: bytes " + object.start() + "-" + object.end() + " of " + object.size()
          + " were returned. Read the rest with range 'bytes=" + (object.end() + 1) + "-'.]"));
    }

    Map<String, Object> metadata = ordered(
        "bucket", bucket,
        "key", key,
        "uri", uri(bucket, key),
        "contentType", object.contentType(),
        "size", object.size(),
        "etag", object.etag(),
        "versionId", object.versionId(),
        "range", object.size() == 0 ? null : "bytes " + object.start() + "-" + object.end() + "/" + object.size(),
        "truncated", object.truncated(),
        "encoding", object.text() ? "utf-8" : "base64");
    return ordered("content", content, "structuredContent", metadata, "isError", false);
  }

  private Map<String, Object> putObject(JsonNode arguments) {
    String bucket = required(arguments, "bucket");
    String key = required(arguments, "key");
    JsonNode given = arguments.get("content");
    if (given == null || !given.isString()) {
      throw new IllegalArgumentException("content must be a string.");
    }
    String encoding = Objects.requireNonNullElse(optional(arguments, "encoding"), "utf-8").toLowerCase(Locale.ROOT);
    byte[] bytes = switch (encoding) {
      case "utf-8", "utf8", "text" -> given.asString().getBytes(StandardCharsets.UTF_8);
      case "base64" -> Base64.getMimeDecoder().decode(given.asString());
      default -> throw new IllegalArgumentException("encoding must be 'utf-8' or 'base64', not " + encoding + ".");
    };
    String contentType = optional(arguments, "content_type");
    if (contentType == null) {
      contentType = WebsiteContentTypes.of(key)
          .orElse("base64".equals(encoding) ? "application/octet-stream" : "text/plain; charset=utf-8");
    }

    PutObjectAns stored = objectService.putObject(bucket, key, PutObjectOptions.builder()
        .contentType(contentType)
        .size(bytes.length)
        .content(new ByteArrayInputStream(bytes))
        .build());
    return ordered(
        "bucket", bucket,
        "key", key,
        "uri", uri(bucket, key),
        "size", stored.getSize(),
        "contentType", contentType,
        "etag", stored.getEtag(),
        "versionId", stored.getVersionId());
  }

  /**
   * Sign a URL like the console does, on the host that the agent reached this service at.
   */
  private Map<String, Object> presign(JsonNode arguments, RouterHttpRequest request) {
    String bucket = required(arguments, "bucket");
    String key = required(arguments, "key");
    String method = Objects.requireNonNullElse(optional(arguments, "method"), "GET").trim().toUpperCase(Locale.ROOT);
    if (!"GET".equals(method) && !"PUT".equals(method)) {
      throw new IllegalArgumentException("method must be GET or PUT, not " + method + ".");
    }
    Duration expiration = Duration.ofSeconds(number(arguments, "expires_in",
        DEFAULT_PRESIGN_EXPIRATION.getSeconds()));
    // A URL of an object that doesn't exist would only fail later, in the hands of whoever it was given to.
    if ("GET".equals(method)
        && objectService.headObject(bucket, key, GetObjectOptions.builder().build()).isDeleteMarker()) {
      throw new IllegalArgumentException("The object was deleted.");
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
    return ordered(
        "url", url,
        "method", method,
        "signed", signed,
        "expiresIn", signed ? expiration.getSeconds() : null,
        "expiresAt", signed ? now.plus(expiration).toString() : null);
  }

  private Map<String, Object> deleteObject(JsonNode arguments) {
    String bucket = required(arguments, "bucket");
    String key = required(arguments, "key");
    DeleteObjectAns deleted = objectService.deleteObject(bucket, key, optional(arguments, "version_id"));
    return ordered(
        "bucket", bucket,
        "key", key,
        "deleteMarker", deleted.isDeleteMarker(),
        "versionId", deleted.getVersionId());
  }

  private Map<String, Object> createBucket(JsonNode arguments) {
    String name = required(arguments, "bucket");
    bucketNameValidator.validate(name);
    Bucket bucket = bucketService.createBucket(name, LocalS3Constants.DEFAULT_LOCATION_CONSTRAINT);
    return ordered(
        "name", bucket.getName(),
        "creationDate", Instant.ofEpochMilli(bucket.getCreationDate()).toString(),
        "region", bucket.regionOrDefault());
  }

  /*
   * Resources.
   */

  /**
   * The objects of every bucket, a page at a time. The cursor is the bucket and the key that the next page continues
   * after, so that a page is listed with {@code start-after} rather than by walking every bucket again.
   */
  private Map<String, Object> listResources(JsonNode params) {
    String cursor = params.path("cursor").asString(null);
    String afterBucket = null;
    String afterKey = null;
    if (cursor != null && !cursor.isEmpty()) {
      String decoded;
      try {
        decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
      } catch (IllegalArgumentException _) {
        throw new McpError(INVALID_PARAMS, "Invalid cursor.");
      }
      int separator = decoded.indexOf('\n');
      if (separator < 0) {
        throw new McpError(INVALID_PARAMS, "Invalid cursor.");
      }
      afterBucket = decoded.substring(0, separator);
      afterKey = decoded.substring(separator + 1);
    }

    List<String> buckets = bucketService.listBuckets().stream().map(Bucket::getName).sorted().toList();
    List<Map<String, Object>> resources = new ArrayList<>();
    String lastBucket = null;
    String lastKey = null;
    boolean more = false;
    for (String bucket : buckets) {
      if (afterBucket != null && bucket.compareTo(afterBucket) < 0) {
        continue;
      }
      String startAfter = bucket.equals(afterBucket) ? afterKey : null;
      int room = RESOURCE_PAGE_SIZE - resources.size();
      ListObjectsV2Ans listing;
      try {
        listing = objectService.listObjectsV2(bucket, null, null, null, false, room, null, startAfter);
      } catch (LocalS3Exception _) {
        // The bucket was deleted while it was listed.
        continue;
      }
      for (var object : listing.getObjects()) {
        resources.add(ordered(
            "uri", uri(bucket, object.key()),
            "name", object.key(),
            "title", bucket + "/" + object.key(),
            "mimeType", WebsiteContentTypes.of(object.key()).orElse("application/octet-stream"),
            "size", object.size()));
        lastBucket = bucket;
        lastKey = object.key();
      }
      if (listing.isTruncated() || resources.size() >= RESOURCE_PAGE_SIZE) {
        more = true;
        break;
      }
    }

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("resources", resources);
    if (more && lastBucket != null) {
      result.put("nextCursor", Base64.getUrlEncoder().withoutPadding()
          .encodeToString((lastBucket + "\n" + lastKey).getBytes(StandardCharsets.UTF_8)));
    }
    return result;
  }

  private Map<String, Object> readResource(JsonNode params) {
    String uri = params.path("uri").asString("");
    ObjectUri target = parseUri(uri);
    ObjectContent object = read(target.bucket(), target.key(), null, null, MAX_READ_BYTES);
    Map<String, Object> content = new LinkedHashMap<>();
    content.put("uri", uri);
    content.put("mimeType", object.contentType());
    if (object.text()) {
      content.put("text", new String(object.bytes(), StandardCharsets.UTF_8));
    } else {
      content.put("blob", Base64.getEncoder().encodeToString(object.bytes()));
    }
    return Map.of("contents", List.of(content));
  }

  private static ObjectUri parseUri(String uri) {
    if (!uri.startsWith(S3_SCHEME)) {
      throw new McpError(INVALID_PARAMS, "The URI of a resource must be s3://<bucket>/<key>, not " + uri + ".");
    }
    String path = uri.substring(S3_SCHEME.length());
    int slash = path.indexOf('/');
    if (slash <= 0 || slash == path.length() - 1) {
      throw new McpError(INVALID_PARAMS, "The URI of a resource must be s3://<bucket>/<key>, not " + uri + ".");
    }
    String key = path.substring(slash + 1);
    // The URIs this server lists are percent-encoded, but an agent may write a key as it is.
    try {
      key = UrlPaths.decode(key);
    } catch (IllegalArgumentException _) {
      // Not encoded, e.g. a key with a literal '%'.
    }
    return new ObjectUri(path.substring(0, slash), key);
  }

  /*
   * Reading an object.
   */

  /**
   * Read at most {@code maxBytes} of an object, from the start of the range if one is given.
   */
  private ObjectContent read(String bucket, String key, @Nullable String versionId, @Nullable Range range,
                             int maxBytes) {
    // One read, so the size, ETag and version describe the very bytes returned even if the object is overwritten.
    GetObjectAns object;
    try {
      object = objectService.getObject(bucket, key, GetObjectOptions.builder()
          .versionId(versionId)
          .range(range)
          .build());
    } catch (InvalidRangeException e) {
      // No range fits an empty object; reading it still returns its (empty) content.
      object = objectService.getObject(bucket, key, GetObjectOptions.builder().versionId(versionId).build());
      if (object.getSize() != 0) {
        closeQuietly(object.getContent());
        throw e;
      }
    }
    if (object.isDeleteMarker()) {
      throw new IllegalArgumentException("The object " + key + " was deleted.");
    }
    String contentType = contentType(object.getContentType(), key);
    long length = object.getSize();
    long start = 0;
    long size = length;
    String contentRange = object.getContentRange();
    if (contentRange != null) {
      // "bytes <start>-<end>/<size>"
      start = Long.parseLong(contentRange.substring("bytes ".length(), contentRange.indexOf('-')));
      size = Long.parseLong(contentRange.substring(contentRange.lastIndexOf('/') + 1));
    }

    byte[] bytes;
    try (InputStream in = object.getContent()) {
      bytes = length == 0 ? new byte[0] : in.readNBytes((int) Math.min(length, maxBytes));
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read " + key + ".", e);
    }
    if (length == 0) {
      return new ObjectContent(bytes, contentType, size, 0, -1, false, true, object.getEtag(),
          object.getVersionId());
    }
    boolean truncated = bytes.length < length;
    int textLength = truncated ? completeUtf8Length(bytes) : bytes.length;
    boolean text = isText(contentType, bytes, textLength);
    if (text && textLength < bytes.length) {
      bytes = Arrays.copyOf(bytes, textLength);
    }
    return new ObjectContent(bytes, contentType, size, start, start + bytes.length - 1, truncated, text,
        object.getEtag(), object.getVersionId());
  }

  /**
   * The length of {@code bytes} without a UTF-8 sequence cut off at the end, so that a read stopped at
   * {@code max_bytes} doesn't end in {@code U+FFFD}; the rest of the character starts the next range.
   */
  private static int completeUtf8Length(byte[] bytes) {
    for (int i = bytes.length - 1; i >= Math.max(0, bytes.length - 4); i--) {
      int b = bytes[i] & 0xFF;
      if ((b & 0xC0) == 0x80) {
        continue;
      }
      int sequence = b < 0x80 ? 1 : b >= 0xF0 ? 4 : b >= 0xE0 ? 3 : b >= 0xC0 ? 2 : 1;
      // Keep at least one byte: a range that starts in the middle of a character has nothing to back off to.
      return i + sequence > bytes.length && i > 0 ? i : bytes.length;
    }
    return bytes.length;
  }

  private static void closeQuietly(@Nullable InputStream in) {
    if (in == null) {
      return;
    }
    try {
      in.close();
    } catch (IOException _) {
      // Nothing was read from it.
    }
  }

  /**
   * The content type an object is read as: the one it was stored with, or that of its extension, like the console.
   */
  private static String contentType(@Nullable String stored, String key) {
    if (stored != null && !stored.startsWith("binary/octet-stream")
        && !stored.startsWith("application/octet-stream")) {
      return stored;
    }
    return WebsiteContentTypes.of(key).orElse("application/octet-stream");
  }

  /**
   * Whether content is handed to the model as text: content of a text type, or, of a type that says nothing, e.g.
   * {@code application/octet-stream}, valid UTF-8 without a NUL byte, which no text has. Decoding anything else
   * would replace bytes with {@code U+FFFD}, and the agent couldn't tell its content was changed.
   */
  private static boolean isText(String contentType, byte[] bytes, int length) {
    String type = contentType.toLowerCase(Locale.ROOT);
    if (type.startsWith("text/") || type.contains("json") || type.contains("xml") || type.contains("yaml")
        || type.contains("csv") || type.contains("javascript") || type.contains("markdown")
        || type.contains("toml") || type.contains("sql")) {
      return true;
    }
    if (!type.startsWith("application/octet-stream")) {
      return false;
    }
    for (int i = 0; i < length; i++) {
      if (bytes[i] == 0) {
        return false;
      }
    }
    try {
      StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes, 0, length));
      return true;
    } catch (CharacterCodingException _) {
      return false;
    }
  }

  /*
   * Guards.
   */

  private boolean isAuthorized(RouterHttpRequest request) {
    return accessKeyId == null
        || ServiceCredentials.hasBearer(request, secretAccessKey)
        || ServiceCredentials.hasBasic(request, accessKeyId, secretAccessKey);
  }

  /**
   * Whether a request was sent by a client rather than by a page of another site: a client sends no {@code Origin},
   * and a page of the loopback address or of the host the request addressed may.
   */
  private static boolean isAllowedOrigin(RouterHttpRequest request) {
    Optional<String> origin = request.header(HttpHeaderNames.ORIGIN.toString()).map(String::trim);
    if (origin.isEmpty() || origin.get().isEmpty()) {
      return true;
    }
    String originHost;
    try {
      originHost = new URI(origin.get()).getHost();
    } catch (URISyntaxException _) {
      return false;
    }
    if (originHost == null) {
      return false;
    }
    originHost = stripBrackets(originHost).toLowerCase(Locale.ROOT);
    if ("localhost".equals(originHost) || "127.0.0.1".equals(originHost) || "::1".equals(originHost)) {
      return true;
    }
    String host = request.header(HttpHeaderNames.HOST.toString()).map(String::trim).orElse("");
    return originHost.equals(hostName(host).toLowerCase(Locale.ROOT));
  }

  /**
   * The name of a {@code Host} header without its port, e.g. {@code ::1} of {@code [::1]:29090}.
   */
  private static String hostName(String host) {
    if (host.startsWith("[")) {
      int close = host.indexOf(']');
      return close < 0 ? host : host.substring(1, close);
    }
    int colon = host.indexOf(':');
    return colon < 0 ? host : host.substring(0, colon);
  }

  private static String stripBrackets(String host) {
    return host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
  }

  /*
   * Building messages.
   */

  private static String uri(String bucket, String key) {
    return S3_SCHEME + bucket + "/" + S3ObjectUtils.urlEncode(key, true);
  }

  private static String describe(LocalS3Exception e) {
    return e.getS3ErrorCode().code() + ": " + Objects.toString(e.getMessage(), e.getS3ErrorCode().description());
  }

  /**
   * A result of a tool with its structured content, and the same as JSON text for the clients that read text alone.
   */
  private Map<String, Object> structured(Map<String, Object> result) {
    return ordered(
        "content", List.of(text(objectMapper.writeValueAsString(result))),
        "structuredContent", result,
        "isError", false);
  }

  private static Map<String, Object> toolError(String message) {
    return ordered("content", List.of(text(message)), "isError", true);
  }

  private static Map<String, Object> text(String text) {
    return ordered("type", "text", "text", text);
  }

  private static Map<String, Object> error(@Nullable JsonNode id, int code, String message) {
    return ordered("jsonrpc", JSONRPC, "id", id, "error", ordered("code", code, "message", message));
  }

  private void json(RouterHttpResponse response, HttpResponseStatus status, Object body) {
    response.status(status)
        .putHeader(HttpHeaderNames.CONTENT_TYPE.toString(), HttpHeaderValues.APPLICATION_JSON)
        .putHeader(HttpHeaderNames.CACHE_CONTROL.toString(), "no-store")
        .write(objectMapper.writeValueAsString(body));
    ResponseUtils.addDateHeader(response);
    ResponseUtils.addServerHeader(response);
  }

  /**
   * A map that keeps the order its entries are given in, and their {@code null} values, which {@code Map.of} allows
   * neither of.
   */
  private static Map<String, Object> ordered(Object... entries) {
    Map<String, Object> map = new LinkedHashMap<>();
    for (int i = 0; i < entries.length; i += 2) {
      map.put((String) entries[i], entries[i + 1]);
    }
    return map;
  }

  /*
   * Reading the arguments of a tool.
   */

  private static String required(JsonNode arguments, String name) {
    String value = optional(arguments, name);
    if (value == null || value.isEmpty()) {
      throw new IllegalArgumentException(name + " is required.");
    }
    return value;
  }

  private static @Nullable String optional(JsonNode arguments, String name) {
    JsonNode value = arguments.get(name);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isValueNode()) {
      throw new IllegalArgumentException(name + " must be a string.");
    }
    return value.asString();
  }

  private static long number(JsonNode arguments, String name, long defaultValue) {
    JsonNode value = arguments.get(name);
    if (value == null || value.isNull()) {
      return defaultValue;
    }
    if (value.isIntegralNumber()) {
      return value.asLong();
    }
    try {
      return Long.parseLong(value.asString().trim());
    } catch (NumberFormatException _) {
      throw new IllegalArgumentException(name + " must be a whole number, not " + value + ".");
    }
  }

  /*
   * Declaring the tools.
   */

  private static Map<String, Object> tool(String name, String title, String description,
                                          Map<String, Object> properties, List<String> required,
                                          Map<String, Object> annotations) {
    Map<String, Object> schema = ordered("type", "object", "properties", properties);
    if (!required.isEmpty()) {
      schema.put("required", required);
    }
    Map<String, Object> withTitle = new LinkedHashMap<>(annotations);
    withTitle.put("title", title);
    return ordered("name", name, "title", title, "description", description, "inputSchema", schema,
        "annotations", withTitle);
  }

  private static Map<String, Object> properties(Object... entries) {
    return ordered(entries);
  }

  private static Map<String, Object> string(String description) {
    return ordered("type", "string", "description", description);
  }

  private static Map<String, Object> integer(String description) {
    return ordered("type", "integer", "description", description);
  }

  private static Map<String, Object> enumeration(String description, String... values) {
    return ordered("type", "string", "enum", List.of(values), "description", description);
  }

  private static Map<String, Object> readOnly() {
    return ordered("readOnlyHint", true, "openWorldHint", false);
  }

  private static Map<String, Object> annotations(boolean readOnly, boolean destructive, boolean idempotent) {
    return ordered("readOnlyHint", readOnly, "destructiveHint", destructive, "idempotentHint", idempotent,
        "openWorldHint", false);
  }

  /**
   * An error of the protocol, which is answered as the error of the JSON-RPC response.
   */
  private static final class McpError extends RuntimeException {

    private final int code;

    McpError(int code, String message) {
      super(message, null, false, false);
      this.code = code;
    }

  }

  private record ObjectUri(String bucket, String key) {
  }

  /**
   * What a read returns.
   *
   * @param start the first byte returned; {@code end} is the last one, inclusive, and {@code -1} for an empty object.
   * @param truncated whether the read stopped before the end of the range it was asked for.
   * @param text whether the content is handed out as text rather than base64.
   */
  private record ObjectContent(byte[] bytes, String contentType, long size, long start, long end, boolean truncated,
                               boolean text, @Nullable String etag, @Nullable String versionId) {
  }

}
