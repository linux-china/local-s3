package com.robothy.s3.rest.handler.iceberg;

import com.robothy.netty.http.HttpRequest;
import com.robothy.s3.core.iceberg.IcebergJson;
import io.netty.handler.codec.http.HttpHeaderNames;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
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
    ObjectNode overrides = IcebergJson.newObject();
    overrides.put("warehouse", warehouse);
    ObjectNode response = IcebergJson.newObject();
    response.set("defaults", IcebergJson.fromStringMap(tableConfig(request)));
    response.set("overrides", overrides);
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
    return config;
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
