package com.robothy.s3.rest.handler.iceberg;

import com.robothy.netty.http.HttpRequest;
import com.robothy.netty.http.HttpRequestHandler;
import com.robothy.netty.http.HttpResponse;
import com.robothy.s3.core.iceberg.IcebergCatalogException;
import com.robothy.s3.core.iceberg.IcebergCatalogService;
import com.robothy.s3.core.iceberg.IcebergIdentifier;
import com.robothy.s3.core.iceberg.IcebergJson;
import com.robothy.s3.rest.utils.ResponseUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The <a href="https://iceberg.apache.org/spec/#rest-catalog">Iceberg REST catalog</a> of LocalS3, served on the port
 * of the S3 service under {@value #PATH_PREFIX}, so that one process is both the catalog and the storage of a
 * lakehouse:
 *
 * <pre>{@code
 * RESTCatalog catalog = new RESTCatalog();
 * catalog.initialize("local", Map.of("uri", "http://localhost:29090/iceberg"));
 * }</pre>
 *
 * <p>Without this, testing Iceberg locally means running a catalog of its own — Polaris, Lakekeeper, Nessie or a JDBC
 * catalog — beside the object store, because a catalog is what turns a pile of files into a table. LocalS3 answers
 * both halves, and the catalog writes the metadata files straight through the S3 services rather than over HTTP to
 * itself.
 *
 * <p>Like {@linkplain com.robothy.s3.rest.handler.StsController} and
 * {@linkplain com.robothy.s3.rest.handler.KmsController}, this is told apart from an S3 request by its shape, here its
 * path, before the bucket of the request is parsed: the paths of the catalog are nested far deeper than the
 * {@code /bucket/key} that the S3 router reads, so they are routed here instead.
 *
 * <p><b>Requests are not signed.</b> The Iceberg REST protocol carries its own credentials — an OAuth2 bearer token —
 * rather than an AWS signature, so a catalog request is answered whatever it carries, and a {@code Bearer} token is
 * accepted without being checked. A LocalS3 with credentials still verifies the S3 requests that the engine then makes
 * with the credentials this catalog vends, which is where a test that asserts about signing has something to assert.
 */
public final class IcebergCatalogController implements HttpRequestHandler {

  private static final Logger log = LoggerFactory.getLogger(IcebergCatalogController.class);

  /**
   * The path that every request of the catalog starts with. It is a path of the service rather than a bucket: a
   * bucket named {@code iceberg} is still reachable, at {@code /iceberg} without a {@code /v1} under it, but a
   * service that serves the catalog should keep its buckets off this name.
   */
  public static final String PATH_PREFIX = "/iceberg/";

  /**
   * The path that the resources of the catalog hang off, which is {@value #PATH_PREFIX} and the version of the API.
   */
  static final String API_PREFIX = "/iceberg/v1";

  /**
   * The operation that a request of the catalog whose path names no resource is recorded as.
   */
  static final String UNKNOWN_OPERATION = "IcebergUnknownOperation";

  private static final String CONTENT_TYPE = "application/json";

  private static final int MAX_BODY_LENGTH = 64 * 1024 * 1024;

  private final IcebergCatalogService catalog;

  private final IcebergClientConfig clientConfig;

  /**
   * Create the controller.
   *
   * @param catalog the catalog of the service.
   * @param clientConfig the settings that the catalog hands its clients, so that an engine configures its
   *     {@code FileIO} from the catalog rather than by hand.
   */
  public IcebergCatalogController(IcebergCatalogService catalog, IcebergClientConfig clientConfig) {
    this.catalog = Objects.requireNonNull(catalog, "catalog");
    this.clientConfig = Objects.requireNonNull(clientConfig, "clientConfig");
  }

  /**
   * Whether a request is addressed at the Iceberg catalog, which is what its path says.
   *
   * @param request the request.
   * @return {@code true} if it is a catalog request.
   */
  public static boolean isIcebergRequest(HttpRequest request) {
    String path = request.getPath();
    return path != null && path.startsWith(PATH_PREFIX);
  }

  /**
   * The operation that a catalog request is recorded as in the statistics of the service, e.g.
   * {@code IcebergLoadTable}.
   *
   * @param request the request.
   * @return the operation.
   */
  public static String operation(HttpRequest request) {
    try {
      return operationOf(request.getMethod(), segments(request));
    } catch (RuntimeException e) {
      return UNKNOWN_OPERATION;
    }
  }

  @Override
  public void handle(HttpRequest request, HttpResponse response) {
    try {
      dispatch(request, response);
    } catch (IcebergCatalogException e) {
      writeError(response, e.status(), e.type(), e.getMessage());
    } catch (RuntimeException e) {
      log.warn("The Iceberg catalog failed to answer {} {}.", request.getMethod(), request.getPath(), e);
      writeError(response, 500, "ServiceFailureException", String.valueOf(e.getMessage()));
    }
  }

  private void dispatch(HttpRequest request, HttpResponse response) {
    HttpMethod method = request.getMethod();
    List<String> path = segments(request);
    switch (operationOf(method, path)) {
      case "IcebergGetConfig" -> writeJson(response, 200, clientConfig.configResponse(request, catalog.warehouse()));
      case "IcebergGetToken" -> writeJson(response, 200, token());
      case "IcebergListNamespaces" -> writeJson(response, 200,
          catalog.listNamespaces(IcebergIdentifier.parseNamespace(request.parameter("parent").orElse(null))));
      case "IcebergCreateNamespace" -> writeJson(response, 200, catalog.createNamespace(body(request)));
      case "IcebergLoadNamespace" -> writeJson(response, 200, catalog.loadNamespace(namespace(path)));
      case "IcebergNamespaceExists" ->
          writeStatus(response, catalog.namespaceExists(namespace(path)) ? 204 : 404);
      case "IcebergDropNamespace" -> {
        catalog.dropNamespace(namespace(path));
        writeStatus(response, 204);
      }
      case "IcebergUpdateNamespaceProperties" -> writeJson(response, 200,
          catalog.updateNamespaceProperties(namespace(path), body(request)));
      case "IcebergListTables" -> writeJson(response, 200, catalog.listTables(namespace(path), false));
      case "IcebergCreateTable" -> writeJson(response, 200,
          withConfig(request, catalog.createTable(namespace(path), body(request))));
      case "IcebergRegisterTable" -> writeJson(response, 200,
          withConfig(request, catalog.registerTable(namespace(path), body(request), false)));
      case "IcebergRegisterView" -> writeJson(response, 200,
          catalog.registerTable(namespace(path), body(request), true));
      case "IcebergLoadTable" -> writeJson(response, 200,
          withConfig(request, catalog.loadTable(identifier(path), false)));
      case "IcebergTableExists" ->
          writeStatus(response, catalog.tableExists(identifier(path), false) ? 204 : 404);
      case "IcebergUpdateTable" -> writeJson(response, 200,
          withConfig(request, catalog.updateTable(identifier(path), body(request), false)));
      case "IcebergDropTable" -> {
        catalog.dropTable(identifier(path), false, purgeRequested(request));
        writeStatus(response, 204);
      }
      case "IcebergRenameTable" -> {
        catalog.renameTable(body(request), false);
        writeStatus(response, 204);
      }
      case "IcebergReportMetrics" -> writeStatus(response, 204);
      case "IcebergListViews" -> writeJson(response, 200, catalog.listTables(namespace(path), true));
      case "IcebergCreateView" -> writeJson(response, 200, catalog.createView(namespace(path), body(request)));
      case "IcebergLoadView" -> writeJson(response, 200, catalog.loadTable(identifier(path), true));
      case "IcebergViewExists" -> writeStatus(response, catalog.tableExists(identifier(path), true) ? 204 : 404);
      case "IcebergReplaceView" -> writeJson(response, 200,
          catalog.updateTable(identifier(path), body(request), true));
      case "IcebergDropView" -> {
        catalog.dropTable(identifier(path), true, false);
        writeStatus(response, 204);
      }
      case "IcebergRenameView" -> {
        catalog.renameTable(body(request), true);
        writeStatus(response, 204);
      }
      case "IcebergCommitTransaction" -> {
        catalog.commitTransaction(body(request));
        writeStatus(response, 204);
      }
      default -> throw new IcebergCatalogException(404, "NotFoundException",
          "No route for " + method + " " + request.getPath() + " in the Iceberg catalog of LocalS3.");
    }
  }

  /**
   * The operation that a method and a path name. It is the one place the routes are declared, so that the statistics
   * of the service and the dispatch above can't drift apart.
   *
   * @param method the HTTP method.
   * @param path the path segments under {@value #API_PREFIX}.
   * @return the operation; {@value #UNKNOWN_OPERATION} if no route matches.
   */
  private static String operationOf(HttpMethod method, List<String> path) {
    boolean get = HttpMethod.GET.equals(method);
    boolean post = HttpMethod.POST.equals(method);
    boolean head = HttpMethod.HEAD.equals(method);
    boolean delete = HttpMethod.DELETE.equals(method);
    int size = path.size();

    if (size == 1 && "config".equals(path.get(0)) && get) {
      return "IcebergGetConfig";
    }
    if (size == 2 && "oauth".equals(path.get(0)) && "tokens".equals(path.get(1)) && post) {
      return "IcebergGetToken";
    }
    if (size == 2 && "tables".equals(path.get(0)) && "rename".equals(path.get(1)) && post) {
      return "IcebergRenameTable";
    }
    if (size == 2 && "views".equals(path.get(0)) && "rename".equals(path.get(1)) && post) {
      return "IcebergRenameView";
    }
    if (size == 2 && "transactions".equals(path.get(0)) && "commit".equals(path.get(1)) && post) {
      return "IcebergCommitTransaction";
    }
    if (size >= 1 && "namespaces".equals(path.get(0))) {
      if (size == 1) {
        return get ? "IcebergListNamespaces" : post ? "IcebergCreateNamespace" : UNKNOWN_OPERATION;
      }
      if (size == 2) {
        return get ? "IcebergLoadNamespace" : head ? "IcebergNamespaceExists"
            : delete ? "IcebergDropNamespace" : UNKNOWN_OPERATION;
      }
      String resource = path.get(2);
      if (size == 3) {
        return switch (resource) {
          case "properties" -> post ? "IcebergUpdateNamespaceProperties" : UNKNOWN_OPERATION;
          case "register" -> post ? "IcebergRegisterTable" : UNKNOWN_OPERATION;
          case "register-view" -> post ? "IcebergRegisterView" : UNKNOWN_OPERATION;
          case "tables" -> get ? "IcebergListTables" : post ? "IcebergCreateTable" : UNKNOWN_OPERATION;
          case "views" -> get ? "IcebergListViews" : post ? "IcebergCreateView" : UNKNOWN_OPERATION;
          default -> UNKNOWN_OPERATION;
        };
      }
      if (size == 4 && "tables".equals(resource)) {
        return get ? "IcebergLoadTable" : head ? "IcebergTableExists" : post ? "IcebergUpdateTable"
            : delete ? "IcebergDropTable" : UNKNOWN_OPERATION;
      }
      if (size == 4 && "views".equals(resource)) {
        return get ? "IcebergLoadView" : head ? "IcebergViewExists" : post ? "IcebergReplaceView"
            : delete ? "IcebergDropView" : UNKNOWN_OPERATION;
      }
      if (size == 5 && "tables".equals(resource) && "metrics".equals(path.get(4)) && post) {
        return "IcebergReportMetrics";
      }
    }
    return UNKNOWN_OPERATION;
  }

  /**
   * Add the settings that the client needs to read the table it just loaded — the endpoint of LocalS3 and the
   * credentials to reach it with — to a {@code LoadTableResult}, which is the credential vending of the REST catalog.
   * An engine configured with nothing but the catalog URI can then open the table.
   */
  private ObjectNode withConfig(HttpRequest request, ObjectNode loadTableResult) {
    Map<String, String> config = clientConfig.tableConfig(request);
    if (!config.isEmpty()) {
      loadTableResult.set("config", IcebergJson.fromStringMap(config));
    }
    return loadTableResult;
  }

  /**
   * Answer the OAuth2 token endpoint that a client may call before anything else, with a token that is never checked.
   * LocalS3 has no identity provider; the point is that a client configured with {@code credential} starts up instead
   * of failing on the first request.
   */
  private static ObjectNode token() {
    ObjectNode response = IcebergJson.newObject();
    response.put("access_token", "local-s3");
    response.put("token_type", "bearer");
    response.put("expires_in", 86400);
    return response;
  }

  /**
   * The path segments under {@value #API_PREFIX}, each one URL-decoded.
   *
   * <p>The path is split before it is decoded, and that order is the whole point: a namespace level, or a table name,
   * may hold a {@code /}, which the Iceberg client escapes as {@code %2F} so that it stays inside one segment. Reading
   * the decoded path of the request instead would split {@code tab%2Fle} into two segments and lose the table, so the
   * raw URI is taken here and the segments are decoded one at a time. Only {@code %XX} is decoded — a {@code +} in a
   * path is a plus, not a space.
   *
   * <p>The REST catalog allows a {@code prefix} segment between the version and the resource, which a catalog that
   * serves several warehouses uses. LocalS3 serves one, and answers no {@code prefix} in its configuration, so a
   * request that carries one anyway is read as if it didn't: the segment before a known resource is skipped.
   */
  private static List<String> segments(HttpRequest request) {
    String path = rawPath(request);
    if (!path.startsWith(PATH_PREFIX)) {
      return List.of();
    }
    String rest = path.length() > API_PREFIX.length() && path.startsWith(API_PREFIX)
        ? path.substring(API_PREFIX.length()) : "";
    List<String> segments = new ArrayList<>();
    for (String segment : rest.split("/")) {
      if (!segment.isEmpty()) {
        segments.add(decode(segment));
      }
    }
    // Skip a prefix segment, e.g. /v1/my-warehouse/namespaces, which isn't a resource of the API.
    if (!segments.isEmpty() && !isResource(segments.get(0))) {
      segments.remove(0);
    }
    return segments;
  }

  /**
   * The path of a request as it arrived, without its query string and without the escapes decoded.
   */
  private static String rawPath(HttpRequest request) {
    String uri = request.getUri();
    if (uri == null) {
      return Objects.toString(request.getPath(), "");
    }
    int end = uri.length();
    for (int i = 0; i < uri.length(); i++) {
      char c = uri.charAt(i);
      if (c == '?' || c == '#') {
        end = i;
        break;
      }
    }
    return uri.substring(0, end);
  }

  /**
   * Decode the {@code %XX} escapes of one path segment, as UTF-8.
   *
   * @throws IcebergCatalogException if an escape is malformed, which a client shouldn't send.
   */
  private static String decode(String segment) {
    if (segment.indexOf('%') < 0) {
      return segment;
    }
    ByteBuf decoded = Unpooled.buffer(segment.length());
    try {
      for (int i = 0; i < segment.length(); i++) {
        char c = segment.charAt(i);
        if (c != '%') {
          decoded.writeCharSequence(String.valueOf(c), StandardCharsets.UTF_8);
          continue;
        }
        if (i + 2 >= segment.length()) {
          throw IcebergCatalogException.badRequest("Malformed escape in the path: " + segment);
        }
        int high = Character.digit(segment.charAt(i + 1), 16);
        int low = Character.digit(segment.charAt(i + 2), 16);
        if (high < 0 || low < 0) {
          throw IcebergCatalogException.badRequest("Malformed escape in the path: " + segment);
        }
        decoded.writeByte((high << 4) + low);
        i += 2;
      }
      return decoded.toString(StandardCharsets.UTF_8);
    } finally {
      decoded.release();
    }
  }

  private static boolean isResource(String segment) {
    return "config".equals(segment) || "namespaces".equals(segment) || "tables".equals(segment)
        || "views".equals(segment) || "transactions".equals(segment) || "oauth".equals(segment);
  }

  /**
   * The namespace of a path, which is one segment whose levels are separated by {@code 0x1F}.
   */
  private static List<String> namespace(List<String> path) {
    return IcebergIdentifier.parseNamespace(path.size() > 1 ? path.get(1) : null);
  }

  private static IcebergIdentifier identifier(List<String> path) {
    return IcebergIdentifier.of(namespace(path), path.get(3));
  }

  private static boolean purgeRequested(HttpRequest request) {
    return request.parameter("purgeRequested").map(Boolean::parseBoolean).orElse(false);
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
      throw IcebergCatalogException.badRequest("The request body is too large.");
    }
    byte[] bytes = new byte[body.readableBytes()];
    body.getBytes(body.readerIndex(), bytes);
    return IcebergJson.read(new String(bytes, StandardCharsets.UTF_8));
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
   * Answer a failure in the error document of the REST catalog, which is what a client reads its exception off:
   * {@code code} chooses the kind of exception and {@code type} tells a missing namespace from a missing table.
   */
  private static void writeError(HttpResponse response, int status, String type, String message) {
    ObjectNode error = IcebergJson.newObject();
    error.put("message", Objects.toString(message, ""));
    error.put("type", type);
    error.put("code", status);
    ArrayNode stack = IcebergJson.newArray();
    error.set("stack", stack);
    ObjectNode body = IcebergJson.newObject();
    body.set("error", error);
    writeJson(response, status, body);
  }

}
