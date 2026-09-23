package com.robothy.s3.rest.handler.s3tables;

import com.robothy.netty.http.HttpRequest;
import com.robothy.netty.http.HttpRequestHandler;
import com.robothy.netty.http.HttpResponse;
import com.robothy.s3.core.exception.LocalS3Exception;
import com.robothy.s3.core.iceberg.IcebergCatalogException;
import com.robothy.s3.core.iceberg.IcebergJson;
import com.robothy.s3.core.s3tables.S3TablesArn;
import com.robothy.s3.core.s3tables.S3TablesException;
import com.robothy.s3.core.s3tables.S3TablesService;
import com.robothy.s3.rest.constants.AmzHeaderNames;
import com.robothy.s3.rest.utils.RequestPaths;
import com.robothy.s3.rest.utils.SigV4Requests;
import com.robothy.s3.rest.utils.ResponseUtils;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_Operations_Amazon_S3_Tables.html">Amazon S3
 * Tables</a> endpoint of LocalS3, served on the port of the S3 service, so that one process is the object store, the
 * catalog and the table bucket API of a lakehouse:
 *
 * <pre>{@code
 * S3TablesClient tables = S3TablesClient.builder()
 *     .endpointOverride(URI.create("http://localhost:29090"))
 *     .region(Region.US_EAST_1)
 *     .credentialsProvider(...)
 *     .build();
 * }</pre>
 *
 * <p><b>How a request of this API is told from an Amazon S3 one.</b> Its paths are not a shape that could be told
 * apart: {@code PUT /buckets} of {@code CreateTableBucket} is, as a path, the {@code CreateBucket} of a bucket named
 * {@code buckets}, and {@code GET /tables/...} is a listing of a bucket named {@code tables}. What does tell them
 * apart is the signature. Every AWS SDK signs a request of this API for the {@code s3tables} service, and that service
 * is in the credential scope of the {@code Authorization} header, so the scope is read before a bucket is parsed —
 * see {@link SigV4Requests#signingService}. The signature is then <em>verified</em> for {@code s3tables} too, so a
 * request cannot claim the scope to reach this API and be accepted with the wrong key.
 *
 * <p>A client that signs <em>nothing</em> — one built with an {@code AnonymousCredentialsProvider}, which is what a
 * test points at a LocalS3 that verifies no signatures — has no credential scope to be told apart by, so it reaches
 * this API by path instead, under {@value #PATH_PREFIX}:
 *
 * <pre>{@code
 * S3TablesClient.builder().endpointOverride(URI.create("http://localhost:29090/s3tables"))
 * }</pre>
 *
 * <p>Both ways in are the same API and the same table buckets; which one a client uses is only a matter of what it
 * carries to be recognized by.
 *
 * <p>Like {@linkplain com.robothy.s3.rest.handler.iceberg.IcebergCatalogController}, this is one handler for the whole
 * API rather than one per operation: the API is a REST one, addressed by path, and the routes of the S3 router are
 * matched against {@code /bucket/key} shapes that these paths are not.
 */
public final class S3TablesController implements HttpRequestHandler {

  private static final Logger log = LoggerFactory.getLogger(S3TablesController.class);

  /**
   * The prefix of the operations in the statistics of the service, e.g. {@code S3TablesCreateTable}.
   *
   * <p>Prefixed because the names of this API are not its own: {@code TagResource} is an operation of S3 Vectors too,
   * and the statistics of a service that answers both would otherwise count them together.
   */
  private static final String OPERATION_PREFIX = "S3Tables";

  /**
   * The path that a request of this API may be addressed under instead of being told apart by its credential scope,
   * which is how an <em>unsigned</em> client reaches it.
   *
   * <p>A client that was given no credentials — an {@code AnonymousCredentialsProvider}, which is what a test points
   * at a LocalS3 that verifies nothing — sends no {@code Authorization} header at all, so there is no credential scope
   * to read and nothing else in the request says which of the two APIs it means. Such a client sets its endpoint to
   * {@code http://localhost:29090/s3tables}, and the AWS SDKs keep the path of an endpoint override in front of the
   * path of every request, which is then unambiguous.
   *
   * <p>Like the {@code /iceberg} of the catalog, this shadows a bucket: a path-style request for the objects of a
   * bucket named {@code s3tables} reaches this API instead. A service that serves table buckets should keep its
   * buckets off that name.
   */
  public static final String PATH_PREFIX = "/s3tables";

  /**
   * The operation that a request of this API whose path names no resource is recorded as.
   */
  public static final String UNKNOWN_OPERATION = OPERATION_PREFIX + "UnknownOperation";

  private static final String CONTENT_TYPE = "application/json";

  private static final int MAX_BODY_LENGTH = 16 * 1024 * 1024;

  private final S3TablesService s3Tables;

  /**
   * Create the controller.
   *
   * @param s3Tables the table buckets of the service.
   */
  public S3TablesController(S3TablesService s3Tables) {
    this.s3Tables = Objects.requireNonNull(s3Tables, "s3Tables");
  }

  /**
   * Whether a request is addressed at the S3 Tables API, which its credential scope says.
   *
   * @param request the request.
   * @return {@code true} if it is signed for the {@code s3tables} service.
   */
  public static boolean isS3TablesRequest(HttpRequest request) {
    return S3TablesArn.SERVICE.equals(SigV4Requests.signingService(request)) || isPathPrefixed(request);
  }

  /**
   * Whether a request is addressed at this API by its path, i.e. under {@value #PATH_PREFIX}, which is how a client
   * that signs nothing reaches it.
   */
  private static boolean isPathPrefixed(HttpRequest request) {
    String path = request.getPath();
    return path != null && (path.equals(PATH_PREFIX) || path.startsWith(PATH_PREFIX + "/"));
  }

  /**
   * The operation that a request of this API is recorded as in the statistics of the service, e.g.
   * {@code S3TablesCreateTable}.
   *
   * @param request the request.
   * @return the operation.
   */
  public static String operation(HttpRequest request) {
    try {
      String operation = operationOf(request.getMethod(), segments(request));
      return operation.isEmpty() ? UNKNOWN_OPERATION : OPERATION_PREFIX + operation;
    } catch (RuntimeException e) {
      return UNKNOWN_OPERATION;
    }
  }

  @Override
  public void handle(HttpRequest request, HttpResponse response) {
    try {
      dispatch(request, response);
    } catch (S3TablesException e) {
      writeError(response, e.status(), e.errorType(), e.getMessage());
    } catch (IcebergCatalogException e) {
      // The catalog under a table bucket speaks the Iceberg REST protocol's failures; a client of this API reads the
      // ones of this API, so they are translated rather than leaked.
      writeError(response, e.status(), errorTypeOf(e.status()), e.getMessage());
    } catch (LocalS3Exception e) {
      writeError(response, 400, "BadRequestException", String.valueOf(e.getMessage()));
    } catch (RuntimeException e) {
      log.warn("The S3 Tables API failed to answer {} {}.", request.getMethod(), request.getPath(), e);
      writeError(response, 500, "InternalServerErrorException", String.valueOf(e.getMessage()));
    }
  }

  private void dispatch(HttpRequest request, HttpResponse response) {
    List<String> path = segments(request);
    switch (operationOf(request.getMethod(), path)) {
      /*
       * Table buckets.
       */
      case "CreateTableBucket" -> writeJson(response, 200, s3Tables.createTableBucket(body(request)));
      case "ListTableBuckets" -> writeJson(response, 200, s3Tables.listTableBuckets(parameter(request, "prefix"),
          parameter(request, "continuationToken"), intParameter(request, "maxBuckets"),
          parameter(request, "type")));
      case "GetTableBucket" -> writeJson(response, 200, s3Tables.getTableBucket(path.get(1)));
      case "DeleteTableBucket" -> {
        s3Tables.deleteTableBucket(path.get(1));
        writeStatus(response, 204);
      }

      /*
       * Namespaces.
       */
      case "CreateNamespace" -> writeJson(response, 200, s3Tables.createNamespace(path.get(1), body(request)));
      case "ListNamespaces" -> writeJson(response, 200, s3Tables.listNamespaces(path.get(1),
          parameter(request, "prefix"), parameter(request, "continuationToken"),
          intParameter(request, "maxNamespaces")));
      case "GetNamespace" -> writeJson(response, 200, s3Tables.getNamespace(path.get(1), path.get(2)));
      case "DeleteNamespace" -> {
        s3Tables.deleteNamespace(path.get(1), path.get(2));
        writeStatus(response, 204);
      }

      /*
       * Tables.
       */
      case "CreateTable" -> writeJson(response, 200,
          s3Tables.createTable(path.get(1), path.get(2), body(request)));
      case "ListTables" -> writeJson(response, 200, s3Tables.listTables(path.get(1),
          parameter(request, "namespace"), parameter(request, "prefix"),
          parameter(request, "continuationToken"), intParameter(request, "maxTables")));
      case "GetTable" -> writeJson(response, 200, s3Tables.getTable(parameter(request, "tableBucketARN"),
          parameter(request, "namespace"), parameter(request, "name"), parameter(request, "tableArn")));
      case "DeleteTable" -> {
        s3Tables.deleteTable(path.get(1), path.get(2), path.get(3), parameter(request, "versionToken"));
        writeStatus(response, 204);
      }
      case "RenameTable" -> {
        s3Tables.renameTable(path.get(1), path.get(2), path.get(3), body(request));
        writeStatus(response, 204);
      }
      case "GetTableMetadataLocation" -> writeJson(response, 200,
          s3Tables.getTableMetadataLocation(path.get(1), path.get(2), path.get(3)));
      case "UpdateTableMetadataLocation" -> writeJson(response, 200,
          s3Tables.updateTableMetadataLocation(path.get(1), path.get(2), path.get(3), body(request)));

      /*
       * Tags.
       */
      case "ListTagsForResource" -> writeJson(response, 200, s3Tables.listTagsForResource(path.get(1)));
      case "TagResource" -> {
        s3Tables.tagResource(path.get(1), body(request));
        writeJson(response, 200, IcebergJson.newObject());
      }
      case "UntagResource" -> {
        s3Tables.untagResource(path.get(1), RequestPaths.queryValues(request, "tagKeys"));
        writeStatus(response, 204);
      }

      /*
       * Encryption and storage class.
       */
      case "PutTableBucketEncryption" -> {
        s3Tables.putTableBucketEncryption(path.get(1), body(request));
        writeJson(response, 200, IcebergJson.newObject());
      }
      case "GetTableBucketEncryption" -> writeJson(response, 200, s3Tables.getTableBucketEncryption(path.get(1)));
      case "DeleteTableBucketEncryption" -> {
        s3Tables.deleteTableBucketEncryption(path.get(1));
        writeStatus(response, 204);
      }
      case "GetTableEncryption" -> writeJson(response, 200,
          s3Tables.getTableEncryption(path.get(1), path.get(2), path.get(3)));
      case "PutTableBucketStorageClass" -> {
        s3Tables.putTableBucketStorageClass(path.get(1), body(request));
        writeJson(response, 200, IcebergJson.newObject());
      }
      case "GetTableBucketStorageClass" -> writeJson(response, 200,
          s3Tables.getTableBucketStorageClass(path.get(1)));
      case "GetTableStorageClass" -> writeJson(response, 200,
          s3Tables.getTableStorageClass(path.get(1), path.get(2), path.get(3)));

      /*
       * Resource policies.
       */
      case "PutTableBucketPolicy" -> {
        s3Tables.putTableBucketPolicy(path.get(1), body(request));
        writeJson(response, 200, IcebergJson.newObject());
      }
      case "GetTableBucketPolicy" -> writeJson(response, 200, s3Tables.getTableBucketPolicy(path.get(1)));
      case "DeleteTableBucketPolicy" -> {
        s3Tables.deleteTableBucketPolicy(path.get(1));
        writeStatus(response, 204);
      }
      case "PutTablePolicy" -> {
        s3Tables.putTablePolicy(path.get(1), path.get(2), path.get(3), body(request));
        writeJson(response, 200, IcebergJson.newObject());
      }
      case "GetTablePolicy" -> writeJson(response, 200,
          s3Tables.getTablePolicy(path.get(1), path.get(2), path.get(3)));
      case "DeleteTablePolicy" -> {
        s3Tables.deleteTablePolicy(path.get(1), path.get(2), path.get(3));
        writeStatus(response, 204);
      }

      /*
       * Maintenance and metrics.
       */
      case "PutTableBucketMaintenanceConfiguration" -> {
        s3Tables.putTableBucketMaintenanceConfiguration(path.get(1), path.get(3), body(request));
        writeStatus(response, 204);
      }
      case "GetTableBucketMaintenanceConfiguration" -> writeJson(response, 200,
          s3Tables.getTableBucketMaintenanceConfiguration(path.get(1)));
      case "PutTableMaintenanceConfiguration" -> {
        s3Tables.putTableMaintenanceConfiguration(path.get(1), path.get(2), path.get(3), path.get(5), body(request));
        writeStatus(response, 204);
      }
      case "GetTableMaintenanceConfiguration" -> writeJson(response, 200,
          s3Tables.getTableMaintenanceConfiguration(path.get(1), path.get(2), path.get(3)));
      case "GetTableMaintenanceJobStatus" -> writeJson(response, 200,
          s3Tables.getTableMaintenanceJobStatus(path.get(1), path.get(2), path.get(3)));
      case "PutTableBucketMetricsConfiguration" -> {
        s3Tables.putTableBucketMetricsConfiguration(path.get(1));
        writeStatus(response, 204);
      }
      case "GetTableBucketMetricsConfiguration" -> writeJson(response, 200,
          s3Tables.getTableBucketMetricsConfiguration(path.get(1)));
      case "DeleteTableBucketMetricsConfiguration" -> {
        s3Tables.deleteTableBucketMetricsConfiguration(path.get(1));
        writeStatus(response, 204);
      }

      /*
       * Record expiration and replication.
       */
      case "PutTableRecordExpirationConfiguration" -> {
        s3Tables.putTableRecordExpirationConfiguration(requiredParameter(request, "tableArn"), body(request));
        writeStatus(response, 204);
      }
      case "GetTableRecordExpirationConfiguration" -> writeJson(response, 200,
          s3Tables.getTableRecordExpirationConfiguration(requiredParameter(request, "tableArn")));
      case "GetTableRecordExpirationJobStatus" -> writeJson(response, 200,
          s3Tables.getTableRecordExpirationJobStatus(requiredParameter(request, "tableArn")));
      case "PutTableBucketReplication" -> writeJson(response, 200,
          s3Tables.putTableBucketReplication(requiredParameter(request, "tableBucketARN"),
              parameter(request, "versionToken"), body(request)));
      case "GetTableBucketReplication" -> writeJson(response, 200,
          s3Tables.getTableBucketReplication(requiredParameter(request, "tableBucketARN")));
      case "DeleteTableBucketReplication" -> {
        s3Tables.deleteTableBucketReplication(requiredParameter(request, "tableBucketARN"));
        writeStatus(response, 204);
      }
      case "PutTableReplication" -> writeJson(response, 200,
          s3Tables.putTableReplication(requiredParameter(request, "tableArn"),
              parameter(request, "versionToken"), body(request)));
      case "GetTableReplication" -> writeJson(response, 200,
          s3Tables.getTableReplication(requiredParameter(request, "tableArn")));
      case "DeleteTableReplication" -> {
        s3Tables.deleteTableReplication(requiredParameter(request, "tableArn"),
            parameter(request, "versionToken"));
        writeStatus(response, 204);
      }
      case "GetTableReplicationStatus" -> writeJson(response, 200,
          s3Tables.getTableReplicationStatus(requiredParameter(request, "tableArn")));

      default -> throw S3TablesException.notFound("No route for " + request.getMethod() + " " + request.getPath()
          + " in the S3 Tables API of LocalS3.");
    }
  }

  /**
   * The operation that a method and a path name. It is the one place the routes are declared, so that the statistics
   * of the service and the dispatch above can't drift apart.
   *
   * @param method the HTTP method.
   * @param path the segments of the path, each one decoded.
   * @return the name of the operation, without the {@value #OPERATION_PREFIX} prefix; {@code null} if no route
   *     matches, which the dispatch answers with a 404.
   */
  private static String operationOf(HttpMethod method, List<String> path) {
    boolean get = HttpMethod.GET.equals(method);
    boolean put = HttpMethod.PUT.equals(method);
    boolean post = HttpMethod.POST.equals(method);
    boolean delete = HttpMethod.DELETE.equals(method);
    int size = path.size();
    if (size == 0) {
      return "";
    }

    switch (path.get(0)) {
      case "buckets" -> {
        if (size == 1) {
          return put ? "CreateTableBucket" : get ? "ListTableBuckets" : "";
        }
        if (size == 2) {
          return get ? "GetTableBucket" : delete ? "DeleteTableBucket" : "";
        }
        if (size == 3) {
          return switch (path.get(2)) {
            case "encryption" -> put ? "PutTableBucketEncryption" : get ? "GetTableBucketEncryption"
                : delete ? "DeleteTableBucketEncryption" : "";
            case "policy" -> put ? "PutTableBucketPolicy" : get ? "GetTableBucketPolicy"
                : delete ? "DeleteTableBucketPolicy" : "";
            case "metrics" -> put ? "PutTableBucketMetricsConfiguration"
                : get ? "GetTableBucketMetricsConfiguration"
                : delete ? "DeleteTableBucketMetricsConfiguration" : "";
            case "maintenance" -> get ? "GetTableBucketMaintenanceConfiguration" : "";
            case "storage-class" -> put ? "PutTableBucketStorageClass" : get ? "GetTableBucketStorageClass" : "";
            default -> "";
          };
        }
        if (size == 4 && "maintenance".equals(path.get(2)) && put) {
          return "PutTableBucketMaintenanceConfiguration";
        }
      }
      case "namespaces" -> {
        if (size == 2) {
          return put ? "CreateNamespace" : get ? "ListNamespaces" : "";
        }
        if (size == 3) {
          return get ? "GetNamespace" : delete ? "DeleteNamespace" : "";
        }
      }
      case "tables" -> {
        if (size == 2) {
          return get ? "ListTables" : "";
        }
        if (size == 3) {
          return put ? "CreateTable" : "";
        }
        if (size == 4) {
          // A table is read with GET /get-table, which carries its identifier in the query; the path addresses it
          // only for the operations that change it.
          return delete ? "DeleteTable" : "";
        }
        if (size == 5) {
          return switch (path.get(4)) {
            case "metadata-location" -> get ? "GetTableMetadataLocation"
                : put ? "UpdateTableMetadataLocation" : "";
            case "rename" -> put ? "RenameTable" : "";
            case "encryption" -> get ? "GetTableEncryption" : "";
            case "storage-class" -> get ? "GetTableStorageClass" : "";
            case "maintenance" -> get ? "GetTableMaintenanceConfiguration" : "";
            case "maintenance-job-status" -> get ? "GetTableMaintenanceJobStatus" : "";
            case "policy" -> put ? "PutTablePolicy" : get ? "GetTablePolicy"
                : delete ? "DeleteTablePolicy" : "";
            default -> "";
          };
        }
        if (size == 6 && "maintenance".equals(path.get(4)) && put) {
          return "PutTableMaintenanceConfiguration";
        }
      }
      case "get-table" -> {
        if (size == 1 && get) {
          return "GetTable";
        }
      }
      case "tag" -> {
        if (size == 2) {
          return get ? "ListTagsForResource" : post ? "TagResource" : delete ? "UntagResource" : "";
        }
      }
      case "table-bucket-replication" -> {
        if (size == 1) {
          return put ? "PutTableBucketReplication" : get ? "GetTableBucketReplication"
              : delete ? "DeleteTableBucketReplication" : "";
        }
      }
      case "table-replication" -> {
        if (size == 1) {
          return put ? "PutTableReplication" : get ? "GetTableReplication"
              : delete ? "DeleteTableReplication" : "";
        }
      }
      case "replication-status" -> {
        if (size == 1 && get) {
          return "GetTableReplicationStatus";
        }
      }
      case "table-record-expiration" -> {
        if (size == 1) {
          return put ? "PutTableRecordExpirationConfiguration" : get ? "GetTableRecordExpirationConfiguration" : "";
        }
      }
      case "table-record-expiration-job-status" -> {
        if (size == 1 && get) {
          return "GetTableRecordExpirationJobStatus";
        }
      }
      default -> {
        return "";
      }
    }
    return "";
  }

  /**
   * The segments of the path of a request, each one decoded.
   *
   * <p>Every path of this API but a handful carries the ARN of a table bucket as one segment, and an ARN holds both
   * {@code :} and {@code /}, which a client escapes. The raw path is split before it is decoded so that the {@code /}
   * inside an ARN doesn't become a segment boundary; see {@linkplain RequestPaths}.
   */
  private static List<String> segments(HttpRequest request) {
    List<String> segments;
    try {
      segments = RequestPaths.decodedSegments(RequestPaths.rawPath(request));
    } catch (IllegalArgumentException e) {
      throw S3TablesException.badRequest(e.getMessage());
    }
    // A client that reached this API by path rather than by credential scope carries the prefix of the endpoint it was
    // configured with in front of the path of the operation; the operations are the same either way.
    if (!segments.isEmpty() && PATH_PREFIX.equals("/" + segments.get(0))) {
      return segments.subList(1, segments.size());
    }
    return segments;
  }

  private static String parameter(HttpRequest request, String name) {
    return request.parameter(name).filter(value -> !value.isBlank()).orElse(null);
  }

  private static String requiredParameter(HttpRequest request, String name) {
    String value = parameter(request, name);
    if (value == null) {
      throw S3TablesException.badRequest("The request is missing the required query parameter '" + name + "'.");
    }
    return value;
  }

  private static Integer intParameter(HttpRequest request, String name) {
    String value = parameter(request, name);
    if (value == null) {
      return null;
    }
    try {
      return Integer.valueOf(value.trim());
    } catch (NumberFormatException e) {
      throw S3TablesException.badRequest("The query parameter '" + name + "' must be a number; got: " + value + ".");
    }
  }

  /**
   * The JSON body of a request; an empty object for a request without one.
   */
  private static ObjectNode body(HttpRequest request) {
    ByteBuf body = request.getBody();
    if (body == null || body.readableBytes() == 0) {
      return IcebergJson.newObject();
    }
    if (body.readableBytes() > MAX_BODY_LENGTH) {
      throw S3TablesException.badRequest("The request body is too large.");
    }
    byte[] bytes = new byte[body.readableBytes()];
    body.getBytes(body.readerIndex(), bytes);
    try {
      return IcebergJson.read(new String(bytes, StandardCharsets.UTF_8));
    } catch (IcebergCatalogException e) {
      throw S3TablesException.badRequest(e.getMessage());
    }
  }

  private static void writeJson(HttpResponse response, int status, JsonNode body) {
    response.status(HttpResponseStatus.valueOf(status))
        .putHeader(HttpHeaderNames.CONTENT_TYPE.toString(), CONTENT_TYPE)
        .write(IcebergJson.write(body));
    ResponseUtils.addDateHeader(response);
    ResponseUtils.addAmzRequestId(response);
  }

  private static void writeStatus(HttpResponse response, int status) {
    response.status(HttpResponseStatus.valueOf(status));
    ResponseUtils.addDateHeader(response);
    ResponseUtils.addAmzRequestId(response);
  }

  /**
   * Answer a failure in the shape a {@code rest-json} client reads: the name of the modelled exception in
   * {@code x-amzn-errortype}, which is what the SDK raises an exception class by, and the message in the body.
   *
   * @param response the response.
   * @param status the HTTP status.
   * @param errorType the name of the modelled exception.
   * @param message the message.
   */
  public static void writeError(HttpResponse response, int status, String errorType, String message) {
    ObjectNode body = IcebergJson.newObject();
    body.put("message", Objects.toString(message, ""));
    // Sent in the body as well, which is the other place a rest-json client looks for the name of the exception.
    body.put("__type", errorType);
    response.status(HttpResponseStatus.valueOf(status))
        .putHeader(AmzHeaderNames.X_AMZN_ERRORTYPE, errorType)
        .putHeader(HttpHeaderNames.CONTENT_TYPE.toString(), CONTENT_TYPE)
        .write(IcebergJson.write(body));
    ResponseUtils.addDateHeader(response);
    ResponseUtils.addAmzRequestId(response);
  }

  /**
   * The modelled exception of this API that answers an HTTP status, which a failure raised by the catalog under a
   * table bucket is translated through.
   */
  private static String errorTypeOf(int status) {
    return switch (status) {
      case 400 -> "BadRequestException";
      case 403 -> "ForbiddenException";
      case 404 -> "NotFoundException";
      case 405 -> "MethodNotAllowedException";
      case 409 -> "ConflictException";
      case 429 -> "TooManyRequestsException";
      default -> "InternalServerErrorException";
    };
  }

}
