package com.robothy.s3.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertPathValidator;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.net.ssl.HttpsURLConnection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

/**
 * A service that serves HTTPS with a certificate that it generated for itself, which is what a client that uses HTTPS
 * by default, e.g. DuckDB, connects to without a certificate of the machine.
 */
class SelfSignedTlsTest {

  /**
   * A client trusts the generated certificate and verifies the host it connects to against it, which is what a client
   * outside the JVM does with the PEM file of the certificate.
   */
  @ParameterizedTest
  @ValueSource(strings = {"localhost", "127.0.0.1"})
  void servesHttpsWithAGeneratedCertificate(String host) throws Exception {
    LocalS3Tls tls = LocalS3Tls.selfSigned();
    LocalS3 localS3 = LocalS3.builder().port(-1).tls(tls).build();
    localS3.start();
    try {
      assertTrue(localS3.isTlsEnabled());
      HttpsURLConnection connection = (HttpsURLConnection) new URI("https://" + host + ":" + localS3.getPort()
          + "/_health").toURL().openConnection();
      // Neither the hostname verifier nor the trust manager is relaxed: the certificate has to name the host.
      connection.setSSLSocketFactory(tls.newClientSslContext().getSocketFactory());
      assertEquals(200, connection.getResponseCode());
      try (InputStream in = connection.getInputStream()) {
        assertEquals("{\"status\":\"UP\"}", new String(in.readAllBytes(), StandardCharsets.UTF_8));
      }
    } finally {
      localS3.shutdown();
    }
  }

  /**
   * A client that isn't given the certificate refuses it, as nothing trusts a certificate that signed itself. A test
   * that trusts it by mistake would pass either way, so this is what makes the test above one.
   */
  @Test
  void isRefusedByAClientThatWasNotGivenTheCertificate() throws Exception {
    LocalS3 localS3 = LocalS3.builder().port(-1).tls(tls -> tls.selfSigned()).build();
    localS3.start();
    try {
      HttpsURLConnection connection = (HttpsURLConnection) new URI("https://localhost:" + localS3.getPort()
          + "/_health").toURL().openConnection();
      assertThrows(Exception.class, connection::getResponseCode);
    } finally {
      localS3.shutdown();
    }
  }

  @Test
  void issuesTheCertificateForLocalhostAndTheLoopbackAddresses() throws Exception {
    LocalS3Tls tls = LocalS3Tls.selfSigned();

    assertEquals(List.of("localhost", "127.0.0.1", "0:0:0:0:0:0:0:1"), tls.hosts());
    assertTrue(tls.isSelfSigned());
    X509Certificate certificate = tls.certificate();
    assertEquals("CN=localhost,O=LocalS3", certificate.getSubjectX500Principal().getName());
    assertEquals(certificate.getSubjectX500Principal(), certificate.getIssuerX500Principal());
    assertEquals(3, certificate.getVersion(), "An X.509 v3 certificate, so that its extensions are read.");
    assertEquals(List.of("1.3.6.1.5.5.7.3.1"), certificate.getExtendedKeyUsage(), "serverAuth.");
    assertTrue(certificate.getKeyUsage()[0], "digitalSignature.");
    assertTrue(certificate.getKeyUsage()[5], "keyCertSign, as the certificate signed itself.");
    assertTrue(certificate.getBasicConstraints() >= 0,
        "A CA, so that a client that only trusts a CA accepts the certificate it is given.");
    assertTrue(certificate.getSerialNumber().signum() > 0, "A positive serial number, as RFC 5280 requires.");
  }

  /**
   * The certificate is valid from before now, for a clock that is behind the one of the service, until long after the
   * service is shut down.
   */
  @Test
  void isValidFromBeforeNowForAYear() throws Exception {
    X509Certificate certificate = LocalS3Tls.selfSigned().certificate();
    Instant now = Instant.now();

    certificate.checkValidity();
    assertTrue(certificate.getNotBefore().toInstant().isBefore(now.minus(30, ChronoUnit.MINUTES)),
        certificate.getNotBefore().toString());
    assertTrue(certificate.getNotAfter().toInstant().isAfter(now.plus(300, ChronoUnit.DAYS)),
        certificate.getNotAfter().toString());
  }

  /**
   * The signature and the encoding of the certificate are the ones that a client validates, here through the PKIX
   * validator of the JDK with the certificate as the trust anchor.
   */
  @Test
  void issuesACertificateThatPkixValidates() throws Exception {
    X509Certificate certificate = LocalS3Tls.selfSigned().certificate();

    certificate.verify(certificate.getPublicKey());
    PKIXParameters parameters = new PKIXParameters(Set.of(new TrustAnchor(certificate, null)));
    parameters.setRevocationEnabled(false);
    // A chain of the certificate alone; the anchor validates it.
    CertPathValidator.getInstance("PKIX").validate(
        CertificateFactory.getInstance("X.509").generateCertPath(List.of(certificate)), parameters);
  }

