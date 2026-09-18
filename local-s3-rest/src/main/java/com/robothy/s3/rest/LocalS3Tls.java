package com.robothy.s3.rest;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

/**
 * The certificate and private key that a {@linkplain LocalS3} service serves HTTPS with, in PEM format, e.g. the
 * {@code example.org.pem} and {@code example.org-key.pem} that <a href="https://github.com/FiloSottile/mkcert">mkcert</a>
 * creates for {@code mkcert example.org}.
 *
 * <p>The PEM content is kept, not the files, so a service serves the certificate that it was configured with even if
 * the files change or are removed afterwards. Where no certificate of the machine is at hand,
 * {@linkplain #selfSigned()} generates one for {@code localhost} on the spot.
 *
 * @param certificateChainPem the certificate, optionally followed by its intermediate certificates, in PEM format.
 * @param privateKeyPem the unencrypted PKCS#8 private key of the certificate in PEM format, i.e.
 *     {@code -----BEGIN PRIVATE KEY-----}.
 */
public record LocalS3Tls(String certificateChainPem, String privateKeyPem) {

  /**
   * The hosts that {@linkplain #selfSigned()} issues a certificate for: a local service, by each name that a client
   * reaches it by.
   */
  public static final List<String> DEFAULT_SELF_SIGNED_HOSTS = List.of("localhost", "127.0.0.1", "::1");

  private static final String PEM_BEGIN = "-----BEGIN ";

  /**
   * Validate that both components are PEM content.
   *
   * @throws IllegalArgumentException if a component isn't PEM content.
   * @throws NullPointerException if a component is {@code null}.
   */
  public LocalS3Tls {
    Objects.requireNonNull(certificateChainPem, "certificateChainPem");
    Objects.requireNonNull(privateKeyPem, "privateKeyPem");
    if (!certificateChainPem.contains(PEM_BEGIN)) {
      throw new IllegalArgumentException("certificateChainPem is not PEM content.");
    }
    if (!privateKeyPem.contains(PEM_BEGIN)) {
      throw new IllegalArgumentException("privateKeyPem is not PEM content.");
    }
  }

  /**
   * Create the TLS configuration from a certificate and a private key, each given either as PEM content or as the path
   * of a PEM file. Both are read and validated at once, so a missing file or a key that doesn't fit fails here rather
   * than when the service starts.
   *
   * @param certPem the certificate chain, as PEM content or as the path of a PEM file.
   * @param keyPem the private key, as PEM content or as the path of a PEM file.
   * @return the TLS configuration.
   * @throws IllegalArgumentException if a file can't be read, or the certificate and key are invalid.
   */
  public static LocalS3Tls of(String certPem, String keyPem) {
    LocalS3Tls tls = new LocalS3Tls(pem(certPem, "certificate"), pem(keyPem, "private key"));
    tls.newServerSslContext();
    return tls;
  }

  /**
   * Generate a certificate for {@code localhost}, {@code 127.0.0.1} and {@code ::1}, so that a client that uses HTTPS
   * by default, e.g. DuckDB, connects to a local service without a certificate of its own:
   *
   * <pre>{@code LocalS3.builder().tls(LocalS3Tls.selfSigned()).build()}</pre>
   *
   * <p>Nothing trusts the certificate yet: a client either has to be given it, e.g. with
   * {@code --ca-bundle <the PEM file>} or {@linkplain #newClientSslContext()} for a client in the same JVM, or has to
   * be told not to verify the certificate at all. The service logs the certificate in PEM format when it starts, so
   * that it can be saved to a file and handed to a client. See
   * <a href="https://github.com/Robothy/local-s3/blob/main/docs/deployment.md#https">deployment.md</a>.
   *
   * <p>A certificate is generated where it is asked for, in a few milliseconds, and is valid for a year. Two calls
   * return two certificates, so a client that is given the certificate of one service doesn't trust another.
   *
   * @return the generated certificate and its private key.
   * @throws IllegalStateException if the JVM generates neither an EC nor an RSA key pair.
   */
  public static LocalS3Tls selfSigned() {
    return selfSigned(DEFAULT_SELF_SIGNED_HOSTS.toArray(String[]::new));
  }

