package com.robothy.s3.rest;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/**
 * Generates the self-signed certificate of {@linkplain LocalS3Tls#selfSigned(String...)}, with the JDK alone: a
 * key pair from the JCA, and a X.509 v3 certificate that is DER encoded and signed here.
 *
 * <p>The JDK has no public API that issues a certificate, and the certificate that
 * {@code io.netty.handler.ssl.util.SelfSignedCertificate} generates is deprecated, names the host in the common name
 * only, and reaches into {@code sun.security.x509}. A certificate without a subject alternative name is rejected by
 * every client that matters here, e.g. curl, DuckDB, Go and Rust clients, and recent JDKs; the few hundred lines of DER
 * below issue one that names each host, and work in a GraalVM native image, where reflection into the JDK doesn't.
 *
 * <p>The certificate is a certificate authority ({@code basicConstraints: CA:TRUE}) that signed itself, so that a client
 * accepts it both as the trust anchor it is given and as the certificate the service serves; a client that only accepts
 * a CA as a trust anchor, e.g. rustls, otherwise refuses it. That is safe for the localhost certificate of a test
 * service, which lives as long as the service and is trusted only where it was explicitly installed, and unsafe
 * anywhere else, which is why it is generated rather than shipped.
 *
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc5280#section-4.1">RFC 5280 section 4.1</a>
 */
final class SelfSignedCertificateGenerator {

  /**
   * How long a generated certificate is valid. A service generates a certificate of its own on every start, so this
   * only has to outlive a long-running service, not a release.
   */
  static final Duration VALIDITY = Duration.ofDays(365);

  /**
   * How long before now a certificate is valid, so that a client whose clock is behind the one of the service, e.g. in
   * another container, doesn't reject it.
   */
  private static final Duration CLOCK_SKEW = Duration.ofHours(1);

  private static final int SEQUENCE = 0x30;

  private static final int SET = 0x31;

  private static final int INTEGER = 0x02;

  private static final int BIT_STRING = 0x03;

  private static final int OCTET_STRING = 0x04;

  private static final int NULL = 0x05;

  private static final int OBJECT_IDENTIFIER = 0x06;

  private static final int UTF8_STRING = 0x0C;

  private static final int UTC_TIME = 0x17;

  private static final int BOOLEAN = 0x01;

  /**
   * Context-specific, constructed: {@code [0] EXPLICIT} of the version, and {@code [3] EXPLICIT} of the extensions.
   */
  private static final int CONTEXT_0 = 0xA0;

  private static final int CONTEXT_3 = 0xA3;

  /**
   * {@code GeneralName.dNSName}, an {@code [2] IA5String}, and {@code GeneralName.iPAddress}, an
   * {@code [7] OCTET STRING}; both primitive.
   */
  private static final int GENERAL_NAME_DNS = 0x82;

  private static final int GENERAL_NAME_IP = 0x87;

  private static final String COMMON_NAME = "2.5.4.3";

  private static final String ORGANIZATION = "2.5.4.10";

  private static final String SUBJECT_ALTERNATIVE_NAME = "2.5.29.17";

  private static final String KEY_USAGE = "2.5.29.15";

  private static final String BASIC_CONSTRAINTS = "2.5.29.19";

  private static final String EXTENDED_KEY_USAGE = "2.5.29.37";

  private static final String SERVER_AUTHENTICATION = "1.3.6.1.5.5.7.3.1";

  /**
   * {@code yyMMddHHmmssZ} in UTC, the {@code UTCTime} that RFC 5280 requires of a date before 2050.
   */
  private static final DateTimeFormatter UTC_TIME_FORMAT =
      DateTimeFormatter.ofPattern("yyMMddHHmmss'Z'").withZone(ZoneOffset.UTC);

  private SelfSignedCertificateGenerator() {
  }

