package com.robothy.s3.rest.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.robothy.netty.http.HttpRequest;
import com.robothy.s3.core.exception.S3ErrorCode;
import com.robothy.s3.rest.handler.AwsSignatureV4Verifier.VerificationResult;
import com.robothy.s3.rest.netty.RequestBodies;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies requests from the examples of the AWS Signature Version 4 documentation.
 */
class AwsSignatureV4VerifierTest {

  private static final String ACCESS_KEY_ID = "AKIAIOSFODNN7EXAMPLE";

  private static final String SECRET_ACCESS_KEY = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";

  private static final String AMZ_DATE = "20130524T000000Z";

  private static final byte[] JUNK = "junk".getBytes(StandardCharsets.US_ASCII);

  private final AwsSignatureV4Verifier verifier = new AwsSignatureV4Verifier(ACCESS_KEY_ID, SECRET_ACCESS_KEY,
      Clock.fixed(Instant.parse("2013-05-24T00:00:00Z"), ZoneOffset.UTC));

  /**
   * The "PUT Object" example of
   * <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/sig-v4-header-based-auth.html">sig-v4-header-based-auth</a>.
   */
  @Test
  void verifiesThePayloadHashAgainstTheBody() {
    Map<CharSequence, String> headers = putObjectHeaders();

    assertVerified(headers, PUT_OBJECT_PATH, HttpMethod.PUT, PUT_OBJECT_CONTENT, 7, 15);

    byte[] tampered = "Welcome to Amazon S4.".getBytes(StandardCharsets.UTF_8);
    assertRejected(headers, PUT_OBJECT_PATH, HttpMethod.PUT, tampered, 7, 15);
  }

  @Test
  void verifiesTheSignatureBeforeTheBodyIsReceived() {
    Map<CharSequence, String> headers = putObjectHeaders();
    VerificationResult result = verifyHead(headers, PUT_OBJECT_PATH, HttpMethod.PUT);
    assertTrue(result.authenticated(), () -> result.errorCode() + ": " + result.message());

    headers.put("authorization", authorization(PUT_OBJECT_SIGNED_HEADERS, "0".repeat(64)));
    assertEquals(S3ErrorCode.SignatureDoesNotMatch, verifyHead(headers, PUT_OBJECT_PATH, HttpMethod.PUT).errorCode());

    headers.put("authorization", putObjectHeaders().get("authorization").replace(ACCESS_KEY_ID, "UNKNOWNKEY"));
    assertEquals(S3ErrorCode.InvalidAccessKeyId, verifyHead(headers, PUT_OBJECT_PATH, HttpMethod.PUT).errorCode());
  }

  @Test
  void verifiesTheSignatureWithoutPayloadHashOnlyWithTheBody() {
    Map<CharSequence, String> headers = putObjectHeaders();
    headers.remove("x-amz-content-sha256");
    headers.put("authorization", authorization("date;host;x-amz-date;x-amz-storage-class", "0".repeat(64)));

    // The signature covers the hash of the body, so the head only passes the checks of the credential and the time.
    assertTrue(verifyHead(headers, PUT_OBJECT_PATH, HttpMethod.PUT).authenticated());
    assertRejected(headers, PUT_OBJECT_PATH, HttpMethod.PUT, PUT_OBJECT_CONTENT, 7);

    headers.put("authorization", headers.get("authorization").replace(ACCESS_KEY_ID, "UNKNOWNKEY"));
    assertEquals(S3ErrorCode.InvalidAccessKeyId, verifyHead(headers, PUT_OBJECT_PATH, HttpMethod.PUT).errorCode());
  }

  private static final String PUT_OBJECT_PATH = "/test%24file.text";

  private static final String PUT_OBJECT_SIGNED_HEADERS = "date;host;x-amz-content-sha256;x-amz-date;x-amz-storage-class";

  private static final byte[] PUT_OBJECT_CONTENT = "Welcome to Amazon S3.".getBytes(StandardCharsets.UTF_8);

  private static Map<CharSequence, String> putObjectHeaders() {
    Map<CharSequence, String> headers = new HashMap<>();
    headers.put("date", "Fri, 24 May 2013 00:00:00 GMT");
    headers.put("host", "examplebucket.s3.amazonaws.com");
    headers.put("x-amz-date", AMZ_DATE);
    headers.put("x-amz-storage-class", "REDUCED_REDUNDANCY");
    headers.put("x-amz-content-sha256", "44ce7dd67c959e0d3524ffac1771dfbba87d2b6b4b4e99e42034a8b803f8b072");
    headers.put("authorization", authorization(PUT_OBJECT_SIGNED_HEADERS,
        "98ad721746da40c64f1a55b78f14c238d841ea1380cd77a1b5971af0ece108bd"));
    return headers;
  }

