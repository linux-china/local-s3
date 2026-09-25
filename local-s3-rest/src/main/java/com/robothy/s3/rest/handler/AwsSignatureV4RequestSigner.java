package com.robothy.s3.rest.handler;

import com.robothy.s3.rest.constants.AmzHeaderNames;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Signs the headers of an S3 request on behalf of a client, with the credentials of the service: the server half of
 * the <em>remote signing</em> of the Iceberg REST catalog, where the {@code S3FileIO} of an engine holds no
 * credentials and asks the catalog to sign every request it is about to send.
 *
 * <p>It derives the signing key, the canonical request and the string to sign the same way
 * {@linkplain AwsSignatureV4Verifier} does, so a request signed here is one that the service accepts. The payload is
 * signed as {@code UNSIGNED-PAYLOAD} unless the client already put the SHA-256 of its body in
 * {@code x-amz-content-sha256}, since the body is not part of what the client sends to be signed.
 */
public final class AwsSignatureV4RequestSigner {

  private static final String SERVICE = "s3";
  private static final String TERMINATOR = "aws4_request";
  private static final String UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD";
  private static final Pattern WHITESPACE = Pattern.compile("[\\t\\n\\r ]+");

  /**
   * The headers that are left out of the signature: the ones that are replaced here, and the ones that an HTTP client
   * or an SDK may change or drop after the request is signed, which the AWS SDKs leave out as well.
   */
  private static final Set<String> UNSIGNED_HEADERS = Set.of("authorization", "x-amz-date", "connection", "expect",
      "user-agent", "x-amzn-trace-id", "transfer-encoding", "amz-sdk-invocation-id", "amz-sdk-request",
      "amz-sdk-retry");

  private final String accessKeyId;
  private final String secretAccessKey;
  private final Clock clock;

  /**
   * Create a signer that signs with the system clock.
   *
   * @param accessKeyId the access key ID of LocalS3.
   * @param secretAccessKey the secret access key of LocalS3.
   */
  public AwsSignatureV4RequestSigner(String accessKeyId, String secretAccessKey) {
    this(accessKeyId, secretAccessKey, Clock.systemUTC());
  }

