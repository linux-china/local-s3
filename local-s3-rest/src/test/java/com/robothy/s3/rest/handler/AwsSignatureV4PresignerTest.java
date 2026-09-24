package com.robothy.s3.rest.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.robothy.netty.http.HttpRequest;
import com.robothy.s3.core.exception.S3ErrorCode;
import com.robothy.s3.rest.handler.AwsSignatureV4Verifier.VerificationResult;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Verifies the URLs of the presigner with {@linkplain AwsSignatureV4Verifier}, the verifier that answers them, so
 * that the two agree on the canonical request of a URL.
 */
class AwsSignatureV4PresignerTest {

  private static final String ACCESS_KEY_ID = "AKIAIOSFODNN7EXAMPLE";

  private static final String SECRET_ACCESS_KEY = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";

  private static final String ENDPOINT = "http://127.0.0.1:19090";

  private static final Instant NOW = Instant.parse("2013-05-24T00:00:00Z");

  private final AwsSignatureV4Presigner presigner = presigner(NOW);

  /**
   * Every key is signed and answered as it is: the ones with characters that the path encodes, with a slash that it
   * keeps, and with a percent sign that is encoded rather than taken for an escape.
   */
  @ParameterizedTest
  @ValueSource(strings = {"a.txt", "reports/2026/q1 summary.pdf", "a+b&c=d?e#f", "100%25.txt", "键/值.txt",
      "~unreserved-._", "double//slash.txt"})
  void signsUrlsThatTheVerifierAccepts(String key) {
    String url = presigner.presign(ENDPOINT, "GET", "my-bucket", key, Duration.ofMinutes(15));

    assertTrue(url.startsWith(ENDPOINT + "/my-bucket/"), url);
    assertEquals("/my-bucket/" + key, new QueryStringDecoder(URI.create(url).getRawPath()).path(),
        "The service decodes the path back into the key.");
    assertAccepted(url, HttpMethod.GET, NOW);
  }

  @Test
  void signsTheMethodOfTheUrl() {
    String url = presigner.presign(ENDPOINT, "put", "my-bucket", "a.txt", Duration.ofMinutes(15));

    assertTrue(url.contains("X-Amz-SignedHeaders=host"), url);
    assertAccepted(url, HttpMethod.PUT, NOW);
    assertEquals(S3ErrorCode.SignatureDoesNotMatch, verify(url, HttpMethod.GET, NOW).errorCode(),
        "A URL signed for PUT doesn't read the object.");
  }

  @Test
  void signsUrlsThatExpire() {
    String url = presigner.presign(ENDPOINT, "GET", "my-bucket", "a.txt", Duration.ofMinutes(15));

    assertTrue(url.contains("X-Amz-Expires=900"), url);
    assertAccepted(url, HttpMethod.GET, NOW.plus(Duration.ofMinutes(14)));
    assertEquals(S3ErrorCode.AccessDenied, verify(url, HttpMethod.GET, NOW.plus(Duration.ofMinutes(16))).errorCode());
  }

  /**
   * A URL that is valid for no time, for a negative one or for longer than a week is refused like an expired one,
   * with {@code 403 AccessDenied}.
   */
  @ParameterizedTest
  @ValueSource(strings = {"0", "-7", "604801"})
  void refusesAnExpiryOutOfRange(String expires) {
    String url = presigner.presign(ENDPOINT, "GET", "my-bucket", "a.txt", Duration.ofMinutes(15))
        .replace("X-Amz-Expires=900", "X-Amz-Expires=" + expires);

    assertEquals(S3ErrorCode.AccessDenied, verify(url, HttpMethod.GET, NOW).errorCode());
  }

  /**
   * A client leaves the default port of the scheme out of the {@code host} header, so a URL that names it is signed
   * without it.
   */
  @Test
  void signsTheHostHeaderThatTheClientSends() {
    assertAccepted(presigner.presign("http://localhost:80", "GET", "my-bucket", "a.txt", Duration.ofMinutes(15)),
        HttpMethod.GET, NOW);
    assertAccepted(presigner.presign("https://localhost:443", "GET", "my-bucket", "a.txt", Duration.ofMinutes(15)),
        HttpMethod.GET, NOW);
    assertAccepted(presigner.presign("http://[::1]:19090", "GET", "my-bucket", "a.txt", Duration.ofMinutes(15)),
        HttpMethod.GET, NOW);
  }

