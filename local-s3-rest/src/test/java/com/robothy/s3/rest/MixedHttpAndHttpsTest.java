package com.robothy.s3.rest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.UnaryOperator;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * A service with a certificate answers HTTP and HTTPS on the same port, so that a client which speaks TLS, e.g. DuckDB
 * with its default {@code s3_use_ssl}, and one which doesn't, e.g. a script with a plain endpoint URL, reach the same
 * endpoint without either being reconfigured.
 */
class MixedHttpAndHttpsTest {

  private static final String BUCKET = "uploads";

  private final List<AutoCloseable> resources = new ArrayList<>();

  @AfterEach
  void close() throws Exception {
    for (AutoCloseable resource : resources.reversed()) {
      resource.close();
    }
  }

  /**
   * The same port, reached both ways, by clients that know nothing of each other.
   */
  @Test
  void answersHttpAndHttpsOnTheSamePort() throws Exception {
    LocalS3Tls tls = LocalS3Tls.selfSigned();
    LocalS3 localS3 = start(builder -> builder.tls(tls));

    assertTrue(localS3.isTlsEnabled());
    assertTrue(localS3.getConfig().plainHttpAccepted());
    assertEquals("{\"status\":\"UP\"}", health(client(tls), "https", localS3.getPort()).body());
    assertEquals("{\"status\":\"UP\"}", health(client(tls), "http", localS3.getPort()).body());
  }

  /**
   * Both schemes keep working on one connection after another, so the decision is made per connection rather than
   * once for the service.
   */
  @Test
  void decidesPerConnectionRatherThanOncePerService() throws Exception {
    LocalS3Tls tls = LocalS3Tls.selfSigned();
    LocalS3 localS3 = start(builder -> builder.tls(tls));
    HttpClient client = client(tls);

    for (String scheme : List.of("http", "https", "http", "https", "https", "http")) {
      assertEquals(200, health(client, scheme, localS3.getPort()).statusCode(), scheme);
    }
  }

  /**
   * A plain HTTP request to a service that requires TLS fails, which is what a test that asserts its client really
   * uses TLS needs.
   */
  @Test
  void refusesPlainHttpWhenTlsIsRequired() throws Exception {
    LocalS3Tls tls = LocalS3Tls.selfSigned();
    LocalS3 localS3 = start(builder -> builder.tls(settings -> settings.certificate(tls).required(true)));
    HttpClient client = client(tls);

    assertFalse(localS3.getConfig().plainHttpAccepted());
    assertEquals(200, health(client, "https", localS3.getPort()).statusCode());
    assertThrows(IOException.class, () -> health(client, "http", localS3.getPort()));
  }

  /**
   * Without a certificate there is nothing to serve HTTPS with, so the port stays plain HTTP whatever
   * {@code tls.required(...)} says.
   */
  @Test
  void servesPlainHttpWithoutACertificate() throws Exception {
    LocalS3 localS3 = start(builder -> builder.tls(tls -> tls.required(true)));

    assertFalse(localS3.isTlsEnabled());
    assertTrue(localS3.getConfig().plainHttpAccepted());
    assertEquals(200, health(HttpClient.newHttpClient(), "http", localS3.getPort()).statusCode());
  }

  /**
   * The {@code Location} of a browser form upload names the scheme that the form was posted with, not the one of the
   * configuration: a browser that posted over plain HTTP is given a URL it can follow.
   */
  @Test
  void namesTheSchemeOfTheConnectionInTheLocationOfAFormUpload() throws Exception {
    LocalS3Tls tls = LocalS3Tls.selfSigned();
    LocalS3 localS3 = start(builder -> builder.tls(tls).buckets(BUCKET));
    HttpClient client = client(tls);

    assertEquals("https://localhost:" + localS3.getPort() + "/uploads/over-tls.txt",
        upload(client, "https", localS3.getPort(), "over-tls.txt").headers().firstValue("Location").orElseThrow());
    assertEquals("http://localhost:" + localS3.getPort() + "/uploads/plain.txt",
        upload(client, "http", localS3.getPort(), "plain.txt").headers().firstValue("Location").orElseThrow());
  }

  /**
   * A body large enough to arrive in several reads goes through either way, so the bytes that the handler which tells
   * the schemes apart buffered before it decided reach the HTTP decoder intact.
   */
  @Test
  void storesAndServesObjectsOverBothSchemes() throws Exception {
    LocalS3Tls tls = LocalS3Tls.selfSigned();
    LocalS3 localS3 = start(builder -> builder.tls(tls).buckets(BUCKET));
    HttpClient client = client(tls);
    byte[] content = new byte[512 * 1024];
    new Random(7).nextBytes(content);

    for (String scheme : List.of("http", "https")) {
      String url = scheme + "://localhost:" + localS3.getPort() + "/" + BUCKET + "/" + scheme + ".bin";
      HttpResponse<Void> put = client.send(HttpRequest.newBuilder(URI.create(url))
          .timeout(Duration.ofSeconds(20))
          .PUT(HttpRequest.BodyPublishers.ofByteArray(content)).build(), HttpResponse.BodyHandlers.discarding());
      assertEquals(200, put.statusCode(), scheme);

      HttpResponse<byte[]> get = client.send(HttpRequest.newBuilder(URI.create(url))
          .timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
      assertEquals(200, get.statusCode(), scheme);
      assertArrayEquals(content, get.body(), scheme);
    }
  }

  @Test
  void isConfiguredFromTheEnvironment() {
    LocalS3Config required = LocalS3.builder().fromEnvironment(Map.of(
        LocalS3Environment.LOCAL_S3_TLS_SELF_SIGNED, "true",
        LocalS3Environment.LOCAL_S3_TLS_REQUIRED, "true")::get).buildConfig();
    assertTrue(required.tlsRequired());
    assertFalse(required.plainHttpAccepted());

    LocalS3Config both = LocalS3.builder().fromEnvironment(
        Map.of(LocalS3Environment.LOCAL_S3_TLS_SELF_SIGNED, "true")::get).buildConfig();
    assertFalse(both.tlsRequired(), "A service with a certificate answers plain HTTP too by default.");
    assertTrue(both.plainHttpAccepted());
  }

  private LocalS3 start(UnaryOperator<LocalS3Builder> configure) {
    LocalS3 localS3 = configure.apply(LocalS3.builder().port(-1)).build();
    localS3.start();
    resources.add(localS3::shutdown);
    return localS3;
  }

  private HttpClient client(LocalS3Tls tls) {
    SSLContext sslContext = tls.newClientSslContext();
    return HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(10))
        .sslContext(sslContext)
        .build();
  }

  private static HttpResponse<String> health(HttpClient client, String scheme, int port) throws Exception {
    return client.send(HttpRequest.newBuilder(URI.create(scheme + "://localhost:" + port + "/_health"))
        .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
  }

  /**
   * Post a file to the bucket the way a browser form does, without a policy, which a service without credentials
   * accepts.
   */
  private static HttpResponse<String> upload(HttpClient client, String scheme, int port, String key) throws Exception {
    String boundary = "----LocalS3TestBoundary";
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"key\"\r\n\r\n" + key + "\r\n")
        .getBytes(StandardCharsets.UTF_8));
    body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + key + "\"\r\n"
        + "Content-Type: text/plain\r\n\r\nhello\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
    HttpResponse<String> response = client.send(
        HttpRequest.newBuilder(URI.create(scheme + "://localhost:" + port + "/" + BUCKET))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(),
        HttpResponse.BodyHandlers.ofString());
    assertEquals(204, response.statusCode(), response.body());
    return response;
  }

}