  /**
   * Generate a certificate for {@code hosts} and the private key that it belongs to.
   *
   * @param hosts the host names and IP addresses that the certificate is issued for; the first one is its common name.
   * @return the certificate and the key, in PEM format.
   * @throws IllegalArgumentException if a host is neither a host name nor an IP address.
   * @throws IllegalStateException if the JDK generates neither an EC nor an RSA key pair, or can't sign.
   */
  static LocalS3Tls generate(List<String> hosts) {
    List<String> names = new ArrayList<>(hosts.size());
    List<byte[]> subjectAlternativeNames = new ArrayList<>(hosts.size());
    for (String host : hosts) {
      Objects.requireNonNull(host, "host");
      if (host.isBlank()) {
        throw new IllegalArgumentException("A host of a self-signed certificate must not be blank.");
      }
      names.add(host.trim());
      subjectAlternativeNames.add(generalName(host.trim()));
    }

    KeyPairAlgorithm algorithm = KeyPairAlgorithm.available();
    KeyPair keyPair = algorithm.generateKeyPair();
    Instant now = Instant.now();
    byte[] tbsCertificate = tbsCertificate(names.getFirst(), subjectAlternativeNames, keyPair, algorithm, now);
    byte[] certificate = der(SEQUENCE,
        tbsCertificate,
        algorithm.signatureAlgorithmIdentifier(),
        der(BIT_STRING, new byte[] {0}, sign(tbsCertificate, keyPair.getPrivate(), algorithm)));

    return new LocalS3Tls(pem("CERTIFICATE", certificate), pem("PRIVATE KEY", keyPair.getPrivate().getEncoded()));
  }

  /**
   * The {@code TBSCertificate}: what the certificate says, and what its signature covers. Issuer and subject are the
   * same, as the certificate signs itself.
   */
  private static byte[] tbsCertificate(String commonName, List<byte[]> subjectAlternativeNames, KeyPair keyPair,
      KeyPairAlgorithm algorithm, Instant now) {
    byte[] name = distinguishedName(commonName);
    return der(SEQUENCE,
        // version: v3, so that the extensions below are read.
        der(CONTEXT_0, der(INTEGER, BigInteger.TWO.toByteArray())),
        der(INTEGER, serialNumber()),
        algorithm.signatureAlgorithmIdentifier(),
        name,
        der(SEQUENCE,
            der(UTC_TIME, UTC_TIME_FORMAT.format(now.minus(CLOCK_SKEW)).getBytes(StandardCharsets.US_ASCII)),
            der(UTC_TIME, UTC_TIME_FORMAT.format(now.plus(VALIDITY)).getBytes(StandardCharsets.US_ASCII))),
        name,
        // SubjectPublicKeyInfo, which is what a public key is encoded as.
        keyPair.getPublic().getEncoded(),
        der(CONTEXT_3, der(SEQUENCE,
            extension(SUBJECT_ALTERNATIVE_NAME, false, der(SEQUENCE, subjectAlternativeNames.toArray(byte[][]::new))),
            extension(BASIC_CONSTRAINTS, true, der(SEQUENCE, der(BOOLEAN, new byte[] {(byte) 0xFF}))),
            extension(KEY_USAGE, true, algorithm.keyUsage()),
            extension(EXTENDED_KEY_USAGE, false, der(SEQUENCE, objectIdentifier(SERVER_AUTHENTICATION))))));
  }

  /**
   * {@code Extension ::= SEQUENCE { extnID, critical BOOLEAN DEFAULT FALSE, extnValue OCTET STRING }}, where the value
   * is the DER encoding of the extension, wrapped in an octet string.
   */
  private static byte[] extension(String oid, boolean critical, byte[] value) {
    byte[] identifier = objectIdentifier(oid);
    byte[] wrapped = der(OCTET_STRING, value);
    return critical
        ? der(SEQUENCE, identifier, der(BOOLEAN, new byte[] {(byte) 0xFF}), wrapped)
        : der(SEQUENCE, identifier, wrapped);
  }

  /**
   * {@code Name}: {@code O=LocalS3, CN=<commonName>}, the most significant attribute first. The common name is where a
   * client that shows the certificate, e.g. a browser or {@code openssl s_client}, names it.
   */
  private static byte[] distinguishedName(String commonName) {
    return der(SEQUENCE,
        der(SET, der(SEQUENCE, objectIdentifier(ORGANIZATION), utf8String("LocalS3"))),
        der(SET, der(SEQUENCE, objectIdentifier(COMMON_NAME), utf8String(commonName))));
  }