  private VerificationResult verifyHead(Map<CharSequence, String> headers, String path, HttpMethod method) {
    return verifier.verifyHead(HttpRequest.builder()
        .method(method)
        .uri(path)
        .httpVersion(HttpVersion.HTTP_1_1)
        .headers(new HashMap<>(headers))
        .path(path)
        .params(new HashMap<>())
        .build());
  }

  /**
   * The example of
   * <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/sigv4-streaming.html">sigv4-streaming</a>,
   * which uploads 66560 bytes in chunks of 64 KB.
   */
  @Test
  void verifiesChunkSignaturesAcrossBufferComponents() {
    Map<CharSequence, String> headers = new HashMap<>();
    headers.put("content-encoding", "aws-chunked");
    headers.put("content-length", "66824");
    headers.put("host", "s3.amazonaws.com");
    headers.put("x-amz-content-sha256", "STREAMING-AWS4-HMAC-SHA256-PAYLOAD");
    headers.put("x-amz-date", AMZ_DATE);
    headers.put("x-amz-decoded-content-length", "66560");
    headers.put("x-amz-storage-class", "REDUCED_REDUNDANCY");
    headers.put("authorization", authorization(
        "content-encoding;content-length;host;x-amz-content-sha256;x-amz-date;x-amz-decoded-content-length;x-amz-storage-class",
        "4f232c4386841ef735655705268965c44a0e4690baa4adea153f7db9fa80a0a9"));

    byte[] encoded = chunkedBody();
    assertEquals(66824, encoded.length);
    String path = "/examplebucket/chunkObject.txt";
    assertTrue(verifyHead(headers, path, HttpMethod.PUT).authenticated(),
        "The chunk signatures are verified once the body is received.");
    // Split the body within chunk headers, within chunk data and between a chunk and its CRLF.
    int[] cuts = {10, 40_000, 88 + 65_537, 88 + 65_538 + 30, encoded.length - 40};
    assertVerified(headers, path, HttpMethod.PUT, encoded, cuts);

    byte[] tamperedData = encoded.clone();
    tamperedData[88 + 65_538 + 86 + 10] = 'b';
    assertRejected(headers, path, HttpMethod.PUT, tamperedData, cuts);

    // A missing CRLF after the final chunk is tolerated, but a body ending within chunk data is not.
    byte[] withoutFinalCrlf = Arrays.copyOf(encoded, encoded.length - 2);
    assertVerified(headers, path, HttpMethod.PUT, withoutFinalCrlf, 10);
    byte[] truncated = Arrays.copyOf(encoded, encoded.length - 100);
    assertRejected(headers, path, HttpMethod.PUT, truncated, 10);
  }

  /**
   * A body larger than 2 GiB is only in a file, which no {@code ByteBuf} holds: its payload hash and its chunk signatures
   * are verified by reading the file. The chunked body is larger than the window the file is read through.
   */
  @Test
  void verifiesThePayloadOfABodyThatIsOnlyInAFile(@TempDir Path directory) throws IOException {
    Map<CharSequence, String> headers = putObjectHeaders();
    assertTrue(verifyFileBody(directory, headers, PUT_OBJECT_PATH, PUT_OBJECT_CONTENT).authenticated());
    assertEquals(S3ErrorCode.SignatureDoesNotMatch, verifyFileBody(directory, headers, PUT_OBJECT_PATH,
        "Welcome to Amazon S4.".getBytes(StandardCharsets.UTF_8)).errorCode());

    Map<CharSequence, String> chunked = chunkedHeaders();
    String path = "/examplebucket/chunkObject.txt";
    byte[] encoded = chunkedBody();
    assertTrue(verifyFileBody(directory, chunked, path, encoded).authenticated());
    byte[] tampered = encoded.clone();
    tampered[88 + 65_538 + 86 + 10] = 'b';
    assertEquals(S3ErrorCode.SignatureDoesNotMatch, verifyFileBody(directory, chunked, path, tampered).errorCode());
    assertEquals(S3ErrorCode.SignatureDoesNotMatch,
        verifyFileBody(directory, chunked, path, Arrays.copyOf(encoded, encoded.length - 100)).errorCode());
  }

