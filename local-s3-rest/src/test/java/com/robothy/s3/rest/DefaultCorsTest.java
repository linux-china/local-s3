package com.robothy.s3.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.datatypes.CORSConfiguration;
import com.robothy.s3.datatypes.CORSRule;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * The default CORS rule of a service, which answers the cross-origin requests of browsers where the bucket has no CORS
 * configuration of its own, and the requests that address no bucket.
 */
class DefaultCorsTest {

  private static final String BUCKET = "web";

  private static final String ORIGIN = "http://localhost:5173";

  private static final HttpClient CLIENT = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

  @Test
  void isOffByDefault() throws Exception {
    assertFalse(LocalS3.builder().buildConfig().corsEnabled());
    withService(builder -> { }, localS3 -> {
      assertEquals(403, preflight(localS3, "/" + BUCKET + "/a.txt", ORIGIN).statusCode());
      HttpResponse<String> get = get(localS3, "/" + BUCKET + "/a.txt", ORIGIN);
      assertEquals(200, get.statusCode());
      assertTrue(get.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
    });
  }

  @Test
  void allowsTheBucketsWithoutCorsConfiguration() throws Exception {
    withService(builder -> builder.defaultCors(cors -> cors.allowedOrigins("*")), localS3 -> {
      HttpResponse<String> preflight = preflight(localS3, "/" + BUCKET + "/a.txt", ORIGIN);
      assertEquals(200, preflight.statusCode());
      assertEquals("*", preflight.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
      assertEquals("content-type, x-amz-date",
          preflight.headers().firstValue("Access-Control-Allow-Headers").orElse(null));
      assertTrue(preflight.headers().firstValue("Access-Control-Allow-Methods").orElse("").contains("PUT"));

      HttpResponse<String> get = get(localS3, "/" + BUCKET + "/a.txt", ORIGIN);
      assertEquals(200, get.statusCode());
      assertEquals("*", get.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
      assertTrue(get.headers().firstValue("Access-Control-Expose-Headers").orElse("").contains("ETag"));

      // A bucket that a page is about to create, and the error of one that isn't there, are readable too.
      assertEquals(200, preflight(localS3, "/missing/a.txt", ORIGIN).statusCode());
      HttpResponse<String> missing = get(localS3, "/missing/a.txt", ORIGIN);
      assertEquals(404, missing.statusCode());
      assertEquals("*", missing.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
    });
  }

  @Test
  void aBucketKeepsItsOwnCorsConfiguration() throws Exception {
    withService(builder -> builder.defaultCors(cors -> cors.allowedOrigins("*")), localS3 -> {
      localS3.getS3Manager().bucketService().putBucketCors(BUCKET, CORSConfiguration.builder()
          .corsRules(List.of(CORSRule.builder().allowedOrigins(List.of("http://allowed.test"))
              .allowedMethods(List.of("GET")).build()))
          .build());

      assertEquals(403, preflight(localS3, "/" + BUCKET + "/a.txt", ORIGIN).statusCode(),
          "The default rule doesn't widen the configuration of a bucket.");
      assertTrue(get(localS3, "/" + BUCKET + "/a.txt", ORIGIN).headers()
          .firstValue("Access-Control-Allow-Origin").isEmpty());
      HttpResponse<String> allowed = get(localS3, "/" + BUCKET + "/a.txt", "http://allowed.test");
      assertEquals("http://allowed.test", allowed.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
    });
  }

  @Test
  void allowsOnlyTheConfiguredOrigins() throws Exception {
    withService(builder -> builder.defaultCors(cors -> cors.allowedOrigins(ORIGIN).maxAgeSeconds(600)), localS3 -> {
      HttpResponse<String> preflight = preflight(localS3, "/" + BUCKET + "/a.txt", ORIGIN);
      assertEquals(200, preflight.statusCode());
      assertEquals(ORIGIN, preflight.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
      assertEquals("true", preflight.headers().firstValue("Access-Control-Allow-Credentials").orElse(null));
      assertEquals("600", preflight.headers().firstValue("Access-Control-Max-Age").orElse(null));
      assertEquals(403, preflight(localS3, "/" + BUCKET + "/a.txt", "http://evil.test").statusCode());
    });
  }

  @Test
  void answersTheRequestsThatAddressNoBucket() throws Exception {
    withService(builder -> builder.icebergCatalog(true).defaultCors(cors -> cors.allowedOrigins("*")), localS3 -> {
      assertEquals(200, preflight(localS3, "/", ORIGIN).statusCode());
      HttpResponse<String> listBuckets = get(localS3, "/", ORIGIN);
      assertEquals(200, listBuckets.statusCode());
      assertEquals("*", listBuckets.headers().firstValue("Access-Control-Allow-Origin").orElse(null));

      HttpResponse<String> catalogPreflight = preflight(localS3, "/iceberg/v1/config", ORIGIN);
      assertEquals(200, catalogPreflight.statusCode());
      assertEquals("*", catalogPreflight.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
      HttpResponse<String> catalog = get(localS3, "/iceberg/v1/config", ORIGIN);
      assertEquals(200, catalog.statusCode());
      assertEquals("*", catalog.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
    });
  }

  @Test
  void isConfiguredFromTheEnvironment() {
    LocalS3Config config = LocalS3.builder().fromEnvironment(Map.of(
        LocalS3Environment.LOCAL_S3_CORS_ALLOWED_ORIGINS, "http://localhost:5173, http://localhost:3000",
        LocalS3Environment.LOCAL_S3_CORS_ALLOWED_METHODS, "get,head",
        LocalS3Environment.LOCAL_S3_CORS_MAX_AGE_SECONDS, "3000")::get).buildConfig();
    assertTrue(config.corsEnabled());
    assertEquals(List.of("http://localhost:5173", "http://localhost:3000"), config.cors().allowedOrigins());
    assertEquals(List.of("GET", "HEAD"), config.cors().allowedMethods());
    assertEquals(3000, config.cors().maxAgeSeconds());

    assertThrows(IllegalArgumentException.class, () -> LocalS3.builder()
        .fromEnvironment(Map.of(LocalS3Environment.LOCAL_S3_CORS_MAX_AGE_SECONDS, "-1")::get));
  }

  @Test
  void validatesTheRule() {
    assertThrows(IllegalArgumentException.class,
        () -> LocalS3.builder().defaultCors(cors -> cors.allowedOrigins("*").allowedMethods("PATCH")));
    assertThrows(IllegalArgumentException.class, () -> LocalS3Cors.allowOrigins(" "));
    assertNull(LocalS3Cors.disabled().toCorsConfiguration());
    CORSRule rule = LocalS3Cors.allowOrigins("*").toCorsConfiguration().getCorsRules().get(0);
    assertEquals(LocalS3Cors.DEFAULT_ALLOWED_METHODS, rule.getAllowedMethods());
    assertEquals(List.of("*"), rule.getAllowedHeaders());
    assertEquals(LocalS3Cors.DEFAULT_EXPOSE_HEADERS, rule.getExposeHeaders());
  }

  private interface ServiceTest {
    void run(LocalS3 localS3) throws Exception;
  }

  private static void withService(Consumer<LocalS3Builder> configure, ServiceTest test) throws Exception {
    LocalS3Builder builder = LocalS3.builder().port(-1).buckets(BUCKET);
    configure.accept(builder);
    LocalS3 localS3 = builder.build();
    localS3.start();
    try {
      localS3.getS3Manager().objectService().putObject(BUCKET, "a.txt",
          com.robothy.s3.core.model.request.PutObjectOptions.builder()
              .contentType("text/plain")
              .content(new java.io.ByteArrayInputStream("hello".getBytes()))
              .size(5)
              .build());
      test.run(localS3);
    } finally {
      localS3.shutdown();
    }
  }

  private static HttpResponse<String> preflight(LocalS3 localS3, String path, String origin) throws Exception {
    return CLIENT.send(HttpRequest.newBuilder(uri(localS3, path))
        .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
        .header("Origin", origin)
        .header("Access-Control-Request-Method", "PUT")
        .header("Access-Control-Request-Headers", "Content-Type, X-Amz-Date")
        .build(), HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<String> get(LocalS3 localS3, String path, String origin) throws Exception {
    return CLIENT.send(HttpRequest.newBuilder(uri(localS3, path)).header("Origin", origin).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static URI uri(LocalS3 localS3, String path) {
    return URI.create("http://127.0.0.1:" + localS3.getPort() + path);
  }

}
