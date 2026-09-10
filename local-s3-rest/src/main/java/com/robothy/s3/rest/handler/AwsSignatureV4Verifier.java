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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Verifies AWS Signature Version 4 requests for a single access key pair.
 */
final class AwsSignatureV4Verifier {

  private static final String ALGORITHM = "AWS4-HMAC-SHA256";
  private static final String CHUNK_ALGORITHM = "AWS4-HMAC-SHA256-PAYLOAD";
  private static final String TRAILER_ALGORITHM = "AWS4-HMAC-SHA256-TRAILER";
  private static final String TERMINATOR = "aws4_request";
  private static final String UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD";
  private static final String EMPTY_SHA256 = sha256Hex(new byte[0]);
  private static final Duration ALLOWED_CLOCK_SKEW = Duration.ofMinutes(15);
  private static final int MAX_PRESIGNED_EXPIRY_SECONDS = 7 * 24 * 60 * 60;
  private static final DateTimeFormatter AMZ_DATE_FORMAT =
      DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(java.time.ZoneOffset.UTC);
  private static final Pattern WHITESPACE = Pattern.compile("[\\t\\n\\r ]+");

  private final String accessKeyId;
  private final String secretAccessKey;
  private final Clock clock;

  AwsSignatureV4Verifier(String accessKeyId, String secretAccessKey) {
    this(accessKeyId, secretAccessKey, Clock.systemUTC());
  }

  AwsSignatureV4Verifier(String accessKeyId, String secretAccessKey, Clock clock) {
    if (accessKeyId == null || accessKeyId.isBlank()) {
      throw new IllegalArgumentException("accessKeyId must not be blank.");
    }
    if (secretAccessKey == null || secretAccessKey.isBlank()) {
      throw new IllegalArgumentException("secretAccessKey must not be blank.");
    }
    this.accessKeyId = accessKeyId;
    this.secretAccessKey = secretAccessKey;
    this.clock = Objects.requireNonNull(clock);
  }

  VerificationResult verify(HttpRequest request) {
    try {
      Optional<String> authorization = request.header(HttpHeaderNames.AUTHORIZATION.toString());
      if (authorization.isPresent()) {
        return verifyAuthorizationHeader(request, authorization.get());
      }

      RawRequestTarget target = RawRequestTarget.parse(request.getUri(), request.getPath());
      List<QueryParameter> queryParameters = parseQuery(target.rawQuery());
      if (queryParameter(queryParameters, "X-Amz-Algorithm").isPresent()) {
        return verifyPresignedUrl(request, target, queryParameters);
      }

      return VerificationResult.failure(S3ErrorCode.AccessDenied,
          "Request must include either a valid Authorization header or SigV4 query parameters.");
    } catch (IllegalArgumentException e) {
      return VerificationResult.failure(S3ErrorCode.AuthorizationHeaderMalformed, e.getMessage());
    }
  }

  private VerificationResult verifyAuthorizationHeader(HttpRequest request, String authorization) {
    ParsedAuthorization parsed = parseAuthorization(authorization);
    CredentialScope scope = parseCredential(parsed.credential());
    VerificationResult credentialResult = validateCredential(scope);
    if (!credentialResult.authenticated()) {
      return credentialResult;
    }

    Map<String, String> headers = normalizedHeaders(request);
    String amzDate = headers.get("x-amz-date");
    if (amzDate == null) {
      return malformed("The x-amz-date header is required.");
    }

    VerificationResult timeResult = validateRequestTime(amzDate, scope.date(), null);
    if (!timeResult.authenticated()) {
      return timeResult;
    }

    String signedHeaders = normalizeSignedHeaders(parsed.signedHeaders());
    VerificationResult signedHeaderResult = validateSignedHeaders(signedHeaders, headers);
    if (!signedHeaderResult.authenticated()) {
      return signedHeaderResult;
    }
    if (!Arrays.asList(signedHeaders.split(";")).contains("x-amz-date")) {
      return malformed("The x-amz-date header must be signed.");
    }

    String payloadHash = headers.getOrDefault(AmzHeaderNames.X_AMZ_CONTENT_SHA256,
        sha256Hex(requestBody(request)));
    VerificationResult payloadResult = validatePayloadHash(payloadHash, request);
    if (!payloadResult.authenticated()) {
      return payloadResult;
    }

    RawRequestTarget target = RawRequestTarget.parse(request.getUri(), request.getPath());
    String canonicalRequest = canonicalRequest(request, target, parseQuery(target.rawQuery()),
        headers, signedHeaders, payloadHash, false);
    byte[] signingKey = signingKey(scope);
    String expectedSignature = signature(signingKey,
        stringToSign(amzDate, scope.value(), canonicalRequest));
    if (!secureEquals(expectedSignature, parsed.signature())) {
      return signatureMismatch();
    }

    if (AmzHeaderValues.STREAMING_AWS4_HMAC_SHA_256_PAYLOAD.equals(payloadHash)
        && !verifyChunkSignatures(requestBody(request), signingKey, amzDate, scope.value(),
        parsed.signature(), false, null)) {
      return signatureMismatch();
    }
    if (AmzHeaderValues.STREAMING_AWS4_HMAC_SHA256_PAYLOAD_TRAILER.equals(payloadHash)
        && !verifyChunkSignatures(requestBody(request), signingKey, amzDate, scope.value(),
        parsed.signature(), true, headers.get("x-amz-trailer"))) {
      return signatureMismatch();
    }

    return VerificationResult.success();
  }

