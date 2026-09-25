package com.robothy.s3.rest.handler.iceberg;

import com.robothy.netty.http.HttpRequest;
import com.robothy.s3.core.iceberg.IcebergJson;
import com.robothy.s3.rest.handler.AwsSignatureV4RequestSigner;
import io.netty.handler.codec.http.HttpHeaderNames;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The settings that the Iceberg catalog hands its clients: where LocalS3 is, and the credentials to reach it with.
 *
 * <p>This is the <em>credential vending</em> of the REST catalog. An engine that is given nothing but the catalog URI
 * asks {@code GET /v1/config} when it starts and reads the {@code config} of every table it loads, and both of them
 * answer with the {@code s3.*} properties of this service. So a Spark, Trino or PyIceberg configured with one line
 * reaches the storage as well:
 *
 * <pre>{@code
 * spark.sql.catalog.local.type = rest
 * spark.sql.catalog.local.uri  = http://localhost:29090/iceberg
 * }</pre>
 *
 * <p>Against a real deployment the vended credentials would be scoped and temporary, from STS; here they are the
 * credentials of the service itself, which is what a test double should hand out — a test that wants temporary ones
 * gets them from the {@code AssumeRole} of LocalS3 on the same port.
 *
 * <p>The endpoint is the host the request arrived on, so a client in a container that reaches LocalS3 by one name and
 * a client on the host that reaches it by another are each told the name that works for them.
 *
 * @param region the region that the clients are told to use.
 * @param accessKeyId the access key to vend; {@code null} if the service takes unsigned requests, which vends none.
 * @param secretAccessKey the secret key of {@code accessKeyId}; {@code null} if it is {@code null}.
 * @param tls whether the service serves HTTPS, which the vended endpoint is then an {@code https://} one.
 * @param vendCredentials whether to vend the settings at all; {@code false} answers none, and the client is then
 *     configured by hand.
 */
public record IcebergClientConfig(String region, @Nullable String accessKeyId, @Nullable String secretAccessKey,
                                  boolean tls, boolean vendCredentials) {

  /**
   * The default region of LocalS3, which its clients sign for.
   */
  public static final String DEFAULT_REGION = "us-east-1";

  /**
   * The host that the endpoint names when a request carries no {@code Host} header.
   */
  private static final String DEFAULT_HOST = "localhost:29090";

  /**
   * The routes of the catalog, as {@code GET /v1/{prefix}/namespaces}, which {@code GET /v1/config} answers so that a
   * client knows what it may call.
   *
   * <p>This is not documentation: a client that is told nothing assumes a default set, and the default set holds the
   * table routes but <em>not</em> the view ones. A catalog that serves views and doesn't say so is a catalog whose
   * views no client will call — {@code catalog.createView(...)} fails with "Server does not support endpoint" before a
   * request is even sent. Every route that {@linkplain IcebergCatalogController} answers is listed here, and nothing
   * else: the scan planning and the credentials endpoint of the specification aren't served, and a client that is
   * told so falls back to reading the table itself rather than failing.
   */
  private static final List<String> ENDPOINTS = List.of(
      "GET /v1/{prefix}/namespaces",
      "POST /v1/{prefix}/namespaces",
      "GET /v1/{prefix}/namespaces/{namespace}",
      "HEAD /v1/{prefix}/namespaces/{namespace}",
      "DELETE /v1/{prefix}/namespaces/{namespace}",
      "POST /v1/{prefix}/namespaces/{namespace}/properties",
      "GET /v1/{prefix}/namespaces/{namespace}/tables",
      "POST /v1/{prefix}/namespaces/{namespace}/tables",
      "GET /v1/{prefix}/namespaces/{namespace}/tables/{table}",
      "HEAD /v1/{prefix}/namespaces/{namespace}/tables/{table}",
      "POST /v1/{prefix}/namespaces/{namespace}/tables/{table}",
      "DELETE /v1/{prefix}/namespaces/{namespace}/tables/{table}",
      "POST /v1/{prefix}/namespaces/{namespace}/tables/{table}/metrics",
      "POST /v1/{prefix}/namespaces/{namespace}/tables/{table}/sign",
      "POST /v1/{prefix}/namespaces/{namespace}/register",
      "POST /v1/{prefix}/tables/rename",
      "POST /v1/{prefix}/transactions/commit",
      "GET /v1/{prefix}/namespaces/{namespace}/views",
      "POST /v1/{prefix}/namespaces/{namespace}/views",
      "GET /v1/{prefix}/namespaces/{namespace}/views/{view}",
      "HEAD /v1/{prefix}/namespaces/{namespace}/views/{view}",
      "POST /v1/{prefix}/namespaces/{namespace}/views/{view}",
      "DELETE /v1/{prefix}/namespaces/{namespace}/views/{view}",
      "POST /v1/{prefix}/views/rename",
      "POST /v1/{prefix}/namespaces/{namespace}/register-view");

  /**
   * The answer of {@code GET /v1/config}, which a client reads before anything else.
   *
   * <p>{@code overrides} wins over what the client was configured with, and carries the warehouse: the client is
   * talking to this catalog, so this catalog says where its tables live. {@code defaults} is what the client falls
   * back to, and carries the S3 settings, so that a client that was configured with its own endpoint or credentials
   * keeps them.
   *
   * @param request the request, whose {@code Host} the endpoint is taken from.
   * @param warehouse the warehouse location of the catalog.
   * @return the {@code CatalogConfig}.
   */
  public ObjectNode configResponse(HttpRequest request, String warehouse) {
    return configResponse(request, warehouse, null);
  }

  /**
   * The answer of {@code GET /v1/config} of a client that named a warehouse this catalog serves under a prefix, i.e.
   * the ARN or the name of a table bucket of the
   * {@link com.robothy.s3.core.s3tables.S3TablesService S3 Tables API}.
   *
   * <p>{@code prefix} is what makes one endpoint serve many catalogs: the client puts it in the path of every request
   * it then makes, {@code /v1/{prefix}/namespaces/...}, and the catalog of that table bucket answers. It is the same
   * mechanism, and the same configuration on the client, that reaching Amazon S3 Tables over its Iceberg REST endpoint
   * uses:
   *
   * <pre>{@code
   * spark.sql.catalog.s3tables.type      = rest
   * spark.sql.catalog.s3tables.uri       = http://localhost:29090/iceberg
   * spark.sql.catalog.s3tables.warehouse = arn:aws:s3tables:us-east-1:000000000000:bucket/sales
   * }</pre>
   *
   * @param request the request, whose {@code Host} the endpoint is taken from.
   * @param warehouse the warehouse to answer, which is the one the client asked for.
   * @param prefix the prefix the client's catalog is served under; {@code null} for the catalog of the service, which
   *     is served under none.
   * @return the {@code CatalogConfig}.
   */
  public ObjectNode configResponse(HttpRequest request, String warehouse, @Nullable String prefix) {
    ObjectNode overrides = IcebergJson.newObject();
    overrides.put("warehouse", warehouse);
    if (prefix != null) {
      overrides.put("prefix", prefix);
    }
    ObjectNode response = IcebergJson.newObject();
    response.set("defaults", IcebergJson.fromStringMap(tableConfig(request)));
    response.set("overrides", overrides);
    ArrayNode endpoints = IcebergJson.newArray();
    ENDPOINTS.forEach(endpoints::add);
    response.set("endpoints", endpoints);
    return response;
  }

  /**
   * The settings that a {@code LoadTableResult} carries, which tell the {@code FileIO} of the client how to reach the
   * files of the table.
   *
   * @param request the request, whose {@code Host} the endpoint is taken from.
   * @return the settings; empty if the catalog vends none.
   */
  public Map<String, String> tableConfig(HttpRequest request) {
    return tableConfig(request, null);
  }

  /**
   * The settings that a {@code LoadTableResult} carries, with where the client signs its S3 requests if it signs them
   * remotely, i.e. if it is configured with {@code s3.remote-signing-enabled}: the catalog signs them with the
   * credentials of the service, see {@linkplain #signer()}.
   *
   * @param request the request, whose {@code Host} the endpoint is taken from.
   * @param signerEndpoint the path of the remote signing route of the table, relative to the catalog URI, e.g.
   *     {@code v1/namespaces/db/tables/events/sign}; {@code null} to vend none, which leaves the client to the default
   *     route {@code v1/aws/s3/sign}, which is served as well.
   * @return the settings; empty if the catalog vends none.
   */
  public Map<String, String> tableConfig(HttpRequest request, @Nullable String signerEndpoint) {
    if (!vendCredentials) {
      return Map.of();
    }
    String endpoint = endpoint(request);
    Map<String, String> config = new LinkedHashMap<>();
    config.put("io-impl", "org.apache.iceberg.aws.s3.S3FileIO");
    config.put("s3.endpoint", endpoint);
    // A local endpoint has no DNS name per bucket, so every client must address a bucket by path.
    config.put("s3.path-style-access", "true");
    config.put("s3.region", region);
    config.put("client.region", region);
    if (accessKeyId != null) {
      config.put("s3.access-key-id", accessKeyId);
      config.put("s3.secret-access-key", secretAccessKey);
    }
    if (signerEndpoint != null) {
      // The URI of the catalog, which the endpoint of the signer is relative to.
      config.put("s3.signer.uri", endpoint + "/iceberg");
      config.put("s3.signer.endpoint", signerEndpoint);
    }
    return config;
  }

  /**
   * The signer of the remote signing of the catalog, which signs the S3 requests of a client with the credentials of
   * the service — the same credentials that the catalog vends, so a client that signs remotely reaches exactly what a
   * client that was vended the credentials does.
   *
   * @return the signer; {@code null} if the service takes unsigned requests, whose requests are then answered as they
   *     are.
   */
  @Nullable
  public AwsSignatureV4RequestSigner signer() {
    return accessKeyId == null || secretAccessKey == null
        ? null : new AwsSignatureV4RequestSigner(accessKeyId, secretAccessKey);
  }

  /**
   * The endpoint of the S3 service, as the client that sent this request can reach it: the host it addressed, and the
   * scheme that LocalS3 serves.
   *
   * <p>A LocalS3 with a certificate answers plain HTTP on the same port unless it is configured not to, and the
   * request doesn't say which of the two it arrived on, so a service with TLS vends its {@code https://} endpoint:
   * that is the one every client of such a service can use.
   */
  private String endpoint(HttpRequest request) {
    String host = request.header(HttpHeaderNames.HOST.toString())
        .map(String::trim)
        .filter(value -> !value.isEmpty())
        .orElse(DEFAULT_HOST);
    return (tls ? "https://" : "http://") + host;
  }

}