  private VerificationResult verifyFileBody(Path directory, Map<CharSequence, String> headers, String path,
                                            byte[] content) throws IOException {
    Path file = Files.write(Files.createTempFile(directory, "body", ".tmp"), content);
    ByteBuf body = RequestBodies.fileBody(file);
    try {
      return verifier.verify(HttpRequest.builder().method(HttpMethod.PUT).uri(path).path(path)
          .httpVersion(HttpVersion.HTTP_1_1).headers(new HashMap<>(headers)).params(new HashMap<>()).body(body)
          .build());
    } finally {
      body.release();
      assertFalse(Files.exists(file), "Releasing the body deletes its file.");
    }
  }

  private static byte[] chunkedBody() {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    writeChunk(out, 65_536, "ad80c730a21e5b8d04586a2213dd63b9a0e99e0e2307b0ade35a65485a288648");
    writeChunk(out, 1_024, "0055627c9e194cb4542bae2aa5492e3c1575bbb81b612b7d234b86a503ef5497");
    writeChunk(out, 0, "b6c6ea8a5354eaf15b3cb7646744f4275b71ea724fed81ceb9323e279d449df9");
    return out.toByteArray();
  }

  private static void writeChunk(ByteArrayOutputStream out, int size, String signature) {
    out.writeBytes((Integer.toHexString(size) + ";chunk-signature=" + signature + "\r\n")
        .getBytes(StandardCharsets.US_ASCII));
    byte[] data = new byte[size];
    Arrays.fill(data, (byte) 'a');
    out.writeBytes(data);
    out.writeBytes("\r\n".getBytes(StandardCharsets.US_ASCII));
  }

  private void assertVerified(Map<CharSequence, String> headers, String path, HttpMethod method,
                              byte[] content, int... cuts) {
    VerificationResult result = verify(headers, path, method, content, cuts);
    assertTrue(result.authenticated(), () -> result.errorCode() + ": " + result.message());
  }

  private void assertRejected(Map<CharSequence, String> headers, String path, HttpMethod method,
                              byte[] content, int... cuts) {
    assertEquals(S3ErrorCode.SignatureDoesNotMatch, verify(headers, path, method, content, cuts).errorCode());
  }

  /**
   * Verify a request whose body is a composite buffer made of {@code content} split at {@code cuts},
   * preceded by junk bytes that have already been read.
   */
  private VerificationResult verify(Map<CharSequence, String> headers, String path, HttpMethod method,
                                    byte[] content, int... cuts) {
    ByteBuf body = compositeBody(content, cuts);
    try {
      HttpRequest request = HttpRequest.builder()
          .method(method)
          .uri(path)
          .httpVersion(HttpVersion.HTTP_1_1)
          .headers(new HashMap<>(headers))
          .body(body)
          .path(path)
          .params(new HashMap<>())
          .build();
      VerificationResult result = verifier.verify(request);
      assertEquals(JUNK.length, body.readerIndex(), "Verification must not consume the body.");
      assertEquals(content.length, body.readableBytes());
      return result;
    } finally {
      body.release();
    }
  }

  private static ByteBuf compositeBody(byte[] content, int... cuts) {
    CompositeByteBuf body = Unpooled.compositeBuffer(Integer.MAX_VALUE);
    body.addComponent(true, Unpooled.wrappedBuffer(JUNK));
    int from = 0;
    for (int cut : cuts) {
      body.addComponent(true, Unpooled.wrappedBuffer(content, from, cut - from));
      from = cut;
    }
    body.addComponent(true, Unpooled.wrappedBuffer(content, from, content.length - from));
    body.readerIndex(JUNK.length);
    return body;
  }

  /**
   * The signer that the tests below sign their requests with, checked against the documented signature first.
   */
  @Test
  void theReferenceSignerProducesTheDocumentedSignature() {
    assertEquals("98ad721746da40c64f1a55b78f14c238d841ea1380cd77a1b5971af0ece108bd",
        sign("PUT", PUT_OBJECT_PATH, putObjectHeaders(), PUT_OBJECT_SIGNED_HEADERS, AMZ_DATE,
            putObjectHeaders().get("x-amz-content-sha256")));
  }

  /**
   * A client that sends the standard {@code Date} header instead of {@code x-amz-date}, in the RFC 1123 format of HTTP
   * or in the basic ISO 8601 format, signs with the time of that header.
   */
  @Test
  void acceptsTheDateHeaderInsteadOfXAmzDate() {
    for (String date : new String[] {"Fri, 24 May 2013 00:00:00 GMT", AMZ_DATE}) {
      Map<CharSequence, String> headers = dateSignedHeaders(date);
      assertVerified(headers, PUT_OBJECT_PATH, HttpMethod.PUT, PUT_OBJECT_CONTENT, 7);
      assertRejected(headers, PUT_OBJECT_PATH, HttpMethod.PUT, "Welcome to Amazon S4.".getBytes(StandardCharsets.UTF_8));
    }
  }

