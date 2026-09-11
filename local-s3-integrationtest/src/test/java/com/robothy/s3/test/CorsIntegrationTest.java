package com.robothy.s3.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.robothy.s3.rest.LocalS3;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CORSRule;
import software.amazon.awssdk.services.s3.model.GetBucketCorsResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * CORS as used by browsers, e.g. to upload with presigned URLs, against a service that requires signed requests.
 */
class CorsIntegrationTest {

  private static final String BUCKET = "cors-bucket";

  private static final String ORIGIN = "http://localhost:3000";

  private static final StaticCredentialsProvider CREDENTIALS =
      StaticCredentialsProvider.create(AwsBasicCredentials.create("access-key-id", "secret-access-key"));

  private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

  private LocalS3 localS3;

  private String endpoint;

  private S3Client s3;

  @BeforeEach
  void setUp() {
    localS3 = LocalS3.builder().port(-1).credentials("access-key-id", "secret-access-key").buckets(BUCKET).build();
    localS3.start();
    endpoint = "http://localhost:" + localS3.getPort();
    s3 = S3Client.builder()
        .credentialsProvider(CREDENTIALS)
        .endpointOverride(URI.create(endpoint))
        .forcePathStyle(true)
        .region(Region.US_EAST_1)
        .build();
  }

  @AfterEach
  void tearDown() {
    s3.close();
    localS3.shutdown();
  }

  @Test
  void managesBucketCorsWithSdk() {
    S3Exception missing = assertThrows(S3Exception.class, () -> s3.getBucketCors(b -> b.bucket(BUCKET)));
    assertEquals(404, missing.statusCode());
    assertEquals("NoSuchCORSConfiguration", missing.awsErrorDetails().errorCode());

    putBucketCors();
    GetBucketCorsResponse cors = s3.getBucketCors(b -> b.bucket(BUCKET));
    assertEquals(1, cors.corsRules().size());
    CORSRule rule = cors.corsRules().get(0);
    assertEquals(List.of(ORIGIN), rule.allowedOrigins());
    assertEquals(List.of("GET", "PUT"), rule.allowedMethods());
    assertEquals(List.of("*"), rule.allowedHeaders());
    assertEquals(List.of("ETag"), rule.exposeHeaders());
    assertEquals(3000, rule.maxAgeSeconds());

    s3.deleteBucketCors(b -> b.bucket(BUCKET));
    assertThrows(S3Exception.class, () -> s3.getBucketCors(b -> b.bucket(BUCKET)));

    S3Exception invalid = assertThrows(S3Exception.class, () -> s3.putBucketCors(b -> b.bucket(BUCKET)
        .corsConfiguration(c -> c.corsRules(CORSRule.builder().allowedOrigins("*").allowedMethods("PATCH").build()))));
    assertEquals(400, invalid.statusCode());
  }

  @Test
  void answersPreflightRequestsWithoutAuthentication() throws Exception {
    HttpResponse<String> notEnabled = preflight(ORIGIN, "PUT", "content-type");
    assertEquals(403, notEnabled.statusCode());
    assertTrue(notEnabled.body().contains("<Code>AccessForbidden</Code>"), notEnabled.body());

    putBucketCors();
    HttpResponse<String> allowed = preflight(ORIGIN, "PUT", "Content-Type, x-amz-meta-owner");
    assertEquals(200, allowed.statusCode(), allowed.body());
    assertEquals(ORIGIN, header(allowed, "access-control-allow-origin"));
    assertEquals("GET, PUT", header(allowed, "access-control-allow-methods"));
    assertEquals("content-type, x-amz-meta-owner", header(allowed, "access-control-allow-headers"));
    assertEquals("ETag", header(allowed, "access-control-expose-headers"));
    assertEquals("3000", header(allowed, "access-control-max-age"));
    assertEquals("true", header(allowed, "access-control-allow-credentials"));

    assertEquals(403, preflight("http://evil.test", "PUT", null).statusCode());
    assertEquals(403, preflight(ORIGIN, "DELETE", null).statusCode());
  }

  @Test
  void addsCorsHeadersToCrossOriginRequests() throws Exception {
    putBucketCors();
    try (S3Presigner presigner = S3Presigner.builder()
        .credentialsProvider(CREDENTIALS)
        .endpointOverride(URI.create(endpoint))
        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
        .region(Region.US_EAST_1)
        .build()) {
      URI upload = presigner.presignPutObject(r -> r.signatureDuration(Duration.ofMinutes(5))
          .putObjectRequest(p -> p.bucket(BUCKET).key("upload.txt"))).url().toURI();

      HttpResponse<String> uploaded = http.send(HttpRequest.newBuilder(upload).header("Origin", ORIGIN)
          .PUT(HttpRequest.BodyPublishers.ofString("Hello")).build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(200, uploaded.statusCode(), uploaded.body());
      assertEquals(ORIGIN, header(uploaded, "access-control-allow-origin"));
      assertEquals("ETag", header(uploaded, "access-control-expose-headers"));

      // Browsers enforce CORS: the request of another origin succeeds, without CORS headers.
      HttpResponse<String> otherOrigin = http.send(HttpRequest.newBuilder(upload).header("Origin", "http://evil.test")
          .PUT(HttpRequest.BodyPublishers.ofString("Hello")).build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(200, otherOrigin.statusCode());
      assertFalse(otherOrigin.headers().firstValue("access-control-allow-origin").isPresent());

      // Errors carry the CORS headers too, so that the page can read them.
      URI missing = presigner.presignGetObject(r -> r.signatureDuration(Duration.ofMinutes(5))
          .getObjectRequest(g -> g.bucket(BUCKET).key("missing.txt"))).url().toURI();
      HttpResponse<String> notFound = http.send(HttpRequest.newBuilder(missing).header("Origin", ORIGIN).GET().build(),
          HttpResponse.BodyHandlers.ofString());
      assertEquals(404, notFound.statusCode());
      assertEquals(ORIGIN, header(notFound, "access-control-allow-origin"));
    }
  }

  private void putBucketCors() {
    s3.putBucketCors(b -> b.bucket(BUCKET).corsConfiguration(c -> c.corsRules(CORSRule.builder()
        .allowedOrigins(ORIGIN)
        .allowedMethods("GET", "PUT")
        .allowedHeaders("*")
        .exposeHeaders("ETag")
        .maxAgeSeconds(3000)
        .build())));
  }

  private HttpResponse<String> preflight(String origin, String method, String requestHeaders) throws Exception {
    HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(endpoint + "/" + BUCKET + "/upload.txt"))
        .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
        .header("Origin", origin)
        .header("Access-Control-Request-Method", method);
    if (requestHeaders != null) {
      request.header("Access-Control-Request-Headers", requestHeaders);
    }
    return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static String header(HttpResponse<?> response, String name) {
    return response.headers().firstValue(name).orElse(null);
  }

}
