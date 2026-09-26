package com.robothy.s3.rest.handler;

import com.robothy.s3.core.exception.S3ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Issues and resolves the temporary credentials of the STS endpoint of LocalS3, without storing them, the way MinIO
 * does: everything that verifying a request signed with temporary credentials needs is carried by the session token,
 * and authenticated with a key that only LocalS3 has.
 *
 * <p>A session token is {@code base64url(payload) "." base64url(HMAC-SHA256(key, "token\n" payload))}, where the
 * payload is the version of the format, the access key ID, the expiration in epoch seconds, the ARN and the user ID of
 * the identity that the credentials act as, and the compact JSON of the session policy, empty for none, separated by
 * line feeds. The secret access key is {@code base64(HMAC-SHA256(key, "secret\n" payload))} truncated to 40
 * characters, which a client signs with and
 * LocalS3 derives again from the token of a request. So the credentials are valid across restarts, in memory and
 * persistence mode alike, until they expire, and no token can be forged or bound to another access key ID without
 * the key.
 *
 * <p>The key is derived from the secret access key of LocalS3; a LocalS3 that doesn't verify signatures has none, and
 * uses a random key instead, since the credentials it issues are never verified.
 */
final class SessionCredentialIssuer {

  /**
   * The shortest session that STS issues credentials for.
   */
  static final Duration MIN_DURATION = Duration.ofMinutes(15);

  private static final String VERSION = "2";

  private static final String ACCESS_KEY_ID_PREFIX = "ASIA";

  private static final char[] BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();

  private static final int SECRET_ACCESS_KEY_LENGTH = 40;

  private final byte[] key;

  private final Clock clock;

  private final SecureRandom random = new SecureRandom();

  /**
   * Create an issuer.
   *
   * @param secretAccessKey the secret access key of LocalS3, which the key of the issuer is derived from; {@code null}
   *     if LocalS3 doesn't verify signatures.
   * @param clock the clock that credentials expire by.
   */
  SessionCredentialIssuer(String secretAccessKey, Clock clock) {
    this.clock = Objects.requireNonNull(clock);
    if (secretAccessKey == null) {
      this.key = new byte[32];
      random.nextBytes(this.key);
    } else {
      this.key = hmac(secretAccessKey.getBytes(StandardCharsets.UTF_8), "LocalS3 STS session credentials");
    }
  }

  /**
   * Issue temporary credentials.
   *
   * @param duration how long the credentials are valid for.
   * @param arn the ARN of the identity that the credentials act as.
   * @param userId the unique ID of that identity.
   * @return the credentials.
   */
  SessionCredentials issue(Duration duration, String arn, String userId) {
    return issue(duration, arn, userId, null);
  }

  /**
   * Issue temporary credentials that a session policy limits.
   *
   * @param duration how long the credentials are valid for.
   * @param arn the ARN of the identity that the credentials act as.
   * @param userId the unique ID of that identity.
   * @param policy the compact JSON of the session policy, see {@linkplain SessionPolicy#compact}; {@code null} for
   *     none.
   * @return the credentials.
   */
  SessionCredentials issue(Duration duration, String arn, String userId, String policy) {
    String accessKeyId = ACCESS_KEY_ID_PREFIX + randomBase32(16);
    Instant expiration = clock.instant().plus(duration).truncatedTo(ChronoUnit.SECONDS);
    Session session = new Session(accessKeyId, expiration, arn, userId, policy);
    String payload = payload(session);
    String sessionToken = base64Url(payload.getBytes(StandardCharsets.UTF_8)) + "."
        + base64Url(hmac(key, "token\n" + payload));
    return new SessionCredentials(accessKeyId, secretAccessKey(payload), sessionToken, session);
  }

  /**
   * Resolve the temporary credentials that a request is signed with.
   *
   * @param accessKeyId the access key ID of the credential scope of the request.
   * @param sessionToken the session token of the request.
   * @return the session and its secret access key, or why the token isn't accepted.
   */
  Resolution resolve(String accessKeyId, String sessionToken) {
    Session session = decode(sessionToken);
    if (session == null || !session.accessKeyId().equals(accessKeyId)) {
      return Resolution.failure(S3ErrorCode.InvalidToken);
    }
    if (!clock.instant().isBefore(session.expiration())) {
      return Resolution.failure(S3ErrorCode.ExpiredToken);
    }
    return new Resolution(session, secretAccessKey(payload(session)), null);
  }

