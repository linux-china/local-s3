package com.robothy.s3.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.rest.LocalS3;
import com.robothy.s3.rest.LocalS3Builder;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketCannedACL;

/**
 * The static website hosting of LocalS3: a browser, i.e. a client that sends no credentials, reads a public bucket
 * as a site, while the signed requests of an S3 client keep their S3 semantics.
 */
class StaticWebsiteIntegrationTest {

  private static final String PUBLIC_BUCKET = "site";

  private static final String PRIVATE_BUCKET = "private-site";

  private static final String INDEX = "<html><body>home</body></html>";

  private static final StaticCredentialsProvider CREDENTIALS =
      StaticCredentialsProvider.create(AwsBasicCredentials.create("access-key-id", "secret-access-key"));

  private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
      .followRedirects(HttpClient.Redirect.NEVER).build();

  private LocalS3 localS3;

  private String endpoint;

  private S3Client s3;

  @AfterEach
  void tearDown() {
    if (localS3 != null) {
      localS3.shutdown();
    }
  }

  /**
   * Start a service and fill both buckets with a small site. The public one is made public with the
   * {@code public-read} canned ACL.
   */
  private void start(LocalS3 service) {
    localS3 = service;
    localS3.start();
    endpoint = "http://localhost:" + localS3.getPort();
    s3 = S3Client.builder()
        .credentialsProvider(CREDENTIALS)
        .endpointOverride(URI.create(endpoint))
        .forcePathStyle(true)
        .region(Region.US_EAST_1)
        .build();

    for (String bucket : new String[] {PUBLIC_BUCKET, PRIVATE_BUCKET}) {
      s3.createBucket(b -> b.bucket(bucket));
      put(bucket, "index.html", INDEX);
      put(bucket, "docs/index.html", "<html><body>docs</body></html>");
      put(bucket, "css/site.css", "body { color: red; }");
    }
    s3.putBucketAcl(b -> b.bucket(PUBLIC_BUCKET).acl(BucketCannedACL.PUBLIC_READ));
  }

  /**
   * Store an object with the content type of a tool that doesn't know one, which is what an object stored without a
   * {@code Content-Type} keeps, so that the website has to guess it from the key.
   */
  private void put(String bucket, String key, String content) {
    s3.putObject(b -> b.bucket(bucket).key(key).contentType("binary/octet-stream"),
        RequestBody.fromString(content, StandardCharsets.UTF_8));
  }