  @Test
  void issuesTheCertificateForTheGivenHosts() {
    LocalS3Tls tls = LocalS3Tls.selfSigned("s3.local", "*.s3.local", "10.1.2.3", "::1", " localhost ");

    assertEquals(List.of("s3.local", "*.s3.local", "10.1.2.3", "0:0:0:0:0:0:0:1", "localhost"), tls.hosts());
    assertEquals("CN=s3.local,O=LocalS3", tls.certificate().getSubjectX500Principal().getName(),
        "The first host is the common name.");
  }

  @Test
  void rejectsHostsThatCantBeIssuedFor() {
    assertThrows(IllegalArgumentException.class, () -> LocalS3Tls.selfSigned(new String[0]));
    assertThrows(IllegalArgumentException.class, () -> LocalS3Tls.selfSigned(" "));
    assertThrows(IllegalArgumentException.class, () -> LocalS3Tls.selfSigned("1.2.3.4.5"));
    assertThrows(IllegalArgumentException.class, () -> LocalS3Tls.selfSigned("127.0.0.256"));
    assertThrows(IllegalArgumentException.class, () -> LocalS3Tls.selfSigned("::pizza"));
    assertThrows(IllegalArgumentException.class, () -> LocalS3Tls.selfSigned("数据.local"),
        "A dNSName is ASCII, so an internationalized name has to be given as punycode.");
  }

  /**
   * Each service generates a certificate of its own, so that a client that is given the certificate of one service
   * doesn't trust another.
   */
  @Test
  void generatesADifferentCertificateEachTime() {
    LocalS3Tls first = LocalS3Tls.selfSigned();
    LocalS3Tls second = LocalS3Tls.selfSigned();

    assertNotEquals(first, second);
    assertNotEquals(first.certificate().getSerialNumber(), second.certificate().getSerialNumber());
    assertEquals(first.hosts(), second.hosts());
  }

  /**
   * The generated certificate exists in the log of the service alone, so the log has to carry the PEM content that a
   * client is given, and never the private key.
   */
  @Test
  void logsTheCertificateInPemFormatWhenItStarts() {
    Logger logger = (Logger) LoggerFactory.getLogger(LocalS3.class);
    Level previousLevel = logger.getLevel();
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    logger.setLevel(Level.INFO);
    LocalS3 localS3 = LocalS3.builder().port(-1).tls(tls -> tls.selfSigned()).build();
    try {
      localS3.start();
    } finally {
      logger.detachAppender(appender);
      logger.setLevel(previousLevel);
      localS3.shutdown();
    }

    String log = appender.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", (a, b) -> a + "\n" + b);
    assertTrue(log.contains("-----BEGIN CERTIFICATE-----"), log);
    assertTrue(log.contains(localS3.getConfig().tls().certificateChainPem()), log);
    assertTrue(log.contains("self-signed certificate"), log);
    assertTrue(log.contains("SHA-256 fingerprint"), log);
    assertFalse(log.contains("PRIVATE KEY"), log);
  }

  @Test
  void isConfiguredFromTheEnvironment() {
    LocalS3Config generated = LocalS3.builder().fromEnvironment(
        Map.of(LocalS3Environment.LOCAL_S3_TLS_SELF_SIGNED, "true")::get).buildConfig();
    assertTrue(generated.tlsEnabled());
    assertEquals(LocalS3Tls.DEFAULT_SELF_SIGNED_HOSTS.size(), generated.tls().hosts().size());

    LocalS3Config hosts = LocalS3.builder().fromEnvironment(
        Map.of(LocalS3Environment.LOCAL_S3_TLS_SELF_SIGNED, "localhost, s3 ,10.1.2.3")::get).buildConfig();
    assertEquals(List.of("localhost", "s3", "10.1.2.3"), hosts.tls().hosts());

    assertFalse(LocalS3.builder().fromEnvironment(
            Map.of(LocalS3Environment.LOCAL_S3_TLS_SELF_SIGNED, "false")::get).buildConfig().tlsEnabled(),
        "A variable that turns the feature off doesn't turn HTTPS on.");
  }

  @Test
  void isNotConfiguredTogetherWithACertificateOfItsOwn() {
    Map<String, String> variables = Map.of(
        LocalS3Environment.LOCAL_S3_TLS_SELF_SIGNED, "true",
        LocalS3Environment.LOCAL_S3_TLS_CERT, "cert.pem",
        LocalS3Environment.LOCAL_S3_TLS_KEY, "key.pem");

    assertThrows(IllegalArgumentException.class, () -> LocalS3.builder().fromEnvironment(variables::get));
  }

}
