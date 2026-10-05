package com.robothy.s3.rest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.robothy.s3.rest.SelfSignedCertificateGenerator.Issuer;
import com.robothy.s3.rest.SelfSignedCertificateGenerator.KeyPairAlgorithm;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertPathValidator;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;

/**
 * A certificate issued by the CA of mkcert, which is what a machine with mkcert installed trusts without being given
 * the certificate. The CA of the tests is generated, in the files and formats that mkcert writes.
 */
class MkcertCertificateAuthorityTest {

  @TempDir
  Path caRoot;

  @Test
  void resolvesTheDirectoryOfMkcert() {
    Map<String, String> none = Map.of();
    assertEquals(Optional.of(Path.of("/ca")),
        MkcertCertificateAuthority.caRoot(Map.of("CAROOT", "/ca")::get, "Mac OS X", "/home/me"));
    assertEquals(Optional.of(Path.of("/home/me", "Library", "Application Support", "mkcert")),
        MkcertCertificateAuthority.caRoot(none::get, "Mac OS X", "/home/me"));
    assertEquals(Optional.of(Path.of("/home/me", ".local", "share", "mkcert")),
        MkcertCertificateAuthority.caRoot(none::get, "Linux", "/home/me"));
    assertEquals(Optional.of(Path.of("/data", "mkcert")),
        MkcertCertificateAuthority.caRoot(Map.of("XDG_DATA_HOME", "/data")::get, "Linux", "/home/me"));
    assertEquals(Optional.of(Path.of("C:\\Users\\me\\AppData\\Local", "mkcert")),
        MkcertCertificateAuthority.caRoot(Map.of("LOCALAPPDATA", "C:\\Users\\me\\AppData\\Local")::get,
            "Windows 11", "C:\\Users\\me"));
    assertEquals(Optional.empty(), MkcertCertificateAuthority.caRoot(none::get, "Windows 11", "C:\\Users\\me"));
  }

  /**
   * mkcert creates an RSA CA; an EC one is what the CA of another tool may be.
   */
  @ParameterizedTest
  @EnumSource(KeyPairAlgorithm.class)
  void issuesACertificateThatChainsToTheCa(KeyPairAlgorithm caAlgorithm) throws Exception {
    LocalS3Tls ca = writeCa(caAlgorithm);
    Issuer issuer = MkcertCertificateAuthority.load(caRoot).orElseThrow();

    LocalS3Tls tls = SelfSignedCertificateGenerator.generate(List.of("localhost", "127.0.0.1"), issuer);
    X509Certificate certificate = tls.certificate();
    assertFalse(tls.isSelfSigned());
    assertEquals(ca.certificate().getSubjectX500Principal(), certificate.getIssuerX500Principal());
    assertEquals(List.of("localhost", "127.0.0.1"), tls.hosts());
    assertEquals(-1, certificate.getBasicConstraints(), "An issued certificate is no CA");
    assertFalse(certificate.getKeyUsage()[5], "keyCertSign");
    assertTrue(certificate.getKeyUsage()[0], "digitalSignature");
    assertFalse(certificate.getNotAfter().after(ca.certificate().getNotAfter()));
    // The authority key identifier names the subject key identifier of the CA, if it has one.
    byte[] subjectKeyIdentifier = ca.certificate().getExtensionValue("2.5.29.14");
    if (subjectKeyIdentifier != null) {
      byte[] authorityKeyIdentifier = certificate.getExtensionValue("2.5.29.35");
      assertNotNull(authorityKeyIdentifier);
      byte[] keyIdentifier = Arrays.copyOfRange(subjectKeyIdentifier, 4, subjectKeyIdentifier.length);
      assertArrayEquals(keyIdentifier,
          Arrays.copyOfRange(authorityKeyIdentifier, authorityKeyIdentifier.length - keyIdentifier.length,
              authorityKeyIdentifier.length));
    }

    PKIXParameters parameters = new PKIXParameters(Set.of(new TrustAnchor(ca.certificate(), null)));
    parameters.setRevocationEnabled(false);
    CertPathValidator.getInstance("PKIX").validate(
        CertificateFactory.getInstance("X.509").generateCertPath(List.of(certificate)), parameters);
  }

  /**
   * A client that trusts the CA alone, as the trust store of a machine after {@code mkcert -install} does, connects
   * to a service with an issued certificate.
   */
  @Test
  void aClientThatTrustsTheCaConnects() throws Exception {
    LocalS3Tls ca = writeCa(KeyPairAlgorithm.RSA);
    LocalS3Tls tls = SelfSignedCertificateGenerator.generate(LocalS3Tls.DEFAULT_SELF_SIGNED_HOSTS,
        MkcertCertificateAuthority.load(caRoot).orElseThrow());
    LocalS3 localS3 = LocalS3.builder().port(-1).tls(tls).build();
    localS3.start();
    try {
      KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
      trustStore.load(null, null);
      trustStore.setCertificateEntry("mkcert", ca.certificate());
      TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      trustManagers.init(trustStore);
      SSLContext sslContext = SSLContext.getInstance("TLS");
      sslContext.init(null, trustManagers.getTrustManagers(), null);

      assertEquals(200, health(localS3, sslContext));
      // The client context of the certificate keeps working for a certificate that a CA issued.
      assertEquals(200, health(localS3, tls.newClientSslContext()));
    } finally {
      localS3.shutdown();
    }
  }

