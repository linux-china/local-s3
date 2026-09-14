package com.robothy.s3.rest.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.robothy.netty.http.HttpRequest;
import com.robothy.netty.http.HttpRequestHandler;
import com.robothy.s3.rest.netty.OperationHandler;
import com.robothy.netty.router.Route;
import com.robothy.s3.rest.netty.LocalS3HttpRequestDecoder;
import com.robothy.s3.rest.utils.VirtualHostParser;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The head of a request with a body is verified by the decoder before the body is received, and the router verifies
 * only the body of the complete request, rather than the whole request a second time.
 */
class HeadVerificationReuseTest {

  private static final String ACCESS_KEY_ID = "AKIAIOSFODNN7EXAMPLE";

  private static final String SECRET_ACCESS_KEY = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";

  private static final byte[] CONTENT = "Welcome to Amazon S3.".getBytes(StandardCharsets.UTF_8);

  /**
   * The "PUT Object" example of the AWS Signature Version 4 documentation.
   */
  private static final Map<String, String> HEADERS = Map.of(
      "Date", "Fri, 24 May 2013 00:00:00 GMT",
      "Host", "examplebucket.s3.amazonaws.com",
      "x-amz-date", "20130524T000000Z",
      "x-amz-storage-class", "REDUCED_REDUNDANCY",
      "x-amz-content-sha256", "44ce7dd67c959e0d3524ffac1771dfbba87d2b6b4b4e99e42034a8b803f8b072",
      "Content-Length", String.valueOf(CONTENT.length),
      "Authorization", "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request,"
          + "SignedHeaders=date;host;x-amz-content-sha256;x-amz-date;x-amz-storage-class,"
          + "Signature=98ad721746da40c64f1a55b78f14c238d841ea1380cd77a1b5971af0ece108bd");

  @Test
  void theRouterVerifiesOnlyTheBodyOfARequestWhoseHeadTheDecoderVerified() {
    MutableClock clock = new MutableClock(Instant.parse("2013-05-24T00:00:00Z"));
    HttpRequestHandler handler = mock(HttpRequestHandler.class);
    LocalS3Router router = router(clock, handler);

    HttpRequest request = decode(router, CONTENT);
    // The upload took longer than the allowed clock skew; the time was valid when the request started.
    clock.instant = Instant.parse("2013-05-24T00:30:00Z");

    assertSame(handler, ((OperationHandler) router.match(request)).handler());
  }

  @Test
  void theRouterStillVerifiesTheBody() {
    MutableClock clock = new MutableClock(Instant.parse("2013-05-24T00:00:00Z"));
    LocalS3Router router = router(clock, mock(HttpRequestHandler.class));

    HttpRequest tampered = decode(router, "Welcome to Amazon S4.".getBytes(StandardCharsets.UTF_8));

    OperationHandler rejected = (OperationHandler) router.match(tampered);
    assertInstanceOf(AuthenticationFailureHandler.class, rejected.handler());
    assertEquals(LocalS3Router.AUTHENTICATION_FAILURE_OPERATION, rejected.operation());
  }

  /**
   * A request that no decoder verified the head of, e.g. one that a test hands to the router directly, is verified
   * as a whole.
   */
  @Test
  void aRequestWithoutAVerifiedHeadIsVerifiedAsAWhole() {
    MutableClock clock = new MutableClock(Instant.parse("2013-05-24T00:00:00Z"));
    LocalS3Router verifying = router(clock, mock(HttpRequestHandler.class));
    HttpRequest request = decode(router(clock, mock(HttpRequestHandler.class)), CONTENT);
    clock.instant = Instant.parse("2013-05-24T00:30:00Z");

    assertInstanceOf(AuthenticationFailureHandler.class, ((OperationHandler) verifying.match(request)).handler());
  }

  private static LocalS3Router router(Clock clock, HttpRequestHandler handler) {
    LocalS3Router router = new LocalS3Router(new AwsSignatureV4Verifier(ACCESS_KEY_ID, SECRET_ACCESS_KEY, clock),
        new VirtualHostParser(Set.of()));
    // The host of the example addresses the bucket, so the path is the key of an object.
    router.route("PutObject", Route.builder().method(HttpMethod.PUT).path(LocalS3Router.BUCKET_KEY_PATH)
        .handler(handler).build());
    return router;
  }

  private static HttpRequest decode(LocalS3Router router, byte[] body) {
    EmbeddedChannel channel = new EmbeddedChannel(
        new LocalS3HttpRequestDecoder(1024 * 1024, Long.MAX_VALUE, new XmlMapper(), router));
    try {
      DefaultHttpRequest head = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.PUT, "/test%24file.text");
      HEADERS.forEach(head.headers()::set);
      assertTrue(channel.writeInbound(head, new DefaultLastHttpContent(Unpooled.wrappedBuffer(body))));
      return channel.readInbound();
    } finally {
      channel.finish();
    }
  }

  private static final class MutableClock extends Clock {

    private Instant instant;

    MutableClock(Instant instant) {
      this.instant = instant;
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }

}