  /**
   * A {@code GeneralName} of a host: an IP address literal as an {@code iPAddress}, anything else as a
   * {@code dNSName}. The address of a literal is parsed rather than resolved; a host name is not resolved at all, so
   * generating a certificate never waits for DNS.
   */
  private static byte[] generalName(String host) {
    boolean ipv6 = host.indexOf(':') >= 0;
    if (!ipv6 && !isNumeric(host)) {
      if (!host.chars().allMatch(c -> c > 0x20 && c < 0x7F)) {
        // A dNSName is an IA5String, i.e. ASCII; an internationalized name has to be given in its punycode form,
        // which is what a client compares the host it connects to against anyway.
        throw new IllegalArgumentException("The host \"" + host + "\" of a self-signed certificate is not ASCII;"
            + " give an internationalized domain name in its punycode form, e.g. xn--s3-6o0a.local.");
      }
      // An IA5String of the host name, e.g. "localhost" or "*.s3.local".
      return der(GENERAL_NAME_DNS, host.getBytes(StandardCharsets.US_ASCII));
    }
    if (!ipv6 && !isIpv4Address(host)) {
      // A host name that consists of digits and dots is no host name; resolving it would ask DNS about a typo.
      throw new IllegalArgumentException("\"" + host + "\" is not a valid IP address.");
    }
    try {
      // The format of a literal is all that is checked here; InetAddress resolves a host name, never a literal, and
      // a host that contains a colon is an IPv6 literal or nothing.
      return der(GENERAL_NAME_IP, InetAddress.getByName(host).getAddress());
    } catch (UnknownHostException e) {
      throw new IllegalArgumentException("\"" + host + "\" is not a valid IP address.", e);
    }
  }

  /**
   * Whether a host consists of digits and dots only, i.e. is an IPv4 address or a typo of one, rather than a host name.
   */
  private static boolean isNumeric(String host) {
    for (int i = 0; i < host.length(); i++) {
      char c = host.charAt(i);
      if (c != '.' && (c < '0' || c > '9')) {
        return false;
      }
    }
    return true;
  }

  /**
   * Whether a host of digits and dots is an IPv4 address: four groups of at most three digits, each at most 255.
   */
  private static boolean isIpv4Address(String host) {
    String[] groups = host.split("\\.", -1);
    if (groups.length != 4) {
      return false;
    }
    for (String group : groups) {
      if (group.isEmpty() || group.length() > 3 || Integer.parseInt(group) > 255) {
        return false;
      }
    }
    return true;
  }

  /**
   * A positive, random serial number of 20 bytes at most, as RFC 5280 requires. A client caches or pins a certificate
   * by its serial number, so a regenerated certificate must not reuse one.
   */
  private static byte[] serialNumber() {
    // 159 bits are positive and fit into 20 bytes, including the sign bit that BigInteger keeps.
    return new BigInteger(159, new SecureRandom()).toByteArray();
  }