  /**
   * Create a signer.
   *
   * @param accessKeyId the access key ID of LocalS3.
   * @param secretAccessKey the secret access key of LocalS3.
   * @param clock the clock that a request is signed at.
   */
  public AwsSignatureV4RequestSigner(String accessKeyId, String secretAccessKey, Clock clock) {
    if (accessKeyId == null || accessKeyId.isBlank()) {
      throw new IllegalArgumentException("accessKeyId must not be blank.");
    }
    if (secretAccessKey == null || secretAccessKey.isBlank()) {
      throw new IllegalArgumentException("secretAccessKey must not be blank.");
    }
    this.accessKeyId = accessKeyId;
    this.secretAccessKey = secretAccessKey;
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Sign a request.
   *
   * @param region the region of the credential scope; the default region of LocalS3 if blank.
   * @param method the HTTP method of the request, e.g. {@code GET}.
   * @param uri the absolute URI of the request, encoded as it is sent, e.g.
   *     {@code http://localhost:29090/bucket/a%20b.parquet?partNumber=1}.
   * @param headers the headers of the request, a header name to its values.
   * @return the headers that the request is to be sent with: the given ones, and {@code Host}, {@code X-Amz-Date},
   *     {@code x-amz-content-sha256} and {@code Authorization}.
   * @throws IllegalArgumentException if the method is blank, the URI is not an absolute one with a host, or the
   *     payload is declared to be sent in signed chunks, whose chunk signatures can't be signed remotely.
   */
  public Map<String, List<String>> sign(@Nullable String region, String method, String uri,
                                        Map<String, List<String>> headers) {
    if (method == null || method.isBlank()) {
      throw new IllegalArgumentException("method must not be blank.");
    }
    URI target = parse(uri);
    String scopeRegion = region == null || region.isBlank() ? AwsSignatureV4Presigner.DEFAULT_REGION : region;

    // Header names are case-insensitive: the last spelling of a name wins, with its values in the order they came.
    Map<String, List<String>> result = new LinkedHashMap<>();
    Map<String, String> names = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    headers.forEach((name, values) -> {
      String previous = names.put(name, name);
      if (previous != null) {
        result.remove(previous);
      }
      if (!"authorization".equalsIgnoreCase(name) && !"x-amz-date".equalsIgnoreCase(name)) {
        result.put(name, values == null ? List.of() : List.copyOf(values));
      }
    });

    if (!names.containsKey("host")) {
      result.put("Host", List.of(hostHeader(target)));
    }
    String payloadHash = first(result, names.get(AmzHeaderNames.X_AMZ_CONTENT_SHA256));
    if (payloadHash == null || payloadHash.isBlank()) {
      payloadHash = UNSIGNED_PAYLOAD;
      result.put(names.getOrDefault(AmzHeaderNames.X_AMZ_CONTENT_SHA256, AmzHeaderNames.X_AMZ_CONTENT_SHA256),
          List.of(payloadHash));
    } else if (payloadHash.startsWith("STREAMING-AWS4-")) {
      throw new IllegalArgumentException("A payload sent in signed chunks can't be signed remotely: " + payloadHash);
    }
    String amzDate = AwsSignatureV4Verifier.AMZ_DATE_FORMAT.format(clock.instant());
    result.put("X-Amz-Date", List.of(amzDate));

    TreeMap<String, String> canonicalHeaders = new TreeMap<>();
    result.forEach((name, values) -> {
      String lowerCase = name.toLowerCase(Locale.ROOT);
      if (!UNSIGNED_HEADERS.contains(lowerCase) || "x-amz-date".equals(lowerCase)) {
        canonicalHeaders.put(lowerCase, String.join(",", values.stream()
            .map(value -> WHITESPACE.matcher(value.trim()).replaceAll(" ")).toList()));
      }
    });
    String signedHeaders = String.join(";", canonicalHeaders.keySet());
    StringBuilder canonicalHeaderLines = new StringBuilder();
    canonicalHeaders.forEach((name, value) -> canonicalHeaderLines.append(name).append(':').append(value).append('\n'));

    String date = amzDate.substring(0, 8);
    String scope = date + '/' + scopeRegion + '/' + SERVICE + '/' + TERMINATOR;
    String rawPath = target.getRawPath();
    String canonicalRequest = method.trim().toUpperCase(Locale.ROOT) + '\n'
        + AwsSignatureV4Verifier.canonicalizeRaw(rawPath == null ? "" : rawPath, true) + '\n'
        + canonicalQuery(target.getRawQuery()) + '\n'
        + canonicalHeaderLines + '\n'
        + signedHeaders + '\n'
        + payloadHash;
    String signature = AwsSignatureV4Verifier.signature(
        AwsSignatureV4Verifier.signingKey(secretAccessKey, date, scopeRegion, SERVICE),
        AwsSignatureV4Verifier.stringToSign(amzDate, scope, canonicalRequest));

    result.put("Authorization", List.of(AwsSignatureV4Verifier.ALGORITHM
        + " Credential=" + accessKeyId + '/' + scope
        + ", SignedHeaders=" + signedHeaders
        + ", Signature=" + signature));
    return result;
  }

  private static URI parse(String uri) {
    if (uri == null || uri.isBlank()) {
      throw new IllegalArgumentException("uri must not be blank.");
    }
    URI result;
    try {
      result = new URI(uri.trim());
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException("uri is not a valid URI: " + uri, e);
    }
    if (result.getScheme() == null || result.getRawAuthority() == null) {
      throw new IllegalArgumentException("uri must be an absolute URI with a host, but was: " + uri);
    }
    return result;
  }

  /**
   * The {@code Host} header that an HTTP client sends for a URI: its authority, without the port when it is the
   * default port of the scheme.
   */
  private static String hostHeader(URI uri) {
    String authority = uri.getRawAuthority();
    String defaultPort = "https".equalsIgnoreCase(uri.getScheme()) ? ":443" : ":80";
    return authority.endsWith(defaultPort)
        ? authority.substring(0, authority.length() - defaultPort.length())
        : authority;
  }

  /**
   * The canonical query of a raw query, sorted by name and then by value, as {@linkplain AwsSignatureV4Verifier}
   * builds it.
   */
  private static String canonicalQuery(@Nullable String rawQuery) {
    if (rawQuery == null || rawQuery.isEmpty()) {
      return "";
    }
    List<String[]> parameters = new ArrayList<>();
    for (String entry : rawQuery.split("&", -1)) {
      int separator = entry.indexOf('=');
      String rawName = separator < 0 ? entry : entry.substring(0, separator);
      String rawValue = separator < 0 ? "" : entry.substring(separator + 1);
      parameters.add(new String[] {AwsSignatureV4Verifier.canonicalizeRaw(rawName, false),
          AwsSignatureV4Verifier.canonicalizeRaw(rawValue, false)});
    }
    return parameters.stream()
        .sorted(Comparator.<String[], String>comparing(parameter -> parameter[0])
            .thenComparing(parameter -> parameter[1]))
        .map(parameter -> parameter[0] + "=" + parameter[1])
        .reduce((left, right) -> left + "&" + right)
        .orElse("");
  }

  @Nullable
  private static String first(Map<String, List<String>> headers, @Nullable String name) {
    if (name == null) {
      return null;
    }
    List<String> values = headers.get(name);
    return values == null || values.isEmpty() ? null : values.get(0);
  }

}
