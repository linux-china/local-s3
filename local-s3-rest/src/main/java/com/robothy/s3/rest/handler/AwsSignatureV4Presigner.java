package com.robothy.s3.rest.handler;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

/**
 * Signs presigned URLs, the counterpart of the presigned URLs that {@linkplain AwsSignatureV4Verifier} verifies: it
 * derives the signing key and the string to sign the same way, so a URL signed here is one that the service accepts.
 *
 * <p>A URL is signed for the {@code s3} service, in the path style, with {@code host} as its only signed header and
 * {@code UNSIGNED-PAYLOAD} as its payload hash, so the client sends nothing but the URL. The region of the credential
 * scope is signed, but LocalS3 doesn't check which region it is.
 *
 * <p>A service that has no credentials answers unsigned requests, and {@linkplain #unsigned()} presigns for it: it
 * checks and builds the URL of an object the same way, but the URL carries no signature and doesn't expire.
 */
public final class AwsSignatureV4Presigner {

  /**
   * The default region of the credential scope, which clients of LocalS3 usually configure.
   */
  public static final String DEFAULT_REGION = "us-east-1";

  private static final String SERVICE = "s3";
  private static final String TERMINATOR = "aws4_request";
  private static final String UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD";
  private static final String SIGNED_HEADERS = "host";

  /**
   * The credentials that a URL is signed with; {@code null} on a presigner of a service that answers unsigned
   * requests, which signs none.
   */
  private final String accessKeyId;
  private final String secretAccessKey;
  private final String region;
  private final Clock clock;

  private AwsSignatureV4Presigner() {
    this.accessKeyId = null;
    this.secretAccessKey = null;
    this.region = DEFAULT_REGION;
    this.clock = Clock.systemUTC();
  }

  /**
   * Create a presigner that signs with the system clock and {@linkplain #DEFAULT_REGION}.
   *
   * @param accessKeyId the access key ID of LocalS3.
   * @param secretAccessKey the secret access key of LocalS3.
   */
  public AwsSignatureV4Presigner(String accessKeyId, String secretAccessKey) {
    this(accessKeyId, secretAccessKey, DEFAULT_REGION, Clock.systemUTC());
  }

