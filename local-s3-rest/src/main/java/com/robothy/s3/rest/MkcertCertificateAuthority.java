package com.robothy.s3.rest;

import com.robothy.s3.rest.SelfSignedCertificateGenerator.Issuer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Finds the CA of mkcert, so that a generated certificate is one that the machine already trusts; see
 * {@code docs/deployment.md#locals3-with-mkcert}.
 *
 * <p>Any CA that can't be used is skipped with a warning rather than failing the start: the certificate that signs
 * itself still serves HTTPS, and a CA that mkcert manages is not LocalS3's to fix.
 */
final class MkcertCertificateAuthority {

  private static final Logger log = LoggerFactory.getLogger(MkcertCertificateAuthority.class);

  static final String CERTIFICATE_FILE = "rootCA.pem";

  static final String PRIVATE_KEY_FILE = "rootCA-key.pem";

  private MkcertCertificateAuthority() {
  }

  /**
   * The directory of the CA of mkcert of this machine, whether or not mkcert created a CA in it.
   */
  static Optional<Path> caRoot() {
    return caRoot(System::getenv, System.getProperty("os.name", ""), System.getProperty("user.home", ""));
  }

  /**
   * The directory of the CA, as {@code mkcert -CAROOT} resolves it.
   */
  static Optional<Path> caRoot(Function<String, String> env, String osName, String userHome) {
    String caRoot = env.apply("CAROOT");
    if (caRoot != null && !caRoot.isBlank()) {
      return Optional.of(Path.of(caRoot));
    }
    String os = osName.toLowerCase(Locale.ROOT);
    if (os.startsWith("windows")) {
      String localAppData = env.apply("LOCALAPPDATA");
      return localAppData == null || localAppData.isBlank()
          ? Optional.empty()
          : Optional.of(Path.of(localAppData, "mkcert"));
    }
    if (userHome.isBlank()) {
      return Optional.empty();
    }
    if (os.startsWith("mac")) {
      return Optional.of(Path.of(userHome, "Library", "Application Support", "mkcert"));
    }
    String dataHome = env.apply("XDG_DATA_HOME");
    return dataHome == null || dataHome.isBlank()
        ? Optional.of(Path.of(userHome, ".local", "share", "mkcert"))
        : Optional.of(Path.of(dataHome, "mkcert"));
  }

  /**
   * Whether mkcert created a CA in a directory, whether or not it can issue a certificate.
   */
  static boolean hasCa(Path caRoot) {
    return Files.isRegularFile(caRoot.resolve(CERTIFICATE_FILE))
        && Files.isRegularFile(caRoot.resolve(PRIVATE_KEY_FILE));
  }

  /**
   * Load the CA in a directory: empty, silently, if it has none, i.e. mkcert isn't installed or has created no CA yet,
   * and with a warning if the CA it has can't issue a certificate.
   */
  static Optional<Issuer> load(Path caRoot) {
    if (!hasCa(caRoot)) {
      return Optional.empty();
    }
    Path certificateFile = caRoot.resolve(CERTIFICATE_FILE);
    Path keyFile = caRoot.resolve(PRIVATE_KEY_FILE);
    try {
      X509Certificate certificate;
      try (InputStream in = Files.newInputStream(certificateFile)) {
        certificate = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
      }
      if (certificate.getBasicConstraints() < 0) {
        throw new IllegalArgumentException(certificateFile + " is not the certificate of a CA");
      }
      // Expired or not yet valid; either way every client would refuse what it issues.
      certificate.checkValidity();
      PrivateKey privateKey = privateKey(Files.readString(keyFile, StandardCharsets.US_ASCII),
          certificate.getPublicKey().getAlgorithm(), keyFile);
      return Optional.of(new Issuer(certificate, privateKey));
    } catch (IOException | GeneralSecurityException | RuntimeException e) {
      log.warn("LocalS3 found the CA of mkcert in {}, but can't issue its certificate with it, and generates a"
          + " self-signed certificate instead: {}", caRoot, e.toString());
      return Optional.empty();
    }
  }

  /**
   * The unencrypted PKCS#8 key that mkcert writes, i.e. {@code -----BEGIN PRIVATE KEY-----}.
   */
  private static PrivateKey privateKey(String pem, String algorithm, Path file) throws GeneralSecurityException {
    String begin = "-----BEGIN PRIVATE KEY-----";
    String end = "-----END PRIVATE KEY-----";
    int from = pem.indexOf(begin);
    int to = pem.indexOf(end);
    if (from < 0 || to < from) {
      throw new IllegalArgumentException(file + " is not an unencrypted PKCS#8 private key");
    }
    byte[] der = Base64.getMimeDecoder().decode(pem.substring(from + begin.length(), to));
    return KeyFactory.getInstance(algorithm).generatePrivate(new PKCS8EncodedKeySpec(der));
  }

}
