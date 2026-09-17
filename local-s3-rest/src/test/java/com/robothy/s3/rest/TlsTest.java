package com.robothy.s3.rest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.rest.bootstrap.LocalS3Mode;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.util.Map;
import java.util.Random;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A service that serves HTTPS with the certificate of {@code example.org} that mkcert created.
 */
class TlsTest {

  private static Path certPem;

  private static Path keyPem;

  /**
   * Trusts the certificate of {@code example.org} itself, as its CA isn't available to the tests.
   */
  private static SSLContext clientContext;

  @BeforeAll
  static void loadCertificate() throws Exception {
    certPem = resource("example.org.pem");
    keyPem = resource("example.org-key.pem");
    KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
    trustStore.load(null, null);
    try (InputStream in = Files.newInputStream(certPem)) {
      trustStore.setCertificateEntry("example.org", CertificateFactory.getInstance("X.509").generateCertificate(in));
    }
    TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    trustManagers.init(trustStore);
    clientContext = SSLContext.getInstance("TLS");
    clientContext.init(null, trustManagers.getTrustManagers(), null);
  }

  @Test
  void servesHttpsWithACertificateThatMatchesItsHostName() throws Exception {
    LocalS3 localS3 = LocalS3.builder().port(-1).tls(certPem, keyPem).build();
    localS3.start();
    try {
      assertTrue(localS3.isTlsEnabled());
      // The client verifies that the certificate is issued for example.org, the host it thinks it connects to.
      try (SSLSocket socket = (SSLSocket) clientContext.getSocketFactory().createSocket(
          new Socket("127.0.0.1", localS3.getPort()), "example.org", localS3.getPort(), true)) {
        SSLParameters parameters = socket.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        socket.setSSLParameters(parameters);
        OutputStream out = socket.getOutputStream();
        out.write("GET /_health HTTP/1.1\r\nHost: example.org\r\nConnection: close\r\n\r\n"
            .getBytes(StandardCharsets.US_ASCII));
        out.flush();
        String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(response.startsWith("HTTP/1.1 200"), response);
      }
    } finally {
      localS3.shutdown();
    }
  }

  /**
   * An object stored in a file is streamed through TLS, where a zero-copy file region can't be written.
   */
  @Test
  void storesAndServesObjectsOverHttps(@TempDir Path dataPath) throws Exception {
    LocalS3 localS3 = LocalS3.builder().port(-1).mode(LocalS3Mode.PERSISTENCE).dataPath(dataPath.toString())
        .requestBodyFileThreshold(1024).tls(certPem.toString(), keyPem.toString()).build();
    localS3.start();
    try {
      byte[] content = new byte[512 * 1024];
      new Random(7).nextBytes(content);
      assertEquals(200, request(localS3, "PUT", "/bucket", null).status());
      assertEquals(200, request(localS3, "PUT", "/bucket/large", content).status());

      Response get = request(localS3, "GET", "/bucket/large", null);
      assertEquals(200, get.status());
      assertArrayEquals(content, get.body());
    } finally {
      localS3.shutdown();
    }
  }

  @Test
  void rejectsPlainHttpRequests() throws Exception {
    LocalS3 localS3 = LocalS3.builder().port(-1).tls(certPem, keyPem).build();
    localS3.start();
    try {
      HttpURLConnection connection = (HttpURLConnection) new URI("http://127.0.0.1:" + localS3.getPort() + "/_health")
          .toURL().openConnection();
      connection.setReadTimeout(5_000);
      assertThrows(IOException.class, connection::getResponseCode);

      assertEquals(200, request(localS3, "GET", "/_health", null).status(),
          "The service keeps serving HTTPS after a plain HTTP request.");
    } finally {
      localS3.shutdown();
    }
  }

  @Test
  void acceptsThePemContentInsteadOfFiles() throws Exception {
    LocalS3Config config = LocalS3.builder()
        .tls(Files.readString(certPem), Files.readString(keyPem))
        .buildConfig();

    assertTrue(config.tlsEnabled());
    assertEquals(LocalS3.builder().tls(certPem, keyPem).buildConfig().tls(), config.tls());
    assertFalse(config.toString().contains("PRIVATE KEY"), config.toString());
    assertFalse(config.tls().toString().contains("PRIVATE KEY"), config.tls().toString());
  }

  @Test
  void validatesTheCertificateAndKeyWhenTheyAreSet(@TempDir Path directory) throws Exception {
    assertNull(LocalS3.builder().buildConfig().tls(), "Plain HTTP by default.");
    assertThrows(IllegalArgumentException.class,
        () -> LocalS3.builder().tls(directory.resolve("missing.pem"), keyPem));
    Path notPem = Files.writeString(directory.resolve("not.pem"), "not a certificate");
    assertThrows(IllegalArgumentException.class, () -> LocalS3.builder().tls(notPem, keyPem));
    // The key where the certificate belongs.
    assertThrows(IllegalArgumentException.class, () -> LocalS3.builder().tls(keyPem, keyPem));
  }

  @Test
  void isConfiguredFromTheEnvironment() {
    LocalS3Config config = LocalS3.builder().fromEnvironment(Map.of(
        LocalS3Environment.LOCAL_S3_TLS_CERT, certPem.toString(),
        LocalS3Environment.LOCAL_S3_TLS_KEY, keyPem.toString())::get).buildConfig();
    assertTrue(config.tlsEnabled());

    assertThrows(IllegalArgumentException.class, () -> LocalS3.builder().fromEnvironment(Map.of(
        LocalS3Environment.LOCAL_S3_TLS_CERT, certPem.toString())::get));
  }

  private record Response(int status, byte[] body) {
  }

  private static Response request(LocalS3 localS3, String method, String path, byte[] body) throws Exception {
    HttpsURLConnection connection = (HttpsURLConnection) new URI("https://127.0.0.1:" + localS3.getPort() + path)
        .toURL().openConnection();
    connection.setSSLSocketFactory(clientContext.getSocketFactory());
    // The certificate is issued for example.org, which the tests reach at 127.0.0.1.
    connection.setHostnameVerifier((host, session) -> true);
    connection.setRequestMethod(method);
    if (body != null) {
      connection.setDoOutput(true);
      connection.setFixedLengthStreamingMode(body.length);
      try (OutputStream out = connection.getOutputStream()) {
        out.write(body);
      }
    }
    int status = connection.getResponseCode();
    try (InputStream in = status < 400 ? connection.getInputStream() : connection.getErrorStream()) {
      return new Response(status, in == null ? new byte[0] : in.readAllBytes());
    }
  }

  private static Path resource(String name) throws URISyntaxException {
    return Path.of(TlsTest.class.getClassLoader().getResource(name).toURI());
  }

}
