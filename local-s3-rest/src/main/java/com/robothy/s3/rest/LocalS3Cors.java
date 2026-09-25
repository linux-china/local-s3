package com.robothy.s3.rest;

import com.robothy.s3.datatypes.CORSConfiguration;
import com.robothy.s3.datatypes.CORSRule;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The default CORS rule of a {@linkplain LocalS3}: the rule that the cross-origin requests of a browser are answered
 * by when the bucket they address has <b>no</b> CORS configuration of its own, so that a single page application, a
 * presigned upload, DuckDB-WASM or a notebook in a browser reaches the service without a {@code PutBucketCors} for
 * every bucket first.
 *
 * <ul>
 *   <li>A bucket that was configured with {@code PutBucketCors} keeps its own rules, exactly like Amazon S3: a request
 *       that they don't allow isn't allowed by the default rule either;</li>
 *   <li>the requests that address no bucket, e.g. {@code ListBuckets}, the Iceberg REST catalog under
 *       {@code /iceberg/v1} and the S3 Tables API, are answered by the default rule alone.</li>
 * </ul>
 *
 * <p>It is off by default. Allowing an origin lets the pages of that origin read and write the data of the service
 * from the browser of anyone who opens them; {@code *} allows every web page, which is convenient on a developer's
 * machine and a risk on a service that other machines reach or that holds data worth protecting.
 *
 * @param allowedOrigins the origins that are allowed, e.g. {@code http://localhost:5173}; each may contain one
 *     {@code *} wildcard, and {@code *} alone allows every origin. Empty turns the default rule off.
 * @param allowedMethods the methods that are allowed, among {@code GET}, {@code PUT}, {@code POST}, {@code DELETE}
 *     and {@code HEAD}; empty for all of them.
 * @param allowedHeaders the request headers that are allowed, each may contain one {@code *} wildcard; empty for
 *     {@code *}, i.e. every header.
 * @param exposeHeaders the response headers that the page may read beside the CORS-safelisted ones; empty for
 *     {@linkplain #DEFAULT_EXPOSE_HEADERS}, e.g. {@code ETag}, which a multipart upload of a browser needs.
 * @param maxAgeSeconds the seconds that a browser may cache a preflight response; {@code null} for the browser's
 *     default.
 */
public record LocalS3Cors(List<String> allowedOrigins, List<String> allowedMethods, List<String> allowedHeaders,
                          List<String> exposeHeaders, Integer maxAgeSeconds) {

  /**
   * The methods that a CORS rule of Amazon S3 may allow, which the default rule allows unless it is told otherwise.
   */
  public static final List<String> DEFAULT_ALLOWED_METHODS = List.of("GET", "PUT", "POST", "DELETE", "HEAD");

  /**
   * The response headers that the pages may read by default: the ones that the clients of a browser, e.g. the AWS SDK
   * for JavaScript and a multipart upload, read off the responses.
   */
  public static final List<String> DEFAULT_EXPOSE_HEADERS = List.of("ETag", "Content-Length", "Content-Range",
      "Last-Modified", "x-amz-version-id", "x-amz-delete-marker", "x-amz-request-id", "x-amz-id-2",
      "x-amz-server-side-encryption", "x-amz-checksum-crc32", "x-amz-checksum-crc32c", "x-amz-checksum-crc64nvme",
      "x-amz-checksum-sha1", "x-amz-checksum-sha256", "x-amz-mp-parts-count");

  private static final Set<String> METHODS = Set.copyOf(DEFAULT_ALLOWED_METHODS);

  private static final LocalS3Cors DISABLED = new LocalS3Cors(List.of(), List.of(), List.of(), List.of(), null);

  /**
   * Validate the rule and copy the lists.
   *
   * @throws IllegalArgumentException if a method isn't one that Amazon S3 allows, a value is blank, or the max age is
   *     negative.
   */
  public LocalS3Cors {
    allowedOrigins = normalize(allowedOrigins, "origin", false);
    allowedMethods = normalize(allowedMethods, "method", true);
    allowedHeaders = normalize(allowedHeaders, "header", false);
    exposeHeaders = normalize(exposeHeaders, "expose header", false);
    for (String method : allowedMethods) {
      if (!METHODS.contains(method)) {
        throw new IllegalArgumentException("Unsupported CORS method " + method + ", must be one of "
            + DEFAULT_ALLOWED_METHODS + ".");
      }
    }
    if (maxAgeSeconds != null && maxAgeSeconds < 0) {
      throw new IllegalArgumentException("The CORS max age must not be negative.");
    }
  }

  /**
   * The settings of a service without a default CORS rule, which is the default: a cross-origin request is allowed by
   * the CORS configuration of its bucket alone.
   *
   * @return the settings.
   */
  public static LocalS3Cors disabled() {
    return DISABLED;
  }

  /**
   * A default rule that allows some origins every method and header, e.g. {@code allowOrigins("*")}.
   *
   * @param origins the origins that are allowed.
   * @return the settings.
   */
  public static LocalS3Cors allowOrigins(String... origins) {
    return DISABLED.withAllowedOrigins(List.of(origins));
  }

  /**
   * Whether the service has a default CORS rule.
   *
   * @return {@code true} if an origin is allowed.
   */
  public boolean enabled() {
    return !allowedOrigins.isEmpty();
  }

  /**
   * The default rule as the CORS configuration of a bucket, which a cross-origin request is matched against.
   *
   * @return the configuration; {@code null} if the default rule is {@linkplain #enabled() off}.
   */
  public CORSConfiguration toCorsConfiguration() {
    if (!enabled()) {
      return null;
    }
    CORSRule rule = CORSRule.builder()
        .id("local-s3-default")
        .allowedOrigins(allowedOrigins)
        .allowedMethods(allowedMethods.isEmpty() ? DEFAULT_ALLOWED_METHODS : allowedMethods)
        .allowedHeaders(allowedHeaders.isEmpty() ? List.of("*") : allowedHeaders)
        .exposeHeaders(exposeHeaders.isEmpty() ? DEFAULT_EXPOSE_HEADERS : exposeHeaders)
        .maxAgeSeconds(maxAgeSeconds)
        .build();
    return CORSConfiguration.builder().corsRules(List.of(rule)).build();
  }

  public LocalS3Cors withAllowedOrigins(List<String> allowedOrigins) {
    return new LocalS3Cors(allowedOrigins, allowedMethods, allowedHeaders, exposeHeaders, maxAgeSeconds);
  }

  public LocalS3Cors withAllowedMethods(List<String> allowedMethods) {
    return new LocalS3Cors(allowedOrigins, allowedMethods, allowedHeaders, exposeHeaders, maxAgeSeconds);
  }

  public LocalS3Cors withAllowedHeaders(List<String> allowedHeaders) {
    return new LocalS3Cors(allowedOrigins, allowedMethods, allowedHeaders, exposeHeaders, maxAgeSeconds);
  }

  public LocalS3Cors withExposeHeaders(List<String> exposeHeaders) {
    return new LocalS3Cors(allowedOrigins, allowedMethods, allowedHeaders, exposeHeaders, maxAgeSeconds);
  }

  public LocalS3Cors withMaxAgeSeconds(Integer maxAgeSeconds) {
    return new LocalS3Cors(allowedOrigins, allowedMethods, allowedHeaders, exposeHeaders, maxAgeSeconds);
  }

  /**
   * Split a comma-separated list, e.g. the value of an environment variable, into its trimmed, non-empty values.
   *
   * @param values the list, e.g. {@code http://localhost:5173, http://localhost:3000}.
   * @return the values.
   */
  static List<String> split(String values) {
    return values == null ? List.of()
        : Arrays.stream(values.split(",")).map(String::trim).filter(value -> !value.isEmpty()).toList();
  }

  private static List<String> normalize(List<String> values, String name, boolean upperCase) {
    if (values == null) {
      return List.of();
    }
    return values.stream()
        .map(value -> {
          if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("A CORS " + name + " must not be blank.");
          }
          String trimmed = value.trim();
          return upperCase ? trimmed.toUpperCase(Locale.ROOT) : trimmed;
        })
        .distinct()
        .toList();
  }

}
