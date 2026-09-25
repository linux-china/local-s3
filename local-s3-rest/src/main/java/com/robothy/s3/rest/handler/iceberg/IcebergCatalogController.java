package com.robothy.s3.rest.handler.iceberg;

import com.robothy.netty.http.HttpRequest;
import com.robothy.netty.http.HttpRequestHandler;
import com.robothy.netty.http.HttpResponse;
import com.robothy.s3.core.iceberg.IcebergCatalogException;
import com.robothy.s3.core.iceberg.IcebergCatalogService;
import com.robothy.s3.core.iceberg.IcebergIdentifier;
import com.robothy.s3.core.iceberg.IcebergJson;
import com.robothy.s3.core.s3tables.S3TablesArn;
import com.robothy.s3.core.s3tables.S3TablesService;
import com.robothy.s3.rest.handler.AwsSignatureV4RequestSigner;
import com.robothy.s3.rest.utils.RequestPaths;
import com.robothy.s3.rest.utils.ResponseUtils;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
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
 * <p><b>It serves more than one catalog.</b> Beside the catalog of the service, every
 * {@link com.robothy.s3.core.s3tables.S3TablesService table bucket} of the service is served here as a catalog of its
 * own, under a {@code prefix} that {@code GET /v1/config} answers to a client that named the table bucket as its
 * warehouse. That is how Amazon S3 Tables documents its own Iceberg REST endpoint, so an engine reaches a table bucket
 * of LocalS3 with the configuration it would use against AWS; see {@linkplain #config}.
 *
 * <p><b>Requests are not signed.</b> The Iceberg REST protocol carries its own credentials — an OAuth2 bearer token —
 * rather than an AWS signature, so a catalog request is answered whatever it carries, and a {@code Bearer} token is
 * accepted without being checked. A client that <em>does</em> sign, which the Iceberg client does when it is configured
 * with {@code rest.sigv4-enabled} — as reaching Amazon S3 Tables over this protocol requires — is verified like any
 * other request, for {@code s3} or for {@code s3tables}, whichever it signed for. A LocalS3 with credentials verifies
 * the S3 requests that the engine then makes with the credentials this catalog vends either way, which is where a test
 * that asserts about signing has something to assert.
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

  /**
   * The segments of the route of the S3 signer API, {@code v1/aws/s3/sign}, which the {@code S3FileIO} of a client
   * that signs remotely calls when the catalog names no route of its own.
   */
  private static final List<String> S3_SIGNER_ROUTE = List.of("aws", "s3", "sign");

  private static final String CONTENT_TYPE = "application/json";

  private static final int MAX_BODY_LENGTH = 64 * 1024 * 1024;

  private final IcebergCatalogService catalog;

  private final IcebergClientConfig clientConfig;

  /**
   * Resolves the catalog of a table bucket of the S3 Tables API; {@code null} if the service serves no table buckets,
   * which leaves every request to the catalog of {@linkplain #catalog}.
   */
  @Nullable
  private final S3TablesService s3Tables;

  /**
   * Create the controller.
   *
   * @param catalog the catalog of the service.
   * @param clientConfig the settings that the catalog hands its clients, so that an engine configures its
   *     {@code FileIO} from the catalog rather than by hand.
   */
  public IcebergCatalogController(IcebergCatalogService catalog, IcebergClientConfig clientConfig) {
    this(catalog, clientConfig, null);
  }

  /**
   * Create the controller of a service that also serves table buckets, whose catalogs this then reaches as well.
   *
   * @param catalog the catalog that the service serves by default, i.e. the one a client that names no warehouse
   *     reaches.
   * @param clientConfig the settings that the catalog hands its clients.
   * @param s3Tables the table buckets of the service, each of which is a catalog of its own; {@code null} for none.
   */
  public IcebergCatalogController(IcebergCatalogService catalog, IcebergClientConfig clientConfig,
                                  @Nullable S3TablesService s3Tables) {
    this.catalog = Objects.requireNonNull(catalog, "catalog");
    this.clientConfig = Objects.requireNonNull(clientConfig, "clientConfig");
    this.s3Tables = s3Tables;
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
    Parsed parsed = parse(request);
    List<String> path = parsed.segments();
    // Shadows the field on purpose: every operation below answers through the catalog the prefix of the request names,
    // which is the one of a table bucket for a client that named one, and the catalog of the service for any other.
    IcebergCatalogService catalog = catalogOf(parsed.prefix());
    switch (operationOf(method, path)) {
      case "IcebergGetConfig" -> writeJson(response, 200, config(request));
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
      case "IcebergCreateTable" -> {
        ObjectNode body = body(request);
        ObjectNode created = catalog.createTable(namespace(path), body);
        writeJson(response, 200, withConfig(request, parsed.prefix(), namedIn(path, body), created));
      }
      case "IcebergRegisterTable" -> {
        ObjectNode body = body(request);
        ObjectNode registered = catalog.registerTable(namespace(path), body, false);
        writeJson(response, 200, withConfig(request, parsed.prefix(), namedIn(path, body), registered));
      }
      case "IcebergRegisterView" -> writeJson(response, 200,
          catalog.registerTable(namespace(path), body(request), true));
      case "IcebergLoadTable" -> writeJson(response, 200,
          withConfig(request, parsed.prefix(), identifier(path), catalog.loadTable(identifier(path), false)));
      case "IcebergTableExists" ->
          writeStatus(response, catalog.tableExists(identifier(path), false) ? 204 : 404);
      case "IcebergUpdateTable" -> writeJson(response, 200, withConfig(request, parsed.prefix(), identifier(path),
          catalog.updateTable(identifier(path), body(request), false)));
      case "IcebergDropTable" -> {
        catalog.dropTable(identifier(path), false, purgeRequested(request));
        writeStatus(response, 204);
      }
      case "IcebergRenameTable" -> {
        catalog.renameTable(body(request), false);
        writeStatus(response, 204);
      }
      case "IcebergReportMetrics" -> writeStatus(response, 204);
      case "IcebergRemoteSign", "IcebergSignS3Request" -> writeSigned(response, sign(body(request)));
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
      if (size == 5 && "tables".equals(resource) && "sign".equals(path.get(4)) && post) {
        return "IcebergRemoteSign";
      }
    }
    // The route of the S3 signer API that preceded the remote signing of the specification.
    if (S3_SIGNER_ROUTE.equals(path) && post) {
      return "IcebergSignS3Request";
    }
    return UNKNOWN_OPERATION;
  }

  /**
   * Add the settings that the client needs to read the table it just loaded — the endpoint of LocalS3 and the
   * credentials to reach it with — to a {@code LoadTableResult}, which is the credential vending of the REST catalog.
   * An engine configured with nothing but the catalog URI can then open the table.
   */
  private ObjectNode withConfig(HttpRequest request, @Nullable String prefix, IcebergIdentifier table,
                                ObjectNode loadTableResult) {
    Map<String, String> config = clientConfig.tableConfig(request, signerEndpoint(prefix, table));
    if (!config.isEmpty()) {
      loadTableResult.set("config", IcebergJson.fromStringMap(config));
    }
    return loadTableResult;
  }

  /**
   * The route that a client signs the S3 requests of a table at, relative to the catalog URI, e.g.
   * {@code v1/sales/namespaces/db/tables/events/sign}: the remote signing of the specification.
   */
  private static String signerEndpoint(@Nullable String prefix, IcebergIdentifier table) {
    StringBuilder endpoint = new StringBuilder("v1/");
    if (prefix != null) {
      endpoint.append(RequestPaths.encode(prefix)).append('/');
    }
    return endpoint.append("namespaces/")
        .append(RequestPaths.encode(String.join(String.valueOf(IcebergIdentifier.SEPARATOR), table.namespace())))
        .append("/tables/")
        .append(RequestPaths.encode(table.name()))
        .append("/sign")
        .toString();
  }

  /**
   * The table that a create or a register request names: the namespace of its path, and the {@code name} of its body.
   */
  private static IcebergIdentifier namedIn(List<String> path, ObjectNode body) {
    return IcebergIdentifier.of(namespace(path), body.path("name").asString(""));
  }

  /**
   * Sign an S3 request for a client that signs remotely, i.e. whose {@code S3FileIO} is configured with
   * {@code s3.remote-signing-enabled}: its headers are signed with the credentials of the service, the ones this
   * catalog would otherwise vend. A service that takes unsigned requests answers the headers as they are.
   *
   * <p>It answers the {@code RemoteSignRequest} of the specification and the {@code S3SignRequest} of the S3 signer
   * API that preceded it alike, which are the same document but for the {@code provider} of the former.
   */
  private ObjectNode sign(ObjectNode request) {
    String provider = request.path("provider").asString("s3");
    if (!"s3".equalsIgnoreCase(provider)) {
      throw IcebergCatalogException.badRequest("Remote signing of the provider " + provider
          + " is not supported, only s3.");
    }
    String uri = request.path("uri").asString("");
    String method = request.path("method").asString("");
    Map<String, List<String>> headers = new LinkedHashMap<>();
    JsonNode headersNode = request.path("headers");
    if (headersNode.isObject()) {
      for (Map.Entry<String, JsonNode> header : headersNode.properties()) {
        List<String> values = new ArrayList<>();
        if (header.getValue().isArray()) {
          header.getValue().forEach(value -> values.add(value.asString("")));
        } else if (!header.getValue().isNull()) {
          values.add(header.getValue().asString(""));
        }
        headers.put(header.getKey(), values);
      }
    }
    AwsSignatureV4RequestSigner signer = clientConfig.signer();
    Map<String, List<String>> signed;
    try {
      signed = signer == null ? headers
          : signer.sign(request.path("region").asString(null), method, uri, headers);
    } catch (IllegalArgumentException e) {
      throw IcebergCatalogException.badRequest(e.getMessage());
    }
    ObjectNode headersResult = IcebergJson.newObject();
    signed.forEach((name, values) -> {
      ArrayNode array = headersResult.putArray(name);
      values.forEach(array::add);
    });
    ObjectNode response = IcebergJson.newObject();
    response.put("uri", uri);
    response.set("headers", headersResult);
    return response;
  }

  /**
   * Answer a signed request. A signature carries the time it was made at, so the client is told not to cache it.
   */
  private static void writeSigned(HttpResponse response, ObjectNode signed) {
    response.putHeader(HttpHeaderNames.CACHE_CONTROL.toString(), "no-cache");
    writeJson(response, 200, signed);
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
   * The path of a request, split into the prefix of the catalog it addresses and the segments under it.
   *
   * <p>The path is split before it is decoded, and that order is the whole point: a namespace level, or a table name,
   * may hold a {@code /}, which the Iceberg client escapes as {@code %2F} so that it stays inside one segment. Reading
   * the decoded path of the request instead would split {@code tab%2Fle} into two segments and lose the table, so the
   * raw URI is taken here and the segments are decoded one at a time, see {@linkplain RequestPaths}.
   *
   * <p>The REST catalog allows a {@code prefix} segment between the version and the resource, which a catalog that
   * serves several warehouses uses, and LocalS3 does: a service that serves table buckets answers the name of one as
   * the prefix of the catalog of that table bucket, see {@linkplain #catalogOf}. A prefix that names no table bucket
   * is read as if it weren't there, which is what a client configured against an older LocalS3 sends.
   */
  private static Parsed parse(HttpRequest request) {
    String path = RequestPaths.rawPath(request);
    if (!path.startsWith(PATH_PREFIX)) {
      return new Parsed(null, List.of());
    }
    String rest = path.length() > API_PREFIX.length() && path.startsWith(API_PREFIX)
        ? path.substring(API_PREFIX.length()) : "";
    List<String> segments;
    try {
      segments = RequestPaths.decodedSegments(rest);
    } catch (IllegalArgumentException e) {
      throw IcebergCatalogException.badRequest(e.getMessage());
    }
    // The S3 signer route, v1/aws/s3/sign, which names no prefix: "aws" is not the prefix of a table bucket here.
    if (segments.equals(S3_SIGNER_ROUTE)) {
      return new Parsed(null, segments);
    }
    if (!segments.isEmpty() && !isResource(segments.get(0))) {
      return new Parsed(segments.get(0), segments.subList(1, segments.size()));
    }
    return new Parsed(null, segments);
  }

  private static List<String> segments(HttpRequest request) {
    return parse(request).segments();
  }

  /**
   * The catalog that a prefix addresses: the one of the table bucket it names, or the catalog that the service serves
   * by default.
   */
  private IcebergCatalogService catalogOf(@Nullable String prefix) {
    if (prefix == null || s3Tables == null) {
      return catalog;
    }
    IcebergCatalogService tableBucket = s3Tables.catalogOfWarehouse(prefix);
    return tableBucket != null ? tableBucket : catalog;
  }

  /**
   * The {@code CatalogConfig} that a client reads before anything else.
   *
   * <p>A client that names the ARN of a table bucket as its warehouse — which is how Amazon S3 Tables documents its
   * Iceberg REST endpoint — is answered the prefix that its table bucket's catalog is served under, and every request
   * it then makes carries that prefix. So one endpoint serves the catalog of the service and the catalog of every
   * table bucket, told apart by the warehouse the client asked for, exactly as the real endpoint does.
   *
   * @throws IcebergCatalogException if the warehouse is the ARN of a table bucket that this service doesn't have,
   *     which is a mistake worth reporting rather than quietly answering another catalog.
   */
  private ObjectNode config(HttpRequest request) {
    String warehouse = request.parameter("warehouse").filter(value -> !value.isBlank()).orElse(null);
    if (warehouse != null && S3TablesArn.isArn(warehouse)) {
      String tableBucket = s3Tables == null ? null : s3Tables.tableBucketNameOf(warehouse);
      if (tableBucket == null) {
        throw new IcebergCatalogException(404, "NoSuchTableBucketException",
            "No table bucket of the ARN " + warehouse + " in this LocalS3 service.");
      }
      return clientConfig.configResponse(request, warehouse, tableBucket);
    }
    if (warehouse != null && s3Tables != null) {
      String tableBucket = s3Tables.tableBucketNameOf(warehouse);
      if (tableBucket != null) {
        return clientConfig.configResponse(request, s3Tables.bucketArn(tableBucket), tableBucket);
      }
    }
    return clientConfig.configResponse(request, catalog.warehouse());
  }

  /**
   * The path of a request: the prefix that names the catalog it addresses, and the segments under it.
   *
   * @param prefix the prefix; {@code null} if the path carries none.
   * @param segments the segments under the prefix, each one decoded.
   */
  private record Parsed(@Nullable String prefix, List<String> segments) {
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
