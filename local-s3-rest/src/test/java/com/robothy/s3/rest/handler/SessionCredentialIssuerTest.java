package com.robothy.s3.rest.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.core.exception.S3ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class SessionCredentialIssuerTest {

  private static final String SECRET_ACCESS_KEY = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";

  private static final String ARN = "arn:aws:sts::123456789012:assumed-role/reader/session";

  private static final Instant NOW = Instant.parse("2026-09-17T08:00:00Z");

  private static Clock at(Instant instant) {
    return Clock.fixed(instant, ZoneOffset.UTC);
  }

  @Test
  void resolvesTheCredentialsThatItIssued() {
    SessionCredentialIssuer issuer = new SessionCredentialIssuer(SECRET_ACCESS_KEY, at(NOW));
    SessionCredentialIssuer.SessionCredentials credentials = issuer.issue(Duration.ofHours(1), ARN, "AROA:session");

    assertTrue(credentials.accessKeyId().matches("ASIA[A-Z2-7]{16}"));
    assertEquals(40, credentials.secretAccessKey().length());
    assertEquals(NOW.plus(Duration.ofHours(1)), credentials.session().expiration());

    SessionCredentialIssuer.Resolution resolution = issuer.resolve(credentials.accessKeyId(),
        credentials.sessionToken());
    assertTrue(resolution.accepted());
    assertEquals(credentials.secretAccessKey(), resolution.secretAccessKey());
    assertEquals(credentials.session(), resolution.session());
    assertNotEquals(credentials.secretAccessKey(),
        issuer.issue(Duration.ofHours(1), ARN, "AROA:session").secretAccessKey());
  }

  /**
   * Nothing is stored: a LocalS3 that restarts with the same secret access key accepts the credentials, one with another
   * key doesn't.
   */
  @Test
  void theCredentialsOutliveTheIssuerButNotItsKey() {
    SessionCredentialIssuer.SessionCredentials credentials = new SessionCredentialIssuer(SECRET_ACCESS_KEY, at(NOW))
        .issue(Duration.ofHours(1), ARN, "AROA:session");

    SessionCredentialIssuer.Resolution restarted = new SessionCredentialIssuer(SECRET_ACCESS_KEY, at(NOW))
        .resolve(credentials.accessKeyId(), credentials.sessionToken());
    assertEquals(credentials.secretAccessKey(), restarted.secretAccessKey());

    assertEquals(S3ErrorCode.InvalidToken, new SessionCredentialIssuer("another secret", at(NOW))
        .resolve(credentials.accessKeyId(), credentials.sessionToken()).errorCode());
  }

  @Test
  void rejectsAnExpiredForgedOrForeignToken() {
    SessionCredentialIssuer issuer = new SessionCredentialIssuer(SECRET_ACCESS_KEY, at(NOW));
    SessionCredentialIssuer.SessionCredentials credentials = issuer.issue(Duration.ofMinutes(15), ARN, "AROA:session");
    String token = credentials.sessionToken();

    assertEquals(S3ErrorCode.ExpiredToken, new SessionCredentialIssuer(SECRET_ACCESS_KEY,
        at(NOW.plus(Duration.ofMinutes(15)))).resolve(credentials.accessKeyId(), token).errorCode());
    assertEquals(S3ErrorCode.InvalidToken, issuer.resolve("ASIAAAAAAAAAAAAAAAAA", token).errorCode());
    assertEquals(S3ErrorCode.InvalidToken, issuer.resolve(credentials.accessKeyId(), null).errorCode());
    assertEquals(S3ErrorCode.InvalidToken, issuer.resolve(credentials.accessKeyId(), "not a token").errorCode());

    // A payload of a longer expiration with the MAC of the original one.
    String[] parts = token.split("\\.");
    String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[0]));
    String extended = payload.replace(Long.toString(credentials.session().expiration().getEpochSecond()),
        Long.toString(credentials.session().expiration().plus(Duration.ofDays(1)).getEpochSecond()));
    String forged = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(extended.getBytes()) + "."
        + parts[1];
    assertNull(issuer.decode(forged));
    assertEquals(S3ErrorCode.InvalidToken, issuer.resolve(credentials.accessKeyId(), forged).errorCode());
  }

}
