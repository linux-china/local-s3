package com.robothy.s3.rest;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import javax.net.ssl.SSLException;

/**
 * The certificate and private key that a {@linkplain LocalS3} service serves HTTPS with, in PEM format, e.g. the
 * {@code example.org.pem} and {@code example.org-key.pem} that <a href="https://github.com/FiloSottile/mkcert">mkcert</a>
 * creates for {@code mkcert example.org}.
 *
 * <p>The PEM content is kept, not the files, so a service serves the certificate that it was configured with even if
 * the files change or are removed afterwards.
 *
 * @param certificateChainPem the certificate, optionally followed by its intermediate certificates, in PEM format.
 * @param privateKeyPem the unencrypted PKCS#8 private key of the certificate in PEM format, i.e.
 *     {@code -----BEGIN PRIVATE KEY-----}.
 */
public record LocalS3Tls(String certificateChainPem, String privateKeyPem) {

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