  private VerificationResult verifyPresignedUrl(HttpRequest request, RawRequestTarget target,
      List<QueryParameter> queryParameters) {
    String algorithm = requiredQueryParameter(queryParameters, "X-Amz-Algorithm");
    if (!ALGORITHM.equals(algorithm)) {
      return malformed("Unsupported signing algorithm: " + algorithm);
    }

    CredentialScope scope = parseCredential(requiredQueryParameter(queryParameters, "X-Amz-Credential"));
    VerificationResult credentialResult = validateCredential(scope);
    if (!credentialResult.authenticated()) {
      return credentialResult;
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
    String canonicalRequest = canonicalRequest(request, target, queryParameters, headers,
        signedHeaders, payloadHash, true);
    String expectedSignature = signature(signingKey(scope),
        stringToSign(amzDate, scope.value(), canonicalRequest));
    return secureEquals(expectedSignature, suppliedSignature)
        ? VerificationResult.success()
        : signatureMismatch();
  }

  private VerificationResult validateCredential(CredentialScope scope) {
    if (!accessKeyId.equals(scope.accessKeyId())) {
      return VerificationResult.failure(S3ErrorCode.InvalidAccessKeyId,
          "The AWS access key ID you provided does not exist in our records.");
    }
    if (!("s3".equals(scope.service()) || "s3vectors".equals(scope.service()))) {
      return malformed("The credential scope service must be s3 or s3vectors.");
    }
    return VerificationResult.success();
  }

  private VerificationResult validateRequestTime(String amzDate, String scopeDate,
      Duration presignedExpiry) {
    final Instant requestTime;
    try {
      requestTime = Instant.from(AMZ_DATE_FORMAT.parse(amzDate));
    } catch (DateTimeParseException e) {
      return malformed("The x-amz-date value is not a valid ISO-8601 basic timestamp.");
    }
    if (!amzDate.startsWith(scopeDate)) {
      return malformed("The credential scope date does not match x-amz-date.");
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

  private VerificationResult validatePayloadHash(String payloadHash, HttpRequest request) {
    if (UNSIGNED_PAYLOAD.equals(payloadHash)
        || AmzHeaderValues.STREAMING_UNSIGNED_PAYLOAD.equals(payloadHash)
        || AmzHeaderValues.STREAMING_UNSIGNED_PAYLOAD_TRAILER.equals(payloadHash)
        || AmzHeaderValues.STREAMING_AWS4_HMAC_SHA_256_PAYLOAD.equals(payloadHash)
        || AmzHeaderValues.STREAMING_AWS4_HMAC_SHA256_PAYLOAD_TRAILER.equals(payloadHash)) {
      return VerificationResult.success();
    }
    if (!payloadHash.matches("[0-9a-fA-F]{64}")) {
      return malformed("x-amz-content-sha256 is invalid.");
    }
    return secureEquals(payloadHash, sha256Hex(requestBody(request)))
        ? VerificationResult.success()
        : signatureMismatch();
  }

  private String canonicalRequest(HttpRequest request, RawRequestTarget target,
      List<QueryParameter> queryParameters, Map<String, String> headers,
      String signedHeaders, String payloadHash, boolean presigned) {
    StringBuilder canonicalHeaders = new StringBuilder();
    for (String name : signedHeaders.split(";")) {
      canonicalHeaders.append(name).append(':').append(headers.get(name)).append('\n');
    }

    return request.getMethod().name() + '\n'
        + canonicalizeRaw(target.rawPath(), true) + '\n'
        + canonicalQuery(queryParameters, presigned) + '\n'
        + canonicalHeaders
        + '\n'
        + signedHeaders + '\n'
        + payloadHash;
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
    if (!signature.matches("[0-9a-fA-F]{64}")) {
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

  private byte[] signingKey(CredentialScope scope) {
    byte[] dateKey = hmac(("AWS4" + secretAccessKey).getBytes(StandardCharsets.UTF_8), scope.date());
    byte[] regionKey = hmac(dateKey, scope.region());
    byte[] serviceKey = hmac(regionKey, scope.service());
    return hmac(serviceKey, TERMINATOR);
  }

  private static String stringToSign(String amzDate, String scope, String canonicalRequest) {
    return ALGORITHM + '\n' + amzDate + '\n' + scope + '\n'
        + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
  }

  private static String signature(byte[] signingKey, String stringToSign) {
    return HexFormat.of().formatHex(hmac(signingKey, stringToSign));
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

  private static String sha256Hex(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (Exception e) {
      throw new IllegalStateException("Unable to calculate a SHA-256 digest.", e);
    }
  }

  private static byte[] requestBody(HttpRequest request) {
    ByteBuf body = request.getBody();
    if (body == null || !body.isReadable()) {
      return new byte[0];
    }
    byte[] bytes = new byte[body.readableBytes()];
    body.getBytes(body.readerIndex(), bytes);
    return bytes;
  }

  private static boolean secureEquals(String expectedHex, String suppliedHex) {
    try {
      return MessageDigest.isEqual(HexFormat.of().parseHex(expectedHex.toLowerCase(Locale.ROOT)),
          HexFormat.of().parseHex(suppliedHex.toLowerCase(Locale.ROOT)));
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private static boolean verifyChunkSignatures(byte[] encodedBody, byte[] signingKey,
      String amzDate, String scope, String seedSignature, boolean hasTrailer,
      String trailerHeaderNames) {
    int offset = 0;
    String previousSignature = seedSignature;
    while (offset < encodedBody.length) {
      int lineEnd = indexOfCrlf(encodedBody, offset);
      if (lineEnd < 0) {
        return false;
      }
      String header = new String(encodedBody, offset, lineEnd - offset, StandardCharsets.US_ASCII);
      offset = lineEnd + 2;
      String[] headerParts = header.split(";");
      final int chunkLength;
      try {
        chunkLength = Integer.parseInt(headerParts[0].trim(), 16);
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
      if (chunkSignature == null || !chunkSignature.matches("[0-9a-fA-F]{64}")
          || offset + chunkLength > encodedBody.length) {
        return false;
      }
      byte[] chunk = Arrays.copyOfRange(encodedBody, offset, offset + chunkLength);
      String chunkStringToSign = CHUNK_ALGORITHM + '\n' + amzDate + '\n' + scope + '\n'
          + previousSignature + '\n' + EMPTY_SHA256 + '\n' + sha256Hex(chunk);
      String expected = signature(signingKey, chunkStringToSign);
      if (!secureEquals(expected, chunkSignature)) {
        return false;
      }
      previousSignature = chunkSignature;
      offset += chunkLength;
      if (chunkLength == 0) {
        return hasTrailer
            ? verifyTrailer(encodedBody, offset, signingKey, amzDate, scope,
                previousSignature, trailerHeaderNames)
            : onlyCrlfRemains(encodedBody, offset);
      }
      if (offset + 2 > encodedBody.length
          || encodedBody[offset] != '\r' || encodedBody[offset + 1] != '\n') {
        return false;
      }
      offset += 2;
    }
    return false;
  }

  private static boolean verifyTrailer(byte[] encodedBody, int offset, byte[] signingKey,
      String amzDate, String scope, String previousSignature, String trailerHeaderNames) {
    Map<String, String> trailerHeaders = new HashMap<>();
    while (offset < encodedBody.length) {
      int lineEnd = indexOfCrlf(encodedBody, offset);
      if (lineEnd < 0) {
        return false;
      }
      if (lineEnd == offset) {
        offset += 2;
        break;
      }
      String line = new String(encodedBody, offset, lineEnd - offset, StandardCharsets.UTF_8);
      int separator = line.indexOf(':');
      if (separator <= 0) {
        return false;
      }
      trailerHeaders.put(line.substring(0, separator).toLowerCase(Locale.ROOT),
          normalizeHeaderValue(line.substring(separator + 1)));
      offset = lineEnd + 2;
    }
    if (offset != encodedBody.length || trailerHeaderNames == null) {
      return false;
    }

    String trailerSignature = trailerHeaders.remove("x-amz-trailer-signature");
    if (trailerSignature == null || !trailerSignature.matches("[0-9a-fA-F]{64}")) {
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

  private static boolean onlyCrlfRemains(byte[] value, int offset) {
    return offset == value.length
        || offset + 2 == value.length && value[offset] == '\r' && value[offset + 1] == '\n';
  }

  private static int indexOfCrlf(byte[] value, int offset) {
    for (int i = offset; i + 1 < value.length; i++) {
      if (value[i] == '\r' && value[i + 1] == '\n') {
        return i;
      }
    }
    return -1;
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
    return result.length() == 0 && preserveSlash ? "/" : result.toString();
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

  private static boolean isUnreserved(int value) {
    return value >= 'A' && value <= 'Z' || value >= 'a' && value <= 'z'
        || value >= '0' && value <= '9' || value == '-' || value == '_'
        || value == '.' || value == '~';
  }

  private static VerificationResult malformed(String message) {
    return VerificationResult.failure(S3ErrorCode.AuthorizationHeaderMalformed, message);
  }

  private static VerificationResult signatureMismatch() {
    return VerificationResult.failure(S3ErrorCode.SignatureDoesNotMatch,
        S3ErrorCode.SignatureDoesNotMatch.description());
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