  /**
   * The log tells which path the certificate took, and the CA by the fingerprint that a trust store lists it by.
   */
  @Test
  void logsWhereTheCertificateComesFrom() throws Exception {
    LocalS3Tls ca = writeCa(KeyPairAlgorithm.RSA);
    String issued = logOf(() -> assertFalse(LocalS3Tls.selfSigned(List.of("localhost"), Optional.of(caRoot))
        .isSelfSigned()));
    assertTrue(issued.contains("issued its certificate with the CA of mkcert in " + caRoot), issued);
    assertTrue(issued.contains(ca.certificate().getSubjectX500Principal().getName()), issued);
    assertTrue(issued.contains(LocalS3Tls.fingerprint(ca.certificate())), issued);

    Path empty = caRoot.resolve("empty");
    String selfSigned = logOf(() -> assertTrue(LocalS3Tls.selfSigned(List.of("localhost"), Optional.of(empty))
        .isSelfSigned()));
    assertTrue(selfSigned.contains("found no CA of mkcert in " + empty), selfSigned);

    String noDirectory = logOf(() -> assertTrue(LocalS3Tls.selfSigned(List.of("localhost"), Optional.empty())
        .isSelfSigned()));
    assertTrue(noDirectory.contains("found no directory of mkcert"), noDirectory);

    // A CA that can't be used is named in a warning, rather than reported as missing.
    Files.writeString(caRoot.resolve(MkcertCertificateAuthority.PRIVATE_KEY_FILE), "not a key");
    String unusable = logOf(() -> assertTrue(LocalS3Tls.selfSigned(List.of("localhost"), Optional.of(caRoot))
        .isSelfSigned()));
    assertTrue(unusable.contains("found the CA of mkcert in " + caRoot + ", but can't"), unusable);
    assertFalse(unusable.contains("found no CA"), unusable);
  }

  @Test
  void skipsADirectoryWithoutACa() {
    assertEquals(Optional.empty(), MkcertCertificateAuthority.load(caRoot));
    assertEquals(Optional.empty(), MkcertCertificateAuthority.load(caRoot.resolve("missing")));
  }

  @Test
  void skipsACaThatCantIssue() throws Exception {
    // A certificate that is no CA.
    LocalS3Tls ca = writeCa(KeyPairAlgorithm.EC);
    LocalS3Tls leaf = SelfSignedCertificateGenerator.generate(List.of("localhost"),
        MkcertCertificateAuthority.load(caRoot).orElseThrow());
    Files.writeString(caRoot.resolve(MkcertCertificateAuthority.CERTIFICATE_FILE), leaf.certificateChainPem());
    assertEquals(Optional.empty(), MkcertCertificateAuthority.load(caRoot));

    // A key that is no PKCS#8 key.
    Files.writeString(caRoot.resolve(MkcertCertificateAuthority.CERTIFICATE_FILE), ca.certificateChainPem());
    Files.writeString(caRoot.resolve(MkcertCertificateAuthority.PRIVATE_KEY_FILE),
        "-----BEGIN RSA PRIVATE KEY-----\nAAAA\n-----END RSA PRIVATE KEY-----\n");
    assertEquals(Optional.empty(), MkcertCertificateAuthority.load(caRoot));
  }

  @Test
  void refusesAKeyThatIsNotTheOneOfTheCa() throws Exception {
    writeCa(KeyPairAlgorithm.EC);
    LocalS3Tls other = SelfSignedCertificateGenerator.generate(List.of("other.test"), null, KeyPairAlgorithm.EC);
    Files.writeString(caRoot.resolve(MkcertCertificateAuthority.PRIVATE_KEY_FILE), other.privateKeyPem());
    Issuer issuer = MkcertCertificateAuthority.load(caRoot).orElseThrow();
    assertThrows(IllegalStateException.class,
        () -> SelfSignedCertificateGenerator.generate(List.of("localhost"), issuer));
  }

  /**
   * Write a CA to {@code rootCA.pem} and {@code rootCA-key.pem}, like {@code mkcert -install}. A certificate that
   * signed itself is a CA that may sign certificates, which is all the CA of mkcert is.
   */
  private LocalS3Tls writeCa(KeyPairAlgorithm algorithm) throws Exception {
    LocalS3Tls ca = SelfSignedCertificateGenerator.generate(List.of("ca.test"), null, algorithm);
    Files.writeString(caRoot.resolve(MkcertCertificateAuthority.CERTIFICATE_FILE), ca.certificateChainPem());
    Files.writeString(caRoot.resolve(MkcertCertificateAuthority.PRIVATE_KEY_FILE), ca.privateKeyPem());
    return ca;
  }

  private static String logOf(Runnable action) {
    List<Logger> loggers = List.of((Logger) LoggerFactory.getLogger(LocalS3Tls.class),
        (Logger) LoggerFactory.getLogger(MkcertCertificateAuthority.class));
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    List<Level> levels = loggers.stream().map(Logger::getLevel).toList();
    for (Logger logger : loggers) {
      logger.addAppender(appender);
      logger.setLevel(Level.INFO);
    }
    try {
      action.run();
    } finally {
      for (int i = 0; i < loggers.size(); i++) {
        loggers.get(i).detachAppender(appender);
        loggers.get(i).setLevel(levels.get(i));
      }
    }
    return appender.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", (a, b) -> a + "\n" + b);
  }

  private static int health(LocalS3 localS3, SSLContext sslContext) throws Exception {
    HttpsURLConnection connection = (HttpsURLConnection) new URI("https://localhost:" + localS3.getPort()
        + "/_health").toURL().openConnection();
    connection.setSSLSocketFactory(sslContext.getSocketFactory());
    try (InputStream in = connection.getInputStream()) {
      assertEquals("{\"status\":\"UP\"}", new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }
    return connection.getResponseCode();
  }

}