  private static byte[] sign(byte[] content, PrivateKey privateKey, KeyPairAlgorithm algorithm) {
    try {
      Signature signature = Signature.getInstance(algorithm.signatureAlgorithm);
      signature.initSign(privateKey);
      signature.update(content);
      return signature.sign();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Can't sign a self-signed certificate with " + algorithm.signatureAlgorithm
          + ": " + e, e);
    }
  }

  private static byte[] utf8String(String value) {
    return der(UTF8_STRING, value.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * An {@code OBJECT IDENTIFIER} of its dotted notation, e.g. {@code 2.5.29.17}: the first two components in one byte,
   * and every other component base 128, most significant group first, with the high bit set on all but the last byte.
   */
  private static byte[] objectIdentifier(String oid) {
    String[] components = oid.split("\\.");
    ByteArrayOutputStream content = new ByteArrayOutputStream();
    content.write(Integer.parseInt(components[0]) * 40 + Integer.parseInt(components[1]));
    for (int i = 2; i < components.length; i++) {
      int component = Integer.parseInt(components[i]);
      int groups = 1;
      for (int remainder = component >>> 7; remainder != 0; remainder >>>= 7) {
        groups++;
      }
      for (int group = groups - 1; group >= 0; group--) {
        int value = (component >>> (group * 7)) & 0x7F;
        content.write(group == 0 ? value : value | 0x80);
      }
    }
    return der(OBJECT_IDENTIFIER, content.toByteArray());
  }

  /**
   * A DER value: the tag, the length of the contents, and the contents.
   */
  private static byte[] der(int tag, byte[]... contents) {
    int length = 0;
    for (byte[] content : contents) {
      length += content.length;
    }
    ByteArrayOutputStream encoded = new ByteArrayOutputStream(length + 4);
    encoded.write(tag);
    if (length < 0x80) {
      encoded.write(length);
    } else {
      // The long form: the number of length bytes, then the length, most significant byte first.
      byte[] lengthBytes = BigInteger.valueOf(length).toByteArray();
      int offset = lengthBytes[0] == 0 ? 1 : 0;
      encoded.write(0x80 | (lengthBytes.length - offset));
      encoded.write(lengthBytes, offset, lengthBytes.length - offset);
    }
    for (byte[] content : contents) {
      encoded.writeBytes(content);
    }
    return encoded.toByteArray();
  }

  /**
   * The PEM encoding of a DER value: the label, and Base64 in lines of 64 characters.
   */
  private static String pem(String label, byte[] der) {
    String base64 = Base64.getEncoder().encodeToString(der);
    StringBuilder pem = new StringBuilder(base64.length() + 128);
    pem.append("-----BEGIN ").append(label).append("-----\n");
    for (int offset = 0; offset < base64.length(); offset += 64) {
      pem.append(base64, offset, Math.min(offset + 64, base64.length())).append('\n');
    }
    return pem.append("-----END ").append(label).append("-----\n").toString();
  }

  /**
   * The key pair of a certificate: an EC key on the P-256 curve, which every TLS stack of the last decade verifies and
   * which the JDK generates in milliseconds, or a 2048 bit RSA key where EC isn't available, e.g. in a JVM whose
   * security providers are restricted.
   */
  private enum KeyPairAlgorithm {

    EC("EC", 256, "SHA256withECDSA", "1.2.840.10045.4.3.2", false),
    RSA("RSA", 2048, "SHA256withRSA", "1.2.840.113549.1.1.11", true);

    private final String keyAlgorithm;

    private final int keySize;

    private final String signatureAlgorithm;

    private final String signatureOid;

    /**
     * Whether the {@code AlgorithmIdentifier} of the signature carries explicit {@code NULL} parameters, which RFC 4055
     * requires of the RSA algorithms and RFC 5758 forbids for the ECDSA ones.
     */
    private final boolean nullParameters;

    KeyPairAlgorithm(String keyAlgorithm, int keySize, String signatureAlgorithm, String signatureOid,
        boolean nullParameters) {
      this.keyAlgorithm = keyAlgorithm;
      this.keySize = keySize;
      this.signatureAlgorithm = signatureAlgorithm;
      this.signatureOid = signatureOid;
      this.nullParameters = nullParameters;
    }

    /**
     * The first algorithm that this JVM generates a key pair and a signature with.
     *
     * @throws IllegalStateException if it supports neither.
     */
    static KeyPairAlgorithm available() {
      for (KeyPairAlgorithm algorithm : values()) {
        try {
          Signature.getInstance(algorithm.signatureAlgorithm);
          KeyPairGenerator.getInstance(algorithm.keyAlgorithm);
          return algorithm;
        } catch (GeneralSecurityException | RuntimeException e) {
          // Try the next one; the JVM supports neither if there is none.
        }
      }
      throw new IllegalStateException("This JVM generates neither an EC nor an RSA key pair, so LocalS3 can't"
          + " generate a self-signed certificate. Configure a certificate and its private key instead.");
    }

    KeyPair generateKeyPair() {
      try {
        KeyPairGenerator generator = KeyPairGenerator.getInstance(keyAlgorithm);
        if (this == EC) {
          generator.initialize(new ECGenParameterSpec("secp256r1"), new SecureRandom());
        } else {
          generator.initialize(keySize, new SecureRandom());
        }
        return generator.generateKeyPair();
      } catch (GeneralSecurityException e) {
        throw new IllegalStateException("Can't generate a " + keySize + " bit " + keyAlgorithm + " key pair for a"
            + " self-signed certificate: " + e, e);
      }
    }

    byte[] signatureAlgorithmIdentifier() {
      return nullParameters
          ? der(SEQUENCE, objectIdentifier(signatureOid), der(NULL))
          : der(SEQUENCE, objectIdentifier(signatureOid));
    }

    /**
     * The {@code KeyUsage} bit string: {@code digitalSignature} and {@code keyCertSign}, which the certificate needs as
     * the CA that signed itself, and {@code keyEncipherment} for the RSA key exchange of a client without ECDHE. The
     * first content byte of a named bit string is the number of unused bits of the last one.
     */
    byte[] keyUsage() {
      // digitalSignature is bit 0, keyEncipherment bit 2 and keyCertSign bit 5, from the most significant bit.
      int bits = 0b1000_0000 | (this == RSA ? 0b0010_0000 : 0) | 0b0000_0100;
      return der(BIT_STRING, new byte[] {2, (byte) bits});
    }

  }

}