  @Test
  void rejectsADateHeaderThatIsMissingUnsignedOrInvalid() {
    Map<CharSequence, String> unsigned = dateSignedHeaders("Fri, 24 May 2013 00:00:00 GMT");
    unsigned.put("authorization", authorization("host;x-amz-content-sha256;x-amz-storage-class", "0".repeat(64)));
    VerificationResult result = verifyHead(unsigned, PUT_OBJECT_PATH, HttpMethod.PUT);
    assertEquals(S3ErrorCode.AuthorizationHeaderMalformed, result.errorCode());
    assertEquals("The date header must be signed.", result.message());

    Map<CharSequence, String> missing = dateSignedHeaders("Fri, 24 May 2013 00:00:00 GMT");
    missing.remove("date");
    assertEquals("The x-amz-date or Date header is required.",
        verifyHead(missing, PUT_OBJECT_PATH, HttpMethod.PUT).message());

    Map<CharSequence, String> invalid = dateSignedHeaders("yesterday");
    assertEquals(S3ErrorCode.AuthorizationHeaderMalformed,
        verifyHead(invalid, PUT_OBJECT_PATH, HttpMethod.PUT).errorCode());

    Map<CharSequence, String> skewed = dateSignedHeaders("Fri, 24 May 2013 01:00:00 GMT");
    assertEquals(S3ErrorCode.RequestTimeTooSkewed, verifyHead(skewed, PUT_OBJECT_PATH, HttpMethod.PUT).errorCode());
  }

  /**
   * Once the head is verified, only what depends on the body is: the head, e.g. its time or its signature, isn't
   * verified again. An upload that takes longer than the allowed clock skew is accepted, since the time of the request
   * was valid when the request started, like Amazon S3 does.
   */
  @Test
  void verifiesOnlyTheBodyOfARequestWhoseHeadIsVerified() {
    MutableClock clock = new MutableClock(Instant.parse("2013-05-24T00:00:00Z"));
    AwsSignatureV4Verifier verifier = new AwsSignatureV4Verifier(ACCESS_KEY_ID, SECRET_ACCESS_KEY, clock);
    Map<CharSequence, String> headers = putObjectHeaders();
    AwsSignatureV4Verifier.HeadVerification head = verifier.verifyHeadForBody(request(headers, null));
    assertTrue(head.result().authenticated());

    clock.instant = Instant.parse("2013-05-24T00:30:00Z");
    assertEquals(S3ErrorCode.RequestTimeTooSkewed,
        verifier.verify(request(headers, Unpooled.wrappedBuffer(PUT_OBJECT_CONTENT))).errorCode(),
        "Without the verified head, the whole request is verified again.");
    assertTrue(verifier.verifyBody(request(headers, Unpooled.wrappedBuffer(PUT_OBJECT_CONTENT)),
        head.verifiedHead()).authenticated());
    assertEquals(S3ErrorCode.SignatureDoesNotMatch, verifier.verifyBody(
        request(headers, Unpooled.wrappedBuffer("Welcome to Amazon S4.".getBytes(StandardCharsets.UTF_8))),
        head.verifiedHead()).errorCode());
  }

  @Test
  void verifiesTheChunkSignaturesWithTheVerifiedHead() {
    Map<CharSequence, String> headers = chunkedHeaders();
    String path = "/examplebucket/chunkObject.txt";
    AwsSignatureV4Verifier.HeadVerification head = verifier.verifyHeadForBody(HttpRequest.builder()
        .method(HttpMethod.PUT).uri(path).path(path).httpVersion(HttpVersion.HTTP_1_1)
        .headers(new HashMap<>(headers)).params(new HashMap<>()).build());
    assertTrue(head.result().authenticated());

    byte[] encoded = chunkedBody();
    assertTrue(verifier.verifyBody(chunkedRequest(headers, path, encoded), head.verifiedHead()).authenticated());
    byte[] tampered = encoded.clone();
    tampered[88 + 65_538 + 86 + 10] = 'b';
    assertEquals(S3ErrorCode.SignatureDoesNotMatch,
        verifier.verifyBody(chunkedRequest(headers, path, tampered), head.verifiedHead()).errorCode());
  }