  /**
   * A service that has no credentials answers unsigned requests, so its presigner hands out the plain URL of an
   * object, after the same checks.
   */
  @Test
  void presignsPlainUrlsWithoutCredentials() {
    AwsSignatureV4Presigner unsigned = AwsSignatureV4Presigner.unsigned();
    assertEquals("http://127.0.0.1:19090/my-bucket/a%20b.txt",
        unsigned.presign(ENDPOINT, "GET", "my-bucket", "a b.txt", Duration.ofMinutes(15)));
    assertThrows(IllegalArgumentException.class,
        () -> unsigned.presign(ENDPOINT, "GET", "my-bucket", "a.txt", Duration.ofDays(8)));
    assertThrows(IllegalArgumentException.class,
        () -> unsigned.presign(ENDPOINT, "GET", " ", "a.txt", Duration.ofMinutes(15)));
  }

  @Test
  void trimsTheEndpointDownToItsSchemeAndAuthority() {
    assertEquals("http://127.0.0.1:19090/my-bucket/a.txt", AwsSignatureV4Presigner.unsigned()
        .presign(" HTTP://127.0.0.1:19090/ ", "GET", "my-bucket", "a.txt", Duration.ofMinutes(15)));
  }

  @Test
  void rejectsArgumentsThatCannotBeSigned() {
    assertThrows(IllegalArgumentException.class,
        () -> presigner.presign(ENDPOINT, "GET", "my-bucket", "a.txt", Duration.ZERO));
    assertThrows(IllegalArgumentException.class,
        () -> presigner.presign(ENDPOINT, "GET", "my-bucket", "a.txt", Duration.ofDays(8)));
    assertThrows(IllegalArgumentException.class,
        () -> presigner.presign(ENDPOINT, " ", "my-bucket", "a.txt", Duration.ofMinutes(15)));
    assertThrows(IllegalArgumentException.class,
        () -> presigner.presign(ENDPOINT, "GET", "", "a.txt", Duration.ofMinutes(15)));
    assertThrows(IllegalArgumentException.class,
        () -> presigner.presign(ENDPOINT, "GET", "my-bucket", "", Duration.ofMinutes(15)));
    assertThrows(IllegalArgumentException.class,
        () -> presigner.presign("127.0.0.1:19090", "GET", "my-bucket", "a.txt", Duration.ofMinutes(15)));
    assertThrows(NullPointerException.class,
        () -> presigner.presign(ENDPOINT, "GET", "my-bucket", "a.txt", null));
    assertThrows(IllegalArgumentException.class, () -> new AwsSignatureV4Presigner("", SECRET_ACCESS_KEY));
    assertThrows(IllegalArgumentException.class, () -> new AwsSignatureV4Presigner(ACCESS_KEY_ID, null));
  }

  private static AwsSignatureV4Presigner presigner(Instant now) {
    return new AwsSignatureV4Presigner(ACCESS_KEY_ID, SECRET_ACCESS_KEY,
        AwsSignatureV4Presigner.DEFAULT_REGION, Clock.fixed(now, ZoneOffset.UTC));
  }

  private static void assertAccepted(String url, HttpMethod method, Instant at) {
    VerificationResult result = verify(url, method, at);
    assertTrue(result.authenticated(), () -> url + " -> " + result.errorCode() + ": " + result.message());
  }

  /**
   * Verify a presigned URL like the service does with the request that a client sends for it: the request target as
   * the URL spells it, and the {@code host} header of its authority.
   */
  private static VerificationResult verify(String url, HttpMethod method, Instant at) {
    URI uri = URI.create(url);
    String target = uri.getRawPath() + '?' + uri.getRawQuery();
    QueryStringDecoder decoder = new QueryStringDecoder(target);
    Map<CharSequence, String> headers = new HashMap<>();
    headers.put("host", hostHeader(uri));
    return new AwsSignatureV4Verifier(ACCESS_KEY_ID, SECRET_ACCESS_KEY, Clock.fixed(at, ZoneOffset.UTC))
        .verifyHead(HttpRequest.builder()
            .method(method)
            .uri(target)
            .httpVersion(HttpVersion.HTTP_1_1)
            .headers(headers)
            .path(decoder.path())
            .params(new HashMap<>(decoder.parameters()))
            .build());
  }

  private static String hostHeader(URI uri) {
    String authority = uri.getRawAuthority();
    String defaultPort = "https".equals(uri.getScheme()) ? ":443" : ":80";
    return authority.endsWith(defaultPort)
        ? authority.substring(0, authority.length() - defaultPort.length())
        : authority;
  }

}