  /**
   * Generate a certificate for the given host names and IP addresses, e.g.
   * {@code selfSigned("localhost", "127.0.0.1", "s3", "*.s3.local")} for a service that is also reached by the name of
   * its container and by {@linkplain LocalS3Builder#virtualHostDomains(String...) virtual-hosted-style} requests. A
   * client verifies that the host it connects to is one of them, so every name that clients use has to be listed; see
   * {@linkplain #selfSigned()} for what a certificate is good for and what it isn't.
   *
   * @param hosts the host names and IP addresses to issue the certificate for, at least one; the first one is also its
   *     common name. An IP address literal, e.g. {@code 127.0.0.1} or {@code ::1}, becomes an {@code iPAddress}, and
   *     anything else a {@code dNSName}, which may be a wildcard like {@code *.s3.local}. A host name is not resolved.
   * @return the generated certificate and its private key.
   * @throws IllegalArgumentException if {@code hosts} is empty, or a host is blank or an invalid IP address.
   * @throws IllegalStateException if the JVM generates neither an EC nor an RSA key pair.
   */
  public static LocalS3Tls selfSigned(String... hosts) {
    Objects.requireNonNull(hosts, "hosts");
    if (hosts.length == 0) {
      throw new IllegalArgumentException("A self-signed certificate must be issued for at least one host.");
    }
    return SelfSignedCertificateGenerator.generate(List.of(hosts));
  }

  /**
   * Create the server-side SSL context of the certificate and key.
   *
   * @return a new SSL context.
   * @throws IllegalArgumentException if the certificate or key can't be parsed, or don't belong together.
   */
  public SslContext newServerSslContext() {
    try {
      return SslContextBuilder.forServer(
              new ByteArrayInputStream(certificateChainPem.getBytes(StandardCharsets.UTF_8)),
              new ByteArrayInputStream(privateKeyPem.getBytes(StandardCharsets.UTF_8)))
          .build();
    } catch (SSLException | IllegalArgumentException e) {
      throw new IllegalArgumentException("Invalid TLS certificate or private key: " + e.getMessage(), e);
    }
  }

  /**
   * The certificate that the service serves, i.e. the first one of the chain.
   *
   * @return the parsed certificate.
   * @throws IllegalArgumentException if the certificate can't be parsed.
   */
  public X509Certificate certificate() {
    try (InputStream pem = new ByteArrayInputStream(certificateChainPem.getBytes(StandardCharsets.UTF_8))) {
      return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(pem);
    } catch (CertificateException | IOException e) {
      throw new IllegalArgumentException("Invalid TLS certificate: " + e, e);
    }
  }

  /**
   * Whether the certificate signed itself, i.e. whether nothing trusts it unless it is installed itself, which is what
   * {@linkplain #selfSigned()} generates.
   *
   * @return {@code true} if the issuer of the certificate is the certificate.
   */
  public boolean isSelfSigned() {
    X509Certificate certificate = certificate();
    return certificate.getIssuerX500Principal().equals(certificate.getSubjectX500Principal());
  }

  /**
   * The host names and IP addresses that the certificate is issued for, i.e. the hosts that a client reaches the
   * service by without a certificate error.
   *
   * @return the subject alternative names of the certificate; empty if it has none, which every current client
   *     rejects.
   */
  public List<String> hosts() {
    Collection<List<?>> names;
    try {
      names = certificate().getSubjectAlternativeNames();
    } catch (CertificateParsingException e) {
      throw new IllegalArgumentException("Invalid TLS certificate: " + e, e);
    }
    if (names == null) {
      return List.of();
    }
    List<String> hosts = new ArrayList<>(names.size());
    for (List<?> name : names) {
      // { the type of the GeneralName, its value }; 2 is a dNSName and 7 an iPAddress, both already strings.
      Integer type = (Integer) name.getFirst();
      if ((type == 2 || type == 7) && name.get(1) instanceof String host) {
        hosts.add(host);
      }
    }
    return List.copyOf(hosts);
  }