  /**
   * Send a request with a {@code Host} of its own, which the JDK HTTP client refuses to set, and read the response.
   *
   * @param requestLine the request line, e.g. {@code GET / HTTP/1.1}.
   * @param host the {@code Host} header.
   * @return the response, headers and body.
   */
  private String sendRaw(String requestLine, String host) throws Exception {
    try (java.net.Socket socket = new java.net.Socket("localhost", localS3.getPort())) {
      socket.getOutputStream().write((requestLine + "\r\nHost: " + host + "\r\nConnection: close\r\n\r\n")
          .getBytes(StandardCharsets.UTF_8));
      socket.getOutputStream().flush();
      return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private HttpResponse<String> get(String path) throws Exception {
    return http.send(HttpRequest.newBuilder(URI.create(endpoint + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  /**
   * A service that requires signed requests, which is where a public bucket actually means something.
   */
  private static LocalS3Builder authenticated() {
    return LocalS3.builder().port(-1).credentials("access-key-id", "secret-access-key");
  }

  @Nested
  class PublicBuckets {

    @Test
    void anUnsignedRequestReadsTheIndexDocumentOfAPublicBucket() throws Exception {
      start(authenticated().build());

      for (String path : new String[] {"/" + PUBLIC_BUCKET, "/" + PUBLIC_BUCKET + "/", "/" + PUBLIC_BUCKET + "/index.html"}) {
        HttpResponse<String> response = get(path);
        assertEquals(200, response.statusCode(), path);
        assertEquals(INDEX, response.body(), path);
      }
    }

    @Test
    void aFileStoredWithoutAContentTypeIsServedWithTheOneOfItsExtension() throws Exception {
      start(authenticated().build());

      assertEquals("text/html; charset=utf-8",
          get("/" + PUBLIC_BUCKET + "/").headers().firstValue("Content-Type").orElseThrow());
      assertEquals("text/css; charset=utf-8",
          get("/" + PUBLIC_BUCKET + "/css/site.css").headers().firstValue("Content-Type").orElseThrow());
    }

    @Test
    void aFileKeepsTheContentTypeItWasStoredWith() throws Exception {
      start(authenticated().build());
      s3.putObject(b -> b.bucket(PUBLIC_BUCKET).key("data.html").contentType("text/plain; charset=utf-8"),
          RequestBody.fromString("not markup", StandardCharsets.UTF_8));

      assertEquals("text/plain; charset=utf-8",
          get("/" + PUBLIC_BUCKET + "/data.html").headers().firstValue("Content-Type").orElseThrow(),
          "The website guesses a content type, it doesn't override one that was chosen.");
    }

    @Test
    void aDirectoryWithoutItsTrailingSlashIsRedirectedToIt() throws Exception {
      start(authenticated().build());

      HttpResponse<String> response = get("/" + PUBLIC_BUCKET + "/docs");
      assertEquals(302, response.statusCode());
      assertEquals("/" + PUBLIC_BUCKET + "/docs/", response.headers().firstValue("Location").orElseThrow());

      assertEquals("<html><body>docs</body></html>", get("/" + PUBLIC_BUCKET + "/docs/").body());
    }

    @Test
    void aKeyThatIsNotThereIsAnsweredWithAnErrorPage() throws Exception {
      start(authenticated().build());

      HttpResponse<String> response = get("/" + PUBLIC_BUCKET + "/missing.html");
      assertEquals(404, response.statusCode());
      assertEquals("text/html; charset=utf-8", response.headers().firstValue("Content-Type").orElseThrow());
      assertTrue(response.body().contains("NoSuchKey"), response.body());
      assertTrue(response.body().contains("missing.html"), response.body());
    }

    @Test
    void theErrorDocumentOfTheBucketIsAnsweredWithTheStatusOfTheFailure() throws Exception {
      start(authenticated().build());
      put(PUBLIC_BUCKET, "error.html", "<html><body>gone</body></html>");
      s3.putBucketWebsite(b -> b.bucket(PUBLIC_BUCKET).websiteConfiguration(w -> w
          .indexDocument(i -> i.suffix("index.html"))
          .errorDocument(e -> e.key("error.html"))));

      HttpResponse<String> response = get("/" + PUBLIC_BUCKET + "/missing.html");
      assertEquals(404, response.statusCode());
      assertEquals("<html><body>gone</body></html>", response.body());
    }

    @Test
    void aBucketIsServedWithTheIndexDocumentOfItsOwnConfiguration() throws Exception {
      start(authenticated().build());
      put(PUBLIC_BUCKET, "home.html", "<html><body>configured</body></html>");
      s3.putBucketWebsite(b -> b.bucket(PUBLIC_BUCKET)
          .websiteConfiguration(w -> w.indexDocument(i -> i.suffix("home.html"))));

      assertEquals("<html><body>configured</body></html>", get("/" + PUBLIC_BUCKET + "/").body());
    }

    @Test
    void aHeadRequestAnswersTheHeadersOfTheIndexDocumentWithoutItsBody() throws Exception {
      start(authenticated().build());

      HttpResponse<String> response = http.send(
          HttpRequest.newBuilder(URI.create(endpoint + "/" + PUBLIC_BUCKET + "/"))
              .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
          HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      assertEquals("text/html; charset=utf-8", response.headers().firstValue("Content-Type").orElseThrow());
      assertEquals("", response.body());

      assertEquals(404, http.send(
          HttpRequest.newBuilder(URI.create(endpoint + "/" + PUBLIC_BUCKET + "/missing.html"))
              .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
          HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void aVirtualHostedRequestReadsTheBucketOfItsHost() throws Exception {
      start(authenticated().build());

      // The bucket is the subdomain of the Host, which the JDK HTTP client refuses to set, so the request is sent
      // as it is: GET / with Host: site.localhost.
      String response = sendRaw("GET / HTTP/1.1", PUBLIC_BUCKET + ".localhost:" + localS3.getPort());
      assertTrue(response.startsWith("HTTP/1.1 200"), response);
      assertTrue(response.endsWith(INDEX), response);
    }

    @Test
    void aConfigurationThatRedirectsEverythingSendsEveryRequestToItsHost() throws Exception {
      start(authenticated().build());
      s3.putBucketWebsite(b -> b.bucket(PUBLIC_BUCKET).websiteConfiguration(w -> w
          .redirectAllRequestsTo(to -> to.hostName("example.com").protocol("https"))));

      HttpResponse<String> response = get("/" + PUBLIC_BUCKET + "/docs/index.html");
      assertEquals(301, response.statusCode());
      assertEquals("https://example.com/docs/index.html",
          response.headers().firstValue("Location").orElseThrow());
    }

    @Test
    void aRoutingRuleRedirectsTheKeysItNames() throws Exception {
      start(authenticated().build());
      s3.putBucketWebsite(b -> b.bucket(PUBLIC_BUCKET).websiteConfiguration(w -> w
          .indexDocument(i -> i.suffix("index.html"))
          .routingRules(r -> r
              .condition(c -> c.keyPrefixEquals("old/"))
              .redirect(to -> to.replaceKeyPrefixWith("docs/").httpRedirectCode("301")))));

      HttpResponse<String> response = get("/" + PUBLIC_BUCKET + "/old/index.html");
      assertEquals(301, response.statusCode());
      assertTrue(response.headers().firstValue("Location").orElseThrow().endsWith("/docs/index.html"),
          response.headers().firstValue("Location").orElseThrow());
    }
  }

  @Nested
  class PrivateBuckets {

    @Test
    void anUnsignedRequestOfAPrivateBucketIsRejected() throws Exception {
      start(authenticated().build());

      HttpResponse<String> response = get("/" + PRIVATE_BUCKET + "/index.html");
      assertEquals(403, response.statusCode());
      assertFalse(response.body().contains("home"), response.body());
    }

    @Test
    void aSignedRequestReadsAPrivateBucketAsBefore() {
      start(authenticated().build());

      assertEquals(INDEX, s3.getObjectAsBytes(b -> b.bucket(PRIVATE_BUCKET).key("index.html")).asUtf8String());
    }

    @Test
    void everyBucketIsServedWhenTheServiceIsConfiguredToServeThemAll() throws Exception {
      start(authenticated().website(website -> website.allBuckets(true)).build());

      HttpResponse<String> response = get("/" + PRIVATE_BUCKET + "/");
      assertEquals(200, response.statusCode());
      assertEquals(INDEX, response.body());
    }

    @Test
    void noBucketIsServedAsAWebsiteWhenHostingIsOff() throws Exception {
      start(authenticated().website(false).build());

      assertEquals(403, get("/" + PUBLIC_BUCKET + "/").statusCode());
      assertEquals(403, get("/" + PUBLIC_BUCKET + "/index.html").statusCode());
    }
  }

  @Nested
  class S3Semantics {

    @Test
    void aSignedRequestOfAPublicBucketKeepsItsS3Semantics() {
      start(authenticated().build());

      // The listing of the bucket root, which an unsigned request is answered with the index document for.
      assertEquals(3, s3.listObjectsV2(b -> b.bucket(PUBLIC_BUCKET)).keyCount());
      assertEquals(INDEX, s3.getObjectAsBytes(b -> b.bucket(PUBLIC_BUCKET).key("index.html")).asUtf8String());
    }

    @Test
    void aBucketWithoutAnIndexDocumentStillListsItsObjects() throws Exception {
      start(LocalS3.builder().port(-1).build());
      s3.createBucket(b -> b.bucket("listable"));
      put("listable", "a.txt", "a");
      s3.putBucketAcl(b -> b.bucket("listable").acl(BucketCannedACL.PUBLIC_READ));

      HttpResponse<String> response = get("/listable");
      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains("<ListBucketResult"), response.body());
    }
  }

}