  /**
   * Create a presigner.
   *
   * @param accessKeyId the access key ID of LocalS3.
   * @param secretAccessKey the secret access key of LocalS3.
   * @param region the region of the credential scope.
   * @param clock the clock that a URL is signed at, from which its expiration is counted.
   */
  public AwsSignatureV4Presigner(String accessKeyId, String secretAccessKey, String region, Clock clock) {
    if (accessKeyId == null || accessKeyId.isBlank()) {
      throw new IllegalArgumentException("accessKeyId must not be blank.");
    }
    if (secretAccessKey == null || secretAccessKey.isBlank()) {
      throw new IllegalArgumentException("secretAccessKey must not be blank.");
    }
    if (region == null || region.isBlank()) {
      throw new IllegalArgumentException("region must not be blank.");
    }
    this.accessKeyId = accessKeyId;
    this.secretAccessKey = secretAccessKey;
    this.region = region;
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * A presigner for a service that answers unsigned requests, which needs no credentials to reach an object: its
   * {@linkplain #presign} checks its arguments and builds the same URL, without a signature and without an
   * expiration.
   *
   * @return the presigner.
   */
  public static AwsSignatureV4Presigner unsigned() {
    return new AwsSignatureV4Presigner();
  }

  /**
   * Sign a URL of an object that is valid for a while, e.g.
   * {@code http://127.0.0.1:19090/bucket/a.txt?X-Amz-Algorithm=...&X-Amz-Signature=...}.
   *
   * @param endpoint the endpoint of the service, e.g. {@code http://127.0.0.1:19090}.
   * @param method the HTTP method that the URL is signed for, e.g. {@code GET}; a request of another method is
   *     rejected, since the method is signed.
   * @param bucketName the bucket of the object.
   * @param key the key of the object.
   * @param expiration how long the URL is valid, between 1 second and 7 days, the longest expiration that Amazon S3
   *     signs; an {@linkplain #unsigned()} presigner checks it and hands out a URL that doesn't expire.
   * @return the presigned URL, or the plain URL of the object on an {@linkplain #unsigned()} presigner.
   * @throws IllegalArgumentException if the endpoint isn't an absolute URL with a host, the method or the names are
   *     blank, or the expiration is out of range.
   */
  public String presign(String endpoint, String method, String bucketName, String key, Duration expiration) {
    String httpMethod = requireNotBlank(method, "method").trim().toUpperCase(Locale.ROOT);
    Objects.requireNonNull(expiration, "expiration");
    long expiresInSeconds = expiration.getSeconds();
    if (expiresInSeconds < 1 || expiresInSeconds > AwsSignatureV4Verifier.MAX_PRESIGNED_EXPIRY_SECONDS) {
      throw new IllegalArgumentException("expiration must be between 1 second and "
          + AwsSignatureV4Verifier.MAX_PRESIGNED_EXPIRY_SECONDS + " seconds.");
    }

    URI base = baseUri(endpoint);
    String canonicalPath = objectPath(bucketName, key);
    if (accessKeyId == null) {
      return base + canonicalPath;
    }

    String amzDate = AwsSignatureV4Verifier.AMZ_DATE_FORMAT.format(clock.instant());
    String date = amzDate.substring(0, 8);
    String scope = date + '/' + region + '/' + SERVICE + '/' + TERMINATOR;

    // The signed parameters, encoded and in the order that the canonical request sorts them into, so that the query
    // of the URL is the canonical query; the verifier leaves X-Amz-Signature out of the canonical query.
    String canonicalQuery = "X-Amz-Algorithm=" + uriEncode(AwsSignatureV4Verifier.ALGORITHM, false)
        + "&X-Amz-Credential=" + uriEncode(accessKeyId + '/' + scope, false)
        + "&X-Amz-Date=" + amzDate
        + "&X-Amz-Expires=" + expiresInSeconds
        + "&X-Amz-SignedHeaders=" + SIGNED_HEADERS;

    String canonicalRequest = httpMethod + '\n'
        + canonicalPath + '\n'
        + canonicalQuery + '\n'
        + SIGNED_HEADERS + ':' + hostHeader(base) + '\n'
        + '\n'
        + SIGNED_HEADERS + '\n'
        + UNSIGNED_PAYLOAD;

    String signature = AwsSignatureV4Verifier.signature(
        AwsSignatureV4Verifier.signingKey(secretAccessKey, date, region, SERVICE),
        AwsSignatureV4Verifier.stringToSign(amzDate, scope, canonicalRequest));

    return base + canonicalPath + '?' + canonicalQuery + "&X-Amz-Signature=" + signature;
  }

  /**
   * The scheme and authority of an endpoint, e.g. {@code http://127.0.0.1:19090}, which a path is appended to.
   */
  private static URI baseUri(String endpoint) {
    URI uri;
    try {
      uri = new URI(requireNotBlank(endpoint, "endpoint").trim());
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException("endpoint is not a valid URL: " + endpoint, e);
    }
    if (uri.getScheme() == null || uri.getRawAuthority() == null) {
      throw new IllegalArgumentException("endpoint must be an absolute URL, e.g. http://127.0.0.1:19090, but was: "
          + endpoint);
    }
    return URI.create(uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getRawAuthority());
  }

  /**
   * The {@code host} header that a client sends for a URL, which is signed: the authority of the URL, without the
   * port when it is the default port of the scheme, which clients leave out of the header.
   */
  private static String hostHeader(URI base) {
    String authority = base.getRawAuthority();
    String defaultPort = "https".equals(base.getScheme()) ? ":443" : ":80";
    return authority.endsWith(defaultPort)
        ? authority.substring(0, authority.length() - defaultPort.length())
        : authority;
  }

  /**
   * The encoded path of an object in the path style, e.g. {@code /bucket/a%20b.txt}, which is also its canonical path:
   * the service decodes it back into the key, and the signature covers it as it is sent.
   */
  private static String objectPath(String bucketName, String key) {
    return '/' + uriEncode(requireNotBlank(bucketName, "bucketName"), false)
        + '/' + uriEncode(requireNotBlank(key, "key"), true);
  }

  /**
   * Encode a value for a URI like AWS Signature Version 4 does: every byte but an unreserved character is
   * percent-encoded with uppercase hex digits.
   *
   * @param value the value.
   * @param preserveSlash whether a slash is kept, which the path of an object key does and a query parameter doesn't.
   * @return the encoded value.
   */
  private static String uriEncode(String value, boolean preserveSlash) {
    StringBuilder result = new StringBuilder(value.length());
    for (byte item : value.getBytes(StandardCharsets.UTF_8)) {
      int unsigned = item & 0xff;
      if (AwsSignatureV4Verifier.isUnreserved(unsigned) || preserveSlash && unsigned == '/') {
        result.append((char) unsigned);
      } else {
        result.append('%').append(HexFormat.of().withUpperCase().toHexDigits((byte) unsigned));
      }
    }
    return result.toString();
  }

  private static String requireNotBlank(String value, String name) {
    if (Objects.requireNonNull(value, name).isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank.");
    }
    return value;
  }

}