  /**
   * The session of a token that this issuer issued, whether or not it has expired.
   *
   * @return the session; {@code null} if the token is malformed or wasn't issued with the key of this issuer.
   */
  Session decode(String sessionToken) {
    if (sessionToken == null) {
      return null;
    }
    int separator = sessionToken.indexOf('.');
    if (separator <= 0 || separator != sessionToken.lastIndexOf('.')) {
      return null;
    }
    try {
      String payload = new String(Base64.getUrlDecoder().decode(sessionToken.substring(0, separator)),
          StandardCharsets.UTF_8);
      byte[] mac = Base64.getUrlDecoder().decode(sessionToken.substring(separator + 1));
      if (!MessageDigest.isEqual(hmac(key, "token\n" + payload), mac)) {
        return null;
      }
      // The policy is the last field, and compact JSON has no line feed of its own.
      String[] fields = payload.split("\n", -1);
      if (fields.length != 6 || !VERSION.equals(fields[0])) {
        return null;
      }
      return new Session(fields[1], Instant.ofEpochSecond(Long.parseLong(fields[2])), fields[3], fields[4],
          fields[5].isEmpty() ? null : fields[5]);
    } catch (IllegalArgumentException e) {
      // Neither base64url nor a number.
      return null;
    }
  }

  private String secretAccessKey(String payload) {
    return Base64.getEncoder().encodeToString(hmac(key, "secret\n" + payload))
        .substring(0, SECRET_ACCESS_KEY_LENGTH);
  }

  private static String payload(Session session) {
    return String.join("\n", VERSION, session.accessKeyId(), Long.toString(session.expiration().getEpochSecond()),
        session.arn(), session.userId(), Objects.requireNonNullElse(session.policy(), ""));
  }

  private String randomBase32(int length) {
    char[] chars = new char[length];
    for (int i = 0; i < length; i++) {
      chars[i] = BASE32[random.nextInt(BASE32.length)];
    }
    return new String(chars);
  }

  /**
   * A stable, AWS-like unique ID of a name, e.g. of a role, which is the same every time the name is.
   *
   * @param prefix the prefix of the kind of ID, e.g. {@code AROA} for a role.
   * @param name the name.
   * @return the ID.
   */
  static String uniqueId(String prefix, String name) {
    byte[] digest;
    try {
      digest = MessageDigest.getInstance("SHA-256").digest(name.getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new IllegalStateException("Unable to calculate a SHA-256 digest.", e);
    }
    StringBuilder id = new StringBuilder(prefix);
    for (int i = 0; i < 17; i++) {
      id.append(BASE32[(digest[i] & 0xff) % BASE32.length]);
    }
    return id.toString();
  }

  private static String base64Url(byte[] value) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
  }

  private static byte[] hmac(byte[] key, String value) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new IllegalStateException("Unable to calculate an HMAC-SHA256 signature.", e);
    }
  }

  /**
   * The identity and the validity of temporary credentials.
   *
   * @param accessKeyId the access key ID of the credentials.
   * @param expiration when the credentials expire.
   * @param arn the ARN of the identity that the credentials act as, e.g. an assumed role.
   * @param userId the unique ID of that identity.
   * @param policy the compact JSON of the session policy that limits the credentials; {@code null} for none.
   */
  record Session(String accessKeyId, Instant expiration, String arn, String userId, String policy) {
  }

  /**
   * Temporary credentials that were issued.
   */
  record SessionCredentials(String accessKeyId, String secretAccessKey, String sessionToken, Session session) {

    @Override
    public String toString() {
      // Never reveal the secret access key or the session token.
      return "SessionCredentials[accessKeyId=" + accessKeyId + ", session=" + session + "]";
    }
  }

  /**
   * The temporary credentials of a request.
   *
   * @param session the session; {@code null} if the token isn't accepted.
   * @param secretAccessKey the secret access key that the request is signed with; {@code null} if the token isn't
   *     accepted.
   * @param errorCode why the token isn't accepted; {@code null} if it is.
   */
  record Resolution(Session session, String secretAccessKey, S3ErrorCode errorCode) {

    static Resolution failure(S3ErrorCode errorCode) {
      return new Resolution(null, null, errorCode);
    }

    boolean accepted() {
      return errorCode == null;
    }

    @Override
    public String toString() {
      // Never reveal the secret access key.
      return "Resolution[session=" + session + ", errorCode=" + errorCode + "]";
    }
  }
}
