package com.robothy.s3.rest.handler;

import com.robothy.netty.http.HttpRequest;
import com.robothy.s3.core.exception.S3ErrorCode;
import com.robothy.s3.rest.constants.AmzHeaderNames;
import com.robothy.s3.rest.constants.AmzHeaderValues;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpHeaderNames;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Verifies AWS Signature Version 4 requests for a single access key pair, and for the temporary credentials that the
 * STS endpoint of LocalS3 issued with it, see {@linkplain SessionCredentialIssuer}. A request signed with temporary
 * credentials carries their session token in {@code x-amz-security-token}, in {@code X-Amz-Security-Token} for a
 * presigned URL, or in the {@code x-amz-security-token} field of a form upload, and is signed with the secret access key
 * that is derived from the token.
 *
 * <p>An STS request, see {@linkplain StsController#isStsRequest}, is signed for the {@code sts} service, a KMS
 * request, see {@linkplain KmsController#isKmsRequest}, for the {@code kms} service, and any other request for
 * {@code s3} or {@code s3vectors}.
 */
final class AwsSignatureV4Verifier {

  static final String ALGORITHM = "AWS4-HMAC-SHA256";
  private static final String CHUNK_ALGORITHM = "AWS4-HMAC-SHA256-PAYLOAD";
  private static final String TRAILER_ALGORITHM = "AWS4-HMAC-SHA256-TRAILER";
  private static final String TERMINATOR = "aws4_request";
  private static final String UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD";
  private static final String EMPTY_SHA256 = sha256Hex(new byte[0]);
  private static final Duration ALLOWED_CLOCK_SKEW = Duration.ofMinutes(15);
  static final int MAX_PRESIGNED_EXPIRY_SECONDS = 7 * 24 * 60 * 60;
  static final DateTimeFormatter AMZ_DATE_FORMAT =
      DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(java.time.ZoneOffset.UTC);
  private static final Pattern WHITESPACE = Pattern.compile("[\\t\\n\\r ]+");
  private static final Pattern HEX_SHA256 = Pattern.compile("[0-9a-fA-F]{64}");
  /**
   * The longest line of chunk metadata that is read, e.g. {@code <hex length>;chunk-signature=<64 hex digits>} or a
   * trailing header, so that a body that isn't {@code aws-chunked} isn't read into a string whole.
   */
  private static final int MAX_CHUNK_HEADER_LENGTH = 8 * 1024;

  private final String accessKeyId;
  private final String secretAccessKey;
  private final SessionCredentialIssuer sessionCredentialIssuer;
  private final Clock clock;

  AwsSignatureV4Verifier(String accessKeyId, String secretAccessKey) {
    this(accessKeyId, secretAccessKey, Clock.systemUTC());
  }

  AwsSignatureV4Verifier(String accessKeyId, String secretAccessKey, Clock clock) {
    this(accessKeyId, secretAccessKey, new SessionCredentialIssuer(secretAccessKey, clock), clock);
  }

  /**
   * Create a verifier.
   *
   * @param accessKeyId the access key ID of LocalS3.
   * @param secretAccessKey the secret access key of LocalS3.
   * @param sessionCredentialIssuer resolves the temporary credentials that a request is signed with.
   * @param clock the clock that the time of a request is checked with.
   */
  AwsSignatureV4Verifier(String accessKeyId, String secretAccessKey, SessionCredentialIssuer sessionCredentialIssuer,
                         Clock clock) {
    if (accessKeyId == null || accessKeyId.isBlank()) {
      throw new IllegalArgumentException("accessKeyId must not be blank.");
    }
    if (secretAccessKey == null || secretAccessKey.isBlank()) {
      throw new IllegalArgumentException("secretAccessKey must not be blank.");
    }
    this.accessKeyId = accessKeyId;
    this.secretAccessKey = secretAccessKey;
    this.sessionCredentialIssuer = Objects.requireNonNull(sessionCredentialIssuer);
    this.clock = Objects.requireNonNull(clock);
  }

  /**
   * Verify a request, including its body.
   */
  VerificationResult verify(HttpRequest request) {
    return verifyBody(request, null);
  }

  /**
   * Verify the head of a request before its body is received, so that a request with an invalid signature is
   * rejected before it uploads its body. Everything but the body is verified: the signature is calculated with the
   * payload hash of {@code x-amz-content-sha256}, which {@linkplain #verifyBody} checks against the body later, as well
   * as the chunk signatures. Without {@code x-amz-content-sha256}, the signature covers the hash of the body, so
   * only the credential, the request time and the signed headers are verified.
   *
   * @param head the request; its body is ignored.
   */
  VerificationResult verifyHead(HttpRequest head) {
    return verifyHeadForBody(head).result();
  }

  /**
   * Verify the head of a request like {@linkplain #verifyHead}, and keep what verifying its body needs, so that
   * {@linkplain #verifyBody} doesn't verify the head a second time once the body is received.
   *
   * @param head the request; its body is ignored.
   * @return the result, and the verified head if the head is accepted.
   */
  HeadVerification verifyHeadForBody(HttpRequest head) {
    return verifyRequest(head, false);
  }

  /**
   * Verify a request whose body is received. With the {@linkplain VerifiedHead head} that
   * {@linkplain #verifyHeadForBody} verified, only what depends on the body is verified: the payload hash and the chunk
   * signatures. Without it, or if the signature of the head couldn't be verified without the body, the whole request is.
   *
   * @param request the request, with its body.
   * @param verifiedHead the verified head of the request; {@code null} to verify the whole request.
   * @return the result.
   */
  VerificationResult verifyBody(HttpRequest request, VerifiedHead verifiedHead) {
    VerifiedHead head = verifiedHead;
    if (head == null || !head.signatureVerified()) {
      HeadVerification verification = verifyRequest(request, true);
      if (!verification.result().authenticated()) {
        return verification.result();
      }
      head = verification.verifiedHead();
    }
    return head.complete() ? VerificationResult.success() : verifyPayload(request.getBody(), head);
  }

  private HeadVerification verifyRequest(HttpRequest request, boolean bodyReceived) {
    try {
      Optional<String> authorization = request.header(HttpHeaderNames.AUTHORIZATION.toString());
      if (authorization.isPresent()) {
        return verifyAuthorizationHeader(request, authorization.get(), bodyReceived);
      }

      RawRequestTarget target = RawRequestTarget.parse(request.getUri(), request.getPath());
      List<QueryParameter> queryParameters = parseQuery(target.rawQuery());
      if (queryParameter(queryParameters, "X-Amz-Algorithm").isPresent()) {
        // The signature of a presigned URL doesn't cover the body, so the head is all there is to verify.
        VerificationResult result = verifyPresignedUrl(request, target, queryParameters);
        return new HeadVerification(result, result.authenticated() ? VerifiedHead.COMPLETE : null);
      }

      return HeadVerification.failed(VerificationResult.failure(S3ErrorCode.AccessDenied,
          "Request must include either a valid Authorization header or SigV4 query parameters."));
    } catch (IllegalArgumentException e) {
      return HeadVerification.failed(VerificationResult.failure(S3ErrorCode.AuthorizationHeaderMalformed,
          e.getMessage()));
    }
  }

  /**
   * Verify the signature of the Authorization header of a request. Before the body is received, a request without
   * {@code x-amz-content-sha256} can't have its signature verified, which covers the hash of the body.
   */
  private HeadVerification verifyAuthorizationHeader(HttpRequest request, String authorization,
      boolean bodyReceived) {
    ParsedAuthorization parsed = parseAuthorization(authorization);
    CredentialScope scope = parseCredential(parsed.credential());
    Map<String, String> headers = normalizedHeaders(request);
    Credential credential = validateCredential(scope, headers.get(AmzHeaderNames.X_AMZ_SECURITY_TOKEN),
        signedServices(request));
    if (!credential.result().authenticated()) {
      return HeadVerification.failed(credential.result());
    }

    RequestTime time = requestTime(headers);
    if (time == null) {
      return HeadVerification.failed(malformed("The x-amz-date or Date header is required."));
    }

    VerificationResult timeResult = validateRequestTime(time.amzDate(), scope.date(), null);
    if (!timeResult.authenticated()) {
      return HeadVerification.failed(timeResult);
    }

    String signedHeaders = normalizeSignedHeaders(parsed.signedHeaders());
    VerificationResult signedHeaderResult = validateSignedHeaders(signedHeaders, headers);
    if (!signedHeaderResult.authenticated()) {
      return HeadVerification.failed(signedHeaderResult);
    }
    if (!Arrays.asList(signedHeaders.split(";")).contains(time.header())) {
      return HeadVerification.failed(malformed("The " + time.header() + " header must be signed."));
    }

    String payloadHash = headers.get(AmzHeaderNames.X_AMZ_CONTENT_SHA256);
    boolean payloadHashOfBody = false;
    if (payloadHash == null) {
      if (!bodyReceived) {
        // Without x-amz-content-sha256, the signature covers the hash of the body, which isn't received yet.
        return new HeadVerification(VerificationResult.success(), VerifiedHead.SIGNATURE_UNVERIFIED);
      }
      // Without x-amz-content-sha256, the signature covers the hash of the body as received.
      try (PayloadBytes bytes = PayloadBytes.of(request.getBody())) {
        payloadHash = sha256Hex(bytes);
      }
      payloadHashOfBody = true;
    } else if (!isStreamingOrUnsigned(payloadHash) && !HEX_SHA256.matcher(payloadHash).matches()) {
      return HeadVerification.failed(malformed("x-amz-content-sha256 is invalid."));
    }

    RawRequestTarget target = RawRequestTarget.parse(request.getUri(), request.getPath());
    String canonicalRequest = canonicalRequest(request, target, scope, parseQuery(target.rawQuery()),
        headers, signedHeaders, payloadHash, false);
    byte[] signingKey = signingKey(credential.secretAccessKey(), scope);
    String expectedSignature = signature(signingKey,
        stringToSign(time.amzDate(), scope.value(), canonicalRequest));
    if (!secureEquals(expectedSignature, parsed.signature())) {
      return HeadVerification.failed(signatureMismatch());
    }
    return new HeadVerification(VerificationResult.success(), new VerifiedHead(false, true, signingKey,
        time.amzDate(), scope.value(), parsed.signature(), payloadHash, payloadHashOfBody,
        headers.get("x-amz-trailer")));
  }

  /**
   * Verify what depends on the body of a request whose head signature is verified: that the body has the hash of
   * {@code x-amz-content-sha256}, or that its chunks have the signatures they carry.
   */
  private static VerificationResult verifyPayload(ByteBuf body, VerifiedHead head) {
    String payloadHash = head.payloadHash();
    boolean hashOfBody = !head.payloadHashOfBody() && HEX_SHA256.matcher(payloadHash).matches();
    boolean chunked = AmzHeaderValues.STREAMING_AWS4_HMAC_SHA_256_PAYLOAD.equals(payloadHash);
    boolean chunkedWithTrailer = AmzHeaderValues.STREAMING_AWS4_HMAC_SHA256_PAYLOAD_TRAILER.equals(payloadHash);
    if (!hashOfBody && !chunked && !chunkedWithTrailer) {
      return VerificationResult.success();
    }
    try (PayloadBytes bytes = PayloadBytes.of(body)) {
      if (hashOfBody) {
        return secureEquals(payloadHash, sha256Hex(bytes)) ? VerificationResult.success() : signatureMismatch();
      }
      boolean verified = verifyChunkSignatures(bytes, head.signingKey(), head.amzDate(), head.scope(),
          head.seedSignature(), chunkedWithTrailer, chunkedWithTrailer ? head.trailerHeaderNames() : null);
      return verified ? VerificationResult.success() : signatureMismatch();
    }
  }

  /**
   * The time of a request, which {@code x-amz-date} carries in the basic ISO 8601 format of the string to sign, or,
   * for the clients that don't send {@code x-amz-date}, the standard {@code Date} header, in the RFC 1123 format of
   * HTTP or in the basic ISO 8601 format. {@code x-amz-date} takes precedence, like it does for Amazon S3.
   *
   * @return the time, and the header it was read from; {@code null} if the request has neither header.
   * @throws IllegalArgumentException if the {@code Date} header has neither format.
   */
  private static RequestTime requestTime(Map<String, String> headers) {
    String amzDate = headers.get("x-amz-date");
    if (amzDate != null) {
      return new RequestTime(amzDate, "x-amz-date");
    }
    String date = headers.get("date");
    if (date == null) {
      return null;
    }
    try {
      AMZ_DATE_FORMAT.parse(date);
      return new RequestTime(date, "date");
    } catch (DateTimeParseException notBasic) {
      try {
        return new RequestTime(AMZ_DATE_FORMAT.format(DateTimeFormatter.RFC_1123_DATE_TIME.parse(date, Instant::from)),
            "date");
      } catch (DateTimeParseException e) {
        throw new IllegalArgumentException("The Date header is neither an RFC 1123 date nor an ISO-8601 basic timestamp.");
      }
    }
  }

  private VerificationResult verifyPresignedUrl(HttpRequest request, RawRequestTarget target,
      List<QueryParameter> queryParameters) {
    String algorithm = requiredQueryParameter(queryParameters, "X-Amz-Algorithm");
    if (!ALGORITHM.equals(algorithm)) {
      return malformed("Unsupported signing algorithm: " + algorithm);
    }

    CredentialScope scope = parseCredential(requiredQueryParameter(queryParameters, "X-Amz-Credential"));
    Credential credential = validateCredential(scope,
        queryParameter(queryParameters, "X-Amz-Security-Token").orElse(null), signedServices(request));
    if (!credential.result().authenticated()) {
      return credential.result();
    }

    String amzDate = requiredQueryParameter(queryParameters, "X-Amz-Date");
    int expires;
    try {
      expires = Integer.parseInt(requiredQueryParameter(queryParameters, "X-Amz-Expires"));
    } catch (NumberFormatException e) {
      return malformed("X-Amz-Expires must be an integer.");
    }
    if (expires < 1 || expires > MAX_PRESIGNED_EXPIRY_SECONDS) {
      return malformed("X-Amz-Expires must be between 1 and 604800 seconds.");
    }

    VerificationResult timeResult = validateRequestTime(amzDate, scope.date(), Duration.ofSeconds(expires));
    if (!timeResult.authenticated()) {
      return timeResult;
    }

    Map<String, String> headers = normalizedHeaders(request);
    String signedHeaders = normalizeSignedHeaders(
        requiredQueryParameter(queryParameters, "X-Amz-SignedHeaders"));
    VerificationResult signedHeaderResult = validateSignedHeaders(signedHeaders, headers);
    if (!signedHeaderResult.authenticated()) {
      return signedHeaderResult;
    }

    String suppliedSignature = requiredQueryParameter(queryParameters, "X-Amz-Signature");
    String payloadHash = headers.getOrDefault(AmzHeaderNames.X_AMZ_CONTENT_SHA256, UNSIGNED_PAYLOAD);
    String canonicalRequest = canonicalRequest(request, target, scope, queryParameters, headers,
        signedHeaders, payloadHash, true);
    String expectedSignature = signature(signingKey(credential.secretAccessKey(), scope),
        stringToSign(amzDate, scope.value(), canonicalRequest));
    return secureEquals(expectedSignature, suppliedSignature)
        ? VerificationResult.success()
        : signatureMismatch();
  }

  /**
   * The services that the credential scope of a request may name: the endpoint that answers the request is the one
   * that the request is signed for, so a request meant for another service can't be replayed against LocalS3.
   */
  private static Set<String> signedServices(HttpRequest request) {
    if (StsController.isStsRequest(request)) {
      return Set.of("sts");
    }
    if (KmsController.isKmsRequest(request)) {
      return Set.of("kms");
    }
    return Set.of("s3", "s3vectors");
  }

  private Credential validateCredential(CredentialScope scope, String sessionToken, Set<String> services) {
    Credential credential = resolveCredential(scope.accessKeyId(), sessionToken);
    if (!credential.result().authenticated()) {
      return credential;
    }
    if (!services.contains(scope.service())) {
      return Credential.failed(malformed("The credential scope service must be "
          + String.join(" or ", new TreeSet<>(services)) + "."));
    }
    return credential;
  }

  /**
   * The secret access key that a request of an access key ID is signed with: the one of LocalS3 for its access key ID,
   * or the one that is derived from the session token of temporary credentials.
   *
   * @param requestAccessKeyId the access key ID of the request.
   * @param sessionToken the session token of the request; {@code null} if it carries none.
   * @return the secret access key, or why the credentials are rejected.
   */
  private Credential resolveCredential(String requestAccessKeyId, String sessionToken) {
    if (sessionToken == null || sessionToken.isEmpty()) {
      return accessKeyId.equals(requestAccessKeyId)
          ? new Credential(VerificationResult.success(), secretAccessKey)
          : Credential.failed(VerificationResult.failure(S3ErrorCode.InvalidAccessKeyId,
              "The AWS access key ID you provided does not exist in our records."));
    }
    // The long-term access key of LocalS3 has no session token, like the one of an IAM user.
    SessionCredentialIssuer.Resolution resolution = sessionCredentialIssuer.resolve(requestAccessKeyId, sessionToken);
    if (!resolution.accepted()) {
      return Credential.failed(VerificationResult.failure(resolution.errorCode(),
          resolution.errorCode().description()));
    }
    return new Credential(VerificationResult.success(), resolution.secretAccessKey());
  }

  private VerificationResult validateRequestTime(String amzDate, String scopeDate,
      Duration presignedExpiry) {
    final Instant requestTime;
    try {
      requestTime = Instant.from(AMZ_DATE_FORMAT.parse(amzDate));
    } catch (DateTimeParseException e) {
      return malformed("The request time is not a valid ISO-8601 basic timestamp.");
    }
    if (!amzDate.startsWith(scopeDate)) {
      return malformed("The credential scope date does not match the request time.");
    }

    Instant now = clock.instant();
    if (presignedExpiry == null) {
      if (Duration.between(requestTime, now).abs().compareTo(ALLOWED_CLOCK_SKEW) > 0) {
        return VerificationResult.failure(S3ErrorCode.RequestTimeTooSkewed,
            S3ErrorCode.RequestTimeTooSkewed.description());
      }
    } else if (now.isBefore(requestTime.minus(ALLOWED_CLOCK_SKEW))
        || now.isAfter(requestTime.plus(presignedExpiry))) {
      return VerificationResult.failure(S3ErrorCode.AccessDenied, "Request has expired.");
    }
    return VerificationResult.success();
  }

  private VerificationResult validateSignedHeaders(String signedHeaders,
      Map<String, String> headers) {
    List<String> names = Arrays.asList(signedHeaders.split(";"));
    if (!names.contains("host")) {
      return malformed("The host header must be signed.");
    }
    for (String name : names) {
      if (name.isBlank() || !name.equals(name.toLowerCase(Locale.ROOT))) {
        return malformed("Signed header names must be lowercase.");
      }
      if (!headers.containsKey(name)) {
        return malformed("Signed header is missing from the request: " + name);
      }
    }
    List<String> sorted = new ArrayList<>(names);
    sorted.sort(String::compareTo);
    if (!names.equals(sorted)) {
      return malformed("Signed header names must be sorted.");
    }
    return VerificationResult.success();
  }

  private static boolean isStreamingOrUnsigned(String payloadHash) {
    return UNSIGNED_PAYLOAD.equals(payloadHash)
        || AmzHeaderValues.STREAMING_UNSIGNED_PAYLOAD.equals(payloadHash)
        || AmzHeaderValues.STREAMING_UNSIGNED_PAYLOAD_TRAILER.equals(payloadHash)
        || AmzHeaderValues.STREAMING_AWS4_HMAC_SHA_256_PAYLOAD.equals(payloadHash)
        || AmzHeaderValues.STREAMING_AWS4_HMAC_SHA256_PAYLOAD_TRAILER.equals(payloadHash);
  }

  private String canonicalRequest(HttpRequest request, RawRequestTarget target, CredentialScope scope,
      List<QueryParameter> queryParameters, Map<String, String> headers,
      String signedHeaders, String payloadHash, boolean presigned) {
    StringBuilder canonicalHeaders = new StringBuilder();
    for (String name : signedHeaders.split(";")) {
      canonicalHeaders.append(name).append(':').append(headers.get(name)).append('\n');
    }

    return request.getMethod().name() + '\n'
        + canonicalPath(target.rawPath(), scope) + '\n'
        + canonicalQuery(queryParameters, presigned) + '\n'
        + canonicalHeaders
        + '\n'
        + signedHeaders + '\n'
        + payloadHash;
  }

  /**
   * The canonical path of a request. Amazon S3 signs its path encoded once, as it is sent; every other service, e.g.
   * S3 Vectors, whose tagging operations carry an encoded ARN in their path, signs it encoded twice.
   */
  private static String canonicalPath(String rawPath, CredentialScope scope) {
    String canonical = canonicalizeRaw(rawPath, true);
    return "s3".equals(scope.service()) ? canonical : canonical.replace("%", "%25");
  }

  private static String canonicalQuery(List<QueryParameter> parameters, boolean presigned) {
    return parameters.stream()
        .filter(parameter -> !presigned
            || !"X-Amz-Signature".equalsIgnoreCase(parameter.decodedName()))
        .sorted(Comparator.comparing(QueryParameter::canonicalName)
            .thenComparing(QueryParameter::canonicalValue))
        .map(parameter -> parameter.canonicalName() + "=" + parameter.canonicalValue())
        .reduce((left, right) -> left + "&" + right)
        .orElse("");
  }

  private static Map<String, String> normalizedHeaders(HttpRequest request) {
    Map<String, String> result = new HashMap<>();
    request.getHeaders().forEach((name, value) -> result.put(
        name.toString().toLowerCase(Locale.ROOT), normalizeHeaderValue(value)));
    return result;
  }

  private static String normalizeHeaderValue(String value) {
    return WHITESPACE.matcher(value.trim()).replaceAll(" ");
  }

  private static String normalizeSignedHeaders(String value) {
    return value.trim().toLowerCase(Locale.ROOT);
  }

  private static ParsedAuthorization parseAuthorization(String authorization) {
    if (!authorization.startsWith(ALGORITHM + " ")) {
      throw new IllegalArgumentException("Unsupported Authorization algorithm.");
    }
    Map<String, String> attributes = new LinkedHashMap<>();
    for (String item : authorization.substring(ALGORITHM.length() + 1).split(",")) {
      String[] pair = item.trim().split("=", 2);
      if (pair.length != 2 || pair[1].isBlank()) {
        throw new IllegalArgumentException("The Authorization header is malformed.");
      }
      attributes.put(pair[0], pair[1]);
    }
    String credential = requiredAttribute(attributes, "Credential");
    String signedHeaders = requiredAttribute(attributes, "SignedHeaders");
    String signature = requiredAttribute(attributes, "Signature");
    if (!HEX_SHA256.matcher(signature).matches()) {
      throw new IllegalArgumentException("The Authorization signature is malformed.");
    }
    return new ParsedAuthorization(credential, signedHeaders, signature);
  }

  private static String requiredAttribute(Map<String, String> attributes, String name) {
    String value = attributes.get(name);
    if (value == null) {
      throw new IllegalArgumentException("The Authorization header is missing " + name + '.');
    }
    return value;
  }

  private static CredentialScope parseCredential(String credential) {
    String[] parts = credential.split("/", -1);
    if (parts.length != 5 || parts[0].isBlank() || parts[1].length() != 8
        || parts[2].isBlank() || parts[3].isBlank() || !TERMINATOR.equals(parts[4])) {
      throw new IllegalArgumentException("The credential scope is malformed.");
    }
    return new CredentialScope(parts[0], parts[1], parts[2], parts[3],
        String.join("/", Arrays.copyOfRange(parts, 1, parts.length)));
  }

  private static List<QueryParameter> parseQuery(String rawQuery) {
    List<QueryParameter> result = new ArrayList<>();
    if (rawQuery == null || rawQuery.isEmpty()) {
      return result;
    }
    for (String entry : rawQuery.split("&", -1)) {
      int separator = entry.indexOf('=');
      String rawName = separator < 0 ? entry : entry.substring(0, separator);
      String rawValue = separator < 0 ? "" : entry.substring(separator + 1);
      result.add(new QueryParameter(percentDecode(rawName), percentDecode(rawValue),
          canonicalizeRaw(rawName, false), canonicalizeRaw(rawValue, false)));
    }
    return result;
  }

  private static Optional<String> queryParameter(List<QueryParameter> parameters, String name) {
    return parameters.stream().filter(parameter -> name.equalsIgnoreCase(parameter.decodedName()))
        .map(QueryParameter::decodedValue).findFirst();
  }

  private static String requiredQueryParameter(List<QueryParameter> parameters, String name) {
    return queryParameter(parameters, name)
        .orElseThrow(() -> new IllegalArgumentException("Missing query parameter: " + name));
  }

  private static byte[] signingKey(String secretAccessKey, CredentialScope scope) {
    return signingKey(secretAccessKey, scope.date(), scope.region(), scope.service());
  }

  /**
   * The signing key of a credential scope, which {@linkplain AwsSignatureV4Presigner} derives the same way, so that
   * the URLs it signs are the ones this verifier accepts.
   *
   * @param secretAccessKey the secret access key that the key is derived from.
   * @param date the date of the scope, {@code yyyyMMdd}.
   * @param region the region of the scope.
   * @param service the service of the scope, e.g. {@code s3}.
   * @return the signing key.
   */
  static byte[] signingKey(String secretAccessKey, String date, String region, String service) {
    byte[] dateKey = hmac(("AWS4" + secretAccessKey).getBytes(StandardCharsets.UTF_8), date);
    byte[] regionKey = hmac(dateKey, region);
    byte[] serviceKey = hmac(regionKey, service);
    return hmac(serviceKey, TERMINATOR);
  }

  static String stringToSign(String amzDate, String scope, String canonicalRequest) {
    return ALGORITHM + '\n' + amzDate + '\n' + scope + '\n'
        + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
  }

  static String signature(byte[] signingKey, String stringToSign) {
    return HexFormat.of().formatHex(hmac(signingKey, stringToSign));
  }

  private static byte[] hmac(byte[] key, String value) {
    try {
      // thread safe for Thread and VirtualThread, and don't optimize
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new IllegalStateException("Unable to calculate an HMAC-SHA256 signature.", e);
    }
  }

  private static String sha256Hex(byte[] value) {
    return HexFormat.of().formatHex(sha256().digest(value));
  }

  /**
   * Hash a request body without copying it, if it is in memory.
   */
  private static String sha256Hex(PayloadBytes body) {
    return sha256Hex(body, 0, body.length());
  }

  private static String sha256Hex(PayloadBytes value, long index, long length) {
    MessageDigest digest = sha256();
    value.digest(digest, index, length);
    return HexFormat.of().formatHex(digest.digest());
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (Exception e) {
      throw new IllegalStateException("Unable to calculate a SHA-256 digest.", e);
    }
  }

  private static boolean secureEquals(String expectedHex, String suppliedHex) {
    try {
      return MessageDigest.isEqual(HexFormat.of().parseHex(expectedHex.toLowerCase(Locale.ROOT)),
          HexFormat.of().parseHex(suppliedHex.toLowerCase(Locale.ROOT)));
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  /**
   * Verify the signatures of an {@code aws-chunked} encoded body. The body is read in place, by
   * index, so that neither the body nor its chunks are copied if the body is in memory.
   */
  private static boolean verifyChunkSignatures(PayloadBytes encodedBody, byte[] signingKey,
      String amzDate, String scope, String seedSignature, boolean hasTrailer,
      String trailerHeaderNames) {
    long end = encodedBody.length();
    if (end == 0) {
      return false;
    }
    long offset = 0;
    String previousSignature = seedSignature;
    while (offset < end) {
      long lineEnd = indexOfCrlf(encodedBody, offset, end);
      if (lineEnd < 0) {
        return false;
      }
      if (lineEnd - offset > MAX_CHUNK_HEADER_LENGTH) {
        return false;
      }
      String header = encodedBody.toString(offset, (int) (lineEnd - offset), StandardCharsets.US_ASCII);
      offset = lineEnd + 2;
      String[] headerParts = header.split(";");
      final long chunkLength;
      try {
        chunkLength = Long.parseLong(headerParts[0].trim(), 16);
      } catch (NumberFormatException e) {
        return false;
      }
      String chunkSignature = null;
      for (int i = 1; i < headerParts.length; i++) {
        String[] extension = headerParts[i].split("=", 2);
        if (extension.length == 2 && "chunk-signature".equalsIgnoreCase(extension[0].trim())) {
          chunkSignature = extension[1].trim();
        }
      }
      if (chunkSignature == null || !HEX_SHA256.matcher(chunkSignature).matches()
          || chunkLength < 0 || chunkLength > end - offset) {
        return false;
      }
      String chunkStringToSign = CHUNK_ALGORITHM + '\n' + amzDate + '\n' + scope + '\n'
          + previousSignature + '\n' + EMPTY_SHA256 + '\n'
          + sha256Hex(encodedBody, offset, chunkLength);
      String expected = signature(signingKey, chunkStringToSign);
      if (!secureEquals(expected, chunkSignature)) {
        return false;
      }
      previousSignature = chunkSignature;
      offset += chunkLength;
      if (chunkLength == 0) {
        return hasTrailer
            ? verifyTrailer(encodedBody, offset, end, signingKey, amzDate, scope,
                previousSignature, trailerHeaderNames)
            : onlyCrlfRemains(encodedBody, offset, end);
      }
      if (end - offset < 2
          || encodedBody.getByte(offset) != '\r' || encodedBody.getByte(offset + 1) != '\n') {
        return false;
      }
      offset += 2;
    }
    return false;
  }

  private static boolean verifyTrailer(PayloadBytes encodedBody, long offset, long end, byte[] signingKey,
      String amzDate, String scope, String previousSignature, String trailerHeaderNames) {
    Map<String, String> trailerHeaders = new HashMap<>();
    while (offset < end) {
      long lineEnd = indexOfCrlf(encodedBody, offset, end);
      if (lineEnd < 0 || lineEnd - offset > MAX_CHUNK_HEADER_LENGTH) {
        return false;
      }
      if (lineEnd == offset) {
        offset += 2;
        break;
      }
      String line = encodedBody.toString(offset, (int) (lineEnd - offset), StandardCharsets.UTF_8);
      int separator = line.indexOf(':');
      if (separator <= 0) {
        return false;
      }
      trailerHeaders.put(line.substring(0, separator).toLowerCase(Locale.ROOT),
          normalizeHeaderValue(line.substring(separator + 1)));
      offset = lineEnd + 2;
    }
    if (offset != end || trailerHeaderNames == null) {
      return false;
    }

    String trailerSignature = trailerHeaders.remove("x-amz-trailer-signature");
    if (trailerSignature == null || !HEX_SHA256.matcher(trailerSignature).matches()) {
      return false;
    }
    List<String> names = Arrays.stream(trailerHeaderNames.split(","))
        .map(name -> name.trim().toLowerCase(Locale.ROOT))
        .sorted()
        .toList();
    StringBuilder canonicalTrailer = new StringBuilder();
    for (String name : names) {
      String value = trailerHeaders.get(name);
      if (value == null) {
        return false;
      }
      canonicalTrailer.append(name).append(':').append(value).append('\n');
    }
    String trailerStringToSign = TRAILER_ALGORITHM + '\n' + amzDate + '\n' + scope + '\n'
        + previousSignature + '\n'
        + sha256Hex(canonicalTrailer.toString().getBytes(StandardCharsets.UTF_8));
    return secureEquals(signature(signingKey, trailerStringToSign), trailerSignature);
  }

  private static boolean onlyCrlfRemains(PayloadBytes value, long offset, long end) {
    return offset == end
        || offset + 2 == end && value.getByte(offset) == '\r' && value.getByte(offset + 1) == '\n';
  }

  /**
   * Find the first CRLF in {@code value} between {@code offset} (inclusive) and {@code end} (exclusive).
   *
   * @return the absolute index of the CR; {@code -1} if there is no CRLF.
   */
  private static long indexOfCrlf(PayloadBytes value, long offset, long end) {
    long from = offset;
    while (true) {
      long cr = value.indexOf(from, end, (byte) '\r');
      if (cr < 0 || cr + 1 >= end) {
        return -1;
      }
      if (value.getByte(cr + 1) == '\n') {
        return cr;
      }
      from = cr + 1;
    }
  }

  private static String canonicalizeRaw(String value, boolean preserveSlash) {
    StringBuilder result = new StringBuilder();
    for (int index = 0; index < value.length();) {
      char current = value.charAt(index);
      if (current == '%' && index + 2 < value.length()
          && isHex(value.charAt(index + 1)) && isHex(value.charAt(index + 2))) {
        result.append('%')
            .append(Character.toUpperCase(value.charAt(index + 1)))
            .append(Character.toUpperCase(value.charAt(index + 2)));
        index += 3;
        continue;
      }
      int codePoint = value.codePointAt(index);
      byte[] bytes = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8);
      for (byte item : bytes) {
        int unsigned = item & 0xff;
        if (isUnreserved(unsigned) || preserveSlash && unsigned == '/') {
          result.append((char) unsigned);
        } else {
          result.append('%');
          String hex = Integer.toHexString(unsigned).toUpperCase(Locale.ROOT);
          if (hex.length() == 1) {
            result.append('0');
          }
          result.append(hex);
        }
      }
      index += Character.charCount(codePoint);
    }
    return result.isEmpty() && preserveSlash ? "/" : result.toString();
  }

  private static String percentDecode(String value) {
    ByteArrayOutputStream output = new ByteArrayOutputStream(value.length());
    for (int index = 0; index < value.length();) {
      char current = value.charAt(index);
      if (current == '%' && index + 2 < value.length()
          && isHex(value.charAt(index + 1)) && isHex(value.charAt(index + 2))) {
        output.write(Integer.parseInt(value.substring(index + 1, index + 3), 16));
        index += 3;
      } else {
        byte[] bytes = new String(Character.toChars(value.codePointAt(index)))
            .getBytes(StandardCharsets.UTF_8);
        output.writeBytes(bytes);
        index += Character.charCount(value.codePointAt(index));
      }
    }
    return output.toString(StandardCharsets.UTF_8);
  }

  private static boolean isHex(char value) {
    return value >= '0' && value <= '9' || value >= 'a' && value <= 'f'
        || value >= 'A' && value <= 'F';
  }

  static boolean isUnreserved(int value) {
    return value >= 'A' && value <= 'Z' || value >= 'a' && value <= 'z'
        || value >= '0' && value <= '9' || value == '-' || value == '_'
        || value == '.' || value == '~';
  }

  /**
   * Verify the signature of the policy of a browser form upload, {@code POST Object}, whose credentials are fields of
   * the form rather than headers. The signature covers the base64-encoded policy, not the request, so neither the
   * time of the request nor its headers are checked: the expiration of the policy limits how long the form can be
   * used, which {@linkplain PostPolicy} checks.
   *
   * <p>Both signature versions that Amazon S3 accepts for a form are verified:
   * <ul>
   *   <li>Signature Version 4: {@code x-amz-algorithm}, {@code x-amz-credential}, {@code x-amz-date} and
   *   {@code x-amz-signature}, the hex HMAC-SHA256 of the policy with the signing key of the credential scope;</li>
   *   <li>Signature Version 2: {@code AWSAccessKeyId} and {@code signature}, the base64 HMAC-SHA1 of the policy with
   *   the secret access key, which older upload libraries still send.</li>
   * </ul>
   *
   * @param policy the {@code policy} field, base64-encoded as the form carries it.
   * @param field the value of a field of the form, whose name is compared ignoring case.
   * @return the result.
   */
  VerificationResult verifyPostPolicy(String policy, Function<String, Optional<String>> field) {
    Optional<String> signatureV4 = field.apply("x-amz-signature");
    if (signatureV4.isPresent()) {
      return verifyPostPolicyV4(policy, signatureV4.get(), field);
    }
    Optional<String> signatureV2 = field.apply("signature");
    if (signatureV2.isPresent()) {
      return verifyPostPolicyV2(policy, signatureV2.get(), field);
    }
    return VerificationResult.failure(S3ErrorCode.AccessDenied, S3ErrorCode.AccessDenied.description());
  }

  private VerificationResult verifyPostPolicyV4(String policy, String suppliedSignature,
      Function<String, Optional<String>> field) {
    Optional<String> algorithm = field.apply("x-amz-algorithm");
    if (algorithm.isEmpty() || !ALGORITHM.equals(algorithm.get())) {
      return postFieldInvalid("Unsupported or missing signing algorithm: "
          + algorithm.orElse(""));
    }
    Optional<String> credential = field.apply("x-amz-credential");
    if (credential.isEmpty()) {
      return postFieldInvalid("The form must contain the field x-amz-credential.");
    }
    final CredentialScope scope;
    try {
      scope = parseCredential(credential.get());
    } catch (IllegalArgumentException e) {
      return postFieldInvalid(e.getMessage());
    }
    Credential resolved = resolveCredential(scope.accessKeyId(), field.apply("x-amz-security-token").orElse(null));
    if (!resolved.result().authenticated()) {
      return resolved.result();
    }
    if (!"s3".equals(scope.service())) {
      return postFieldInvalid("The credential scope service must be s3.");
    }
    Optional<String> amzDate = field.apply("x-amz-date");
    if (amzDate.isEmpty()) {
      return postFieldInvalid("The form must contain the field x-amz-date.");
    }
    try {
      AMZ_DATE_FORMAT.parse(amzDate.get());
    } catch (DateTimeParseException e) {
      return postFieldInvalid("x-amz-date is not a valid ISO-8601 basic timestamp.");
    }
    if (!amzDate.get().startsWith(scope.date())) {
      return postFieldInvalid("The credential scope date does not match x-amz-date.");
    }
    String expectedSignature = signature(signingKey(resolved.secretAccessKey(), scope), policy);
    return secureEquals(expectedSignature, suppliedSignature) ? VerificationResult.success() : signatureMismatch();
  }

  private VerificationResult verifyPostPolicyV2(String policy, String suppliedSignature,
      Function<String, Optional<String>> field) {
    Optional<String> suppliedAccessKeyId = field.apply("AWSAccessKeyId");
    if (suppliedAccessKeyId.isEmpty()) {
      return postFieldInvalid("The form must contain the field AWSAccessKeyId.");
    }
    Credential resolved = resolveCredential(suppliedAccessKeyId.get(),
        field.apply("x-amz-security-token").orElse(null));
    if (!resolved.result().authenticated()) {
      return resolved.result();
    }
    try {
      Mac mac = Mac.getInstance("HmacSHA1");
      mac.init(new SecretKeySpec(resolved.secretAccessKey().getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
      byte[] expected = mac.doFinal(policy.getBytes(StandardCharsets.UTF_8));
      byte[] supplied = Base64.getDecoder().decode(suppliedSignature.trim());
      return MessageDigest.isEqual(expected, supplied) ? VerificationResult.success() : signatureMismatch();
    } catch (IllegalArgumentException e) {
      return signatureMismatch();
    } catch (Exception e) {
      throw new IllegalStateException("Unable to calculate an HMAC-SHA1 signature.", e);
    }
  }

  private static VerificationResult postFieldInvalid(String message) {
    return VerificationResult.failure(S3ErrorCode.InvalidArgument, message);
  }

  private static VerificationResult malformed(String message) {
    return VerificationResult.failure(S3ErrorCode.AuthorizationHeaderMalformed, message);
  }

  private static VerificationResult signatureMismatch() {
    return VerificationResult.failure(S3ErrorCode.SignatureDoesNotMatch,
        S3ErrorCode.SignatureDoesNotMatch.description());
  }

  /**
   * The result of verifying the head of a request.
   *
   * @param result whether the head is accepted.
   * @param verifiedHead what verifying the body needs; {@code null} if the head is rejected.
   */
  record HeadVerification(VerificationResult result, VerifiedHead verifiedHead) {
    static HeadVerification failed(VerificationResult result) {
      return new HeadVerification(result, null);
    }
  }

  /**
   * A verified head of a request, with what verifying its body needs.
   *
   * @param complete whether nothing depends on the body, e.g. for a presigned URL.
   * @param signatureVerified whether the signature was verified; it can't be before the body is received if the
   *     request has no {@code x-amz-content-sha256}, whose signature covers the hash of the body.
   * @param signingKey the key that the chunk signatures are calculated with.
   * @param amzDate the time of the request, in the basic ISO 8601 format.
   * @param scope the credential scope.
   * @param seedSignature the signature of the head, which the signature of the first chunk is chained to.
   * @param payloadHash the payload hash that the signature covers.
   * @param payloadHashOfBody whether the payload hash was calculated from the body, so it needn't be checked again.
   * @param trailerHeaderNames the value of {@code x-amz-trailer}.
   */
  record VerifiedHead(boolean complete, boolean signatureVerified, byte[] signingKey, String amzDate, String scope,
                      String seedSignature, String payloadHash, boolean payloadHashOfBody,
                      String trailerHeaderNames) {

    static final VerifiedHead COMPLETE = new VerifiedHead(true, true, null, null, null, null, null, false, null);

    static final VerifiedHead SIGNATURE_UNVERIFIED =
        new VerifiedHead(false, false, null, null, null, null, null, false, null);
  }

  private record RequestTime(String amzDate, String header) {
  }

  /**
   * The credentials of a request.
   *
   * @param result whether they are accepted.
   * @param secretAccessKey the secret access key that the request is signed with; {@code null} if they aren't accepted.
   */
  private record Credential(VerificationResult result, String secretAccessKey) {

    static Credential failed(VerificationResult result) {
      return new Credential(result, null);
    }

    @Override
    public String toString() {
      // Never reveal the secret access key.
      return "Credential[result=" + result + "]";
    }
  }

  record VerificationResult(boolean authenticated, S3ErrorCode errorCode, String message) {
    static VerificationResult success() {
      return new VerificationResult(true, null, null);
    }

    static VerificationResult failure(S3ErrorCode errorCode, String message) {
      return new VerificationResult(false, errorCode, message);
    }
  }

  private record ParsedAuthorization(String credential, String signedHeaders, String signature) {
  }

  private record CredentialScope(String accessKeyId, String date, String region, String service,
                                 String value) {
  }

  private record QueryParameter(String decodedName, String decodedValue, String canonicalName,
                                String canonicalValue) {
  }

  private record RawRequestTarget(String rawPath, String rawQuery) {
    private static RawRequestTarget parse(String uri, String fallbackPath) {
      if (uri == null || uri.isEmpty()) {
        return new RawRequestTarget(Optional.ofNullable(fallbackPath).orElse("/"), null);
      }
      if (uri.startsWith("http://") || uri.startsWith("https://")) {
        URI parsed = URI.create(uri);
        return new RawRequestTarget(Optional.ofNullable(parsed.getRawPath()).orElse("/"),
            parsed.getRawQuery());
      }
      int queryStart = uri.indexOf('?');
      String path = queryStart < 0 ? uri : uri.substring(0, queryStart);
      String query = queryStart < 0 ? null : uri.substring(queryStart + 1);
      return new RawRequestTarget(path.isEmpty() ? "/" : path, query);
    }
  }
}
