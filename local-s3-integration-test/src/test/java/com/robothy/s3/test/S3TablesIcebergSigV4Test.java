package com.robothy.s3.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.jupiter.LocalS3;
import com.robothy.s3.jupiter.LocalS3Endpoint;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner;
import software.amazon.awssdk.http.auth.spi.signer.SignedRequest;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3tables.S3TablesClient;

/**
 * The Iceberg REST endpoint of a table bucket, reached with the signature that Amazon documents for it:
 * {@code rest.sigv4-enabled=true} and {@code rest.signing-name=s3tables}, which makes an Iceberg client sign every
 * catalog request for the {@code s3tables} service.
 *
 * <p>Signed for {@code s3tables} and signed for {@code s3} are both accepted on the catalog path, because which one an
 * Iceberg client sends depends on how it was configured and neither says anything about what the request may do. What
 * is <em>not</em> accepted is a signature that doesn't verify, which is what this pins: the scope picks the endpoint,
 * the signature is still checked against the credentials of the service.
 *
 * <p>The request is signed with the AWS SDK's own signer and sent with a plain HTTP client rather than through the
 * Iceberg client, so that this runs in the build of every pull request instead of only with the {@code data-tools} tag.
 */
@LocalS3(icebergCatalog = true, accessKey = S3TablesIcebergSigV4Test.ACCESS_KEY,
    secretKey = S3TablesIcebergSigV4Test.SECRET_KEY)
class S3TablesIcebergSigV4Test {

  static final String ACCESS_KEY = "an-access-key";

  static final String SECRET_KEY = "a-secret-key";

  private static final HttpClient CLIENT = HttpClient.newHttpClient();

  @Test
  void a_catalog_request_signed_for_s3tables_is_accepted(S3TablesClient tables, LocalS3Endpoint endpoint)
      throws Exception {
    String arn = tables.createTableBucket(request -> request.name("signed-catalog")).arn();

    HttpResponse<String> response = send(config(endpoint, arn), "s3tables", SECRET_KEY);
    assertEquals(200, response.statusCode(), response.body());
    // The config of a client that named a table bucket carries the prefix its catalog is served under.
    assertTrue(response.body().contains("\"prefix\":\"signed-catalog\""), response.body());
  }

  @Test
  void a_catalog_request_signed_for_s3_is_accepted_too(S3TablesClient tables, LocalS3Endpoint endpoint)
      throws Exception {
    String arn = tables.createTableBucket(request -> request.name("signed-for-s3")).arn();
    assertEquals(200, send(config(endpoint, arn), "s3", SECRET_KEY).statusCode());
  }

  @Test
  void a_catalog_request_signed_with_the_wrong_key_is_refused(S3TablesClient tables, LocalS3Endpoint endpoint)
      throws Exception {
    String arn = tables.createTableBucket(request -> request.name("wrong-key")).arn();
    HttpResponse<String> response = send(config(endpoint, arn), "s3tables", "not-the-secret");
    assertEquals(403, response.statusCode(), response.body());
  }

  private static URI config(LocalS3Endpoint endpoint, String tableBucketArn) {
    return URI.create(endpoint.icebergCatalogUri() + "/v1/config?warehouse="
        + java.net.URLEncoder.encode(tableBucketArn, java.nio.charset.StandardCharsets.UTF_8));
  }

  /**
   * Sign a {@code GET} of a URI for a service and send it.
   */
  private static HttpResponse<String> send(URI uri, String signingName, String secretKey)
      throws IOException, InterruptedException {
    SignedRequest signed = AwsV4HttpSigner.create().sign(request -> request
        .identity(AwsBasicCredentials.create(ACCESS_KEY, secretKey))
        .request(SdkHttpFullRequest.builder()
            .method(SdkHttpMethod.GET)
            .uri(uri)
            .appendHeader("Host", uri.getHost() + ":" + uri.getPort())
            .build())
        .putProperty(AwsV4HttpSigner.SERVICE_SIGNING_NAME, signingName)
        .putProperty(AwsV4HttpSigner.REGION_NAME, Region.US_EAST_1.id()));

    java.net.http.HttpRequest.Builder builder = java.net.http.HttpRequest.newBuilder(uri).GET();
    for (Map.Entry<String, java.util.List<String>> header : signed.request().headers().entrySet()) {
      if (!"Host".equalsIgnoreCase(header.getKey()) && !"Content-Length".equalsIgnoreCase(header.getKey())) {
        header.getValue().forEach(value -> builder.header(header.getKey(), value));
      }
    }
    return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

}