  /**
   * Without {@code x-amz-content-sha256}, the signature of the head covers the hash of the body, so the body phase
   * verifies the whole request.
   */
  @Test
  void verifiesTheWholeRequestWhenTheHeadSignatureDependsOnTheBody() {
    Map<CharSequence, String> headers = putObjectHeaders();
    headers.remove("x-amz-content-sha256");
    headers.put("authorization", authorization("date;host;x-amz-date;x-amz-storage-class", "0".repeat(64)));
    AwsSignatureV4Verifier.HeadVerification head = verifier.verifyHeadForBody(request(headers, null));
    assertTrue(head.result().authenticated());
    assertEquals(S3ErrorCode.SignatureDoesNotMatch, verifier.verifyBody(
        request(headers, Unpooled.wrappedBuffer(PUT_OBJECT_CONTENT)), head.verifiedHead()).errorCode());
  }

  private static Map<CharSequence, String> dateSignedHeaders(String date) {
    Map<CharSequence, String> headers = putObjectHeaders();
    headers.remove("x-amz-date");
    headers.put("date", date);
    String signedHeaders = "date;host;x-amz-content-sha256;x-amz-storage-class";
    headers.put("authorization", authorization(signedHeaders, sign("PUT", PUT_OBJECT_PATH, headers, signedHeaders,
        AMZ_DATE, headers.get("x-amz-content-sha256"))));
    return headers;
  }

  private static Map<CharSequence, String> chunkedHeaders() {
    Map<CharSequence, String> headers = new HashMap<>();
    headers.put("content-encoding", "aws-chunked");
    headers.put("content-length", "66824");
    headers.put("host", "s3.amazonaws.com");
    headers.put("x-amz-content-sha256", "STREAMING-AWS4-HMAC-SHA256-PAYLOAD");
    headers.put("x-amz-date", AMZ_DATE);
    headers.put("x-amz-decoded-content-length", "66560");
    headers.put("x-amz-storage-class", "REDUCED_REDUNDANCY");
    headers.put("authorization", authorization(
        "content-encoding;content-length;host;x-amz-content-sha256;x-amz-date;x-amz-decoded-content-length;x-amz-storage-class",
        "4f232c4386841ef735655705268965c44a0e4690baa4adea153f7db9fa80a0a9"));
    return headers;
  }

  private static HttpRequest chunkedRequest(Map<CharSequence, String> headers, String path, byte[] body) {
    return HttpRequest.builder().method(HttpMethod.PUT).uri(path).path(path).httpVersion(HttpVersion.HTTP_1_1)
        .headers(new HashMap<>(headers)).params(new HashMap<>()).body(Unpooled.wrappedBuffer(body)).build();
  }

  private static HttpRequest request(Map<CharSequence, String> headers, ByteBuf body) {
    return HttpRequest.builder().method(HttpMethod.PUT).uri(PUT_OBJECT_PATH).path(PUT_OBJECT_PATH)
        .httpVersion(HttpVersion.HTTP_1_1).headers(new HashMap<>(headers)).params(new HashMap<>()).body(body).build();
  }

  /**
   * Sign a request without a query, following the steps of the AWS Signature Version 4 documentation.
   */
  private static String sign(String method, String path, Map<CharSequence, String> headers, String signedHeaders,
                             String amzDate, String payloadHash) {
    StringBuilder canonicalHeaders = new StringBuilder();
    for (String name : signedHeaders.split(";")) {
      canonicalHeaders.append(name).append(':').append(headers.get(name).trim()).append('\n');
    }
    String canonicalRequest = method + "\n" + path + "\n\n" + canonicalHeaders + "\n" + signedHeaders + "\n"
        + payloadHash;
    String scope = amzDate.substring(0, 8) + "/us-east-1/s3/aws4_request";
    String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n"
        + HexFormat.of().formatHex(sha256(canonicalRequest));
    byte[] key = hmac(("AWS4" + SECRET_ACCESS_KEY).getBytes(StandardCharsets.UTF_8), amzDate.substring(0, 8));
    key = hmac(key, "us-east-1");
    key = hmac(key, "s3");
    key = hmac(key, "aws4_request");
    return HexFormat.of().formatHex(hmac(key, stringToSign));
  }

  private static byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static byte[] hmac(byte[] key, String value) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new IllegalStateException(e);
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
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }

  private static String authorization(String signedHeaders, String signature) {
    return "AWS4-HMAC-SHA256 Credential=" + ACCESS_KEY_ID + "/20130524/us-east-1/s3/aws4_request,"
        + "SignedHeaders=" + signedHeaders + ",Signature=" + signature;
  }

}