  /**
   * Create a client-side SSL context that trusts this certificate and nothing else, for a client in the JVM that
   * embeds the service, e.g. an {@code S3Client} of a test against a service with a
   * {@linkplain #selfSigned() self-signed} certificate.
   *
   * <p>A JVM client otherwise refuses a self-signed certificate, as the JVM trust store doesn't have it. The context
   * still verifies the host name, so the client has to connect to one of {@linkplain #hosts()}.
   *
   * @return a new SSL context that trusts the certificate.
   * @throws IllegalArgumentException if the certificate can't be parsed.
   */
  public SSLContext newClientSslContext() {
    try {
      SSLContext sslContext = SSLContext.getInstance("TLS");
      sslContext.init(null, trustManagers(), null);
      return sslContext;
    } catch (GeneralSecurityException e) {
      throw new IllegalArgumentException("Can't trust the TLS certificate: " + e, e);
    }
  }

  /**
   * Trust managers that trust this certificate and nothing else, for a client that takes trust managers rather than an
   * {@linkplain #newClientSslContext() SSL context}, e.g. the {@code TlsTrustManagersProvider} of the HTTP clients of
   * the AWS SDK:
   *
   * <pre>{@code ApacheHttpClient.builder().tlsTrustManagersProvider(tls::trustManagers).build()}</pre>
   *
   * @return new trust managers that trust the certificate.
   * @throws IllegalArgumentException if the certificate can't be parsed.
   */
  public TrustManager[] trustManagers() {
    try {
      KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
      trustStore.load(null, null);
      trustStore.setCertificateEntry("local-s3", certificate());
      TrustManagerFactory trustManagers =
          TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      trustManagers.init(trustStore);
      return trustManagers.getTrustManagers();
    } catch (GeneralSecurityException | IOException e) {
      throw new IllegalArgumentException("Can't trust the TLS certificate: " + e, e);
    }
  }

  /**
   * Describe the certificate in one line, without its private key: what it is issued for, until when, and its SHA-256
   * fingerprint, which is what a client shows of a certificate it doesn't trust.
   *
   * @return the description, e.g. {@code CN=localhost, O=LocalS3 for [localhost, 127.0.0.1, ::1], self-signed, valid
   *     until 2026-09-18T08:12:31Z, SHA-256 fingerprint 3A:7B:…}.
   */
  public String describe() {
    X509Certificate certificate = certificate();
    return certificate.getSubjectX500Principal().getName() + " for " + hosts()
        + (isSelfSigned() ? ", self-signed" : ", issued by " + certificate.getIssuerX500Principal().getName())
        + ", valid until " + certificate.getNotAfter().toInstant()
        + ", SHA-256 fingerprint " + fingerprint(certificate);
  }

  /**
   * The SHA-256 fingerprint of the DER encoding of a certificate, in the upper case, colon separated notation that
   * {@code openssl x509 -fingerprint} and the clients print.
   */
  private static String fingerprint(X509Certificate certificate) {
    byte[] digest;
    try {
      digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
    } catch (GeneralSecurityException e) {
      return "unavailable (" + e + ")";
    }
    StringBuilder fingerprint = new StringBuilder(digest.length * 3);
    for (byte b : digest) {
      if (!fingerprint.isEmpty()) {
        fingerprint.append(':');
      }
      fingerprint.append("%02X".formatted(b));
    }
    return fingerprint.toString();
  }

  /**
   * Leaves the private key out, so that logging the configuration doesn't reveal it.
   */
  @Override
  public String toString() {
    return "LocalS3Tls[certificateChainPem=" + certificateChainPem.length() + " chars, privateKeyPem=****]";
  }

  private static String pem(String contentOrPath, String name) {
    Objects.requireNonNull(contentOrPath, name);
    if (contentOrPath.contains(PEM_BEGIN)) {
      return contentOrPath;
    }
    if (contentOrPath.isBlank()) {
      throw new IllegalArgumentException("The TLS " + name + " must not be blank.");
    }
    Path path = Path.of(contentOrPath.trim());
    try {
      return Files.readString(path, StandardCharsets.UTF_8);
    } catch (IOException | UncheckedIOException e) {
      throw new IllegalArgumentException("Can't read the TLS " + name + " file " + path + ": " + e, e);
    }
  }

}
