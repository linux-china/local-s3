package com.robothy.s3.rest.handler;

import com.robothy.netty.http.HttpRequest;
import com.robothy.netty.http.HttpResponse;
import com.robothy.s3.core.exception.LocalS3Exception;
import com.robothy.s3.core.service.BucketService;
import com.robothy.s3.datatypes.CORSConfiguration;
import com.robothy.s3.datatypes.CORSRule;
import io.netty.handler.codec.http.HttpHeaderNames;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Adds CORS headers to the responses of cross-origin requests, i.e. requests with an {@code Origin} header, by the
 * matching rule of the bucket's CORS configuration, so that browsers let the page read the responses.
 */
final class CorsResponseHeaders {

  private static final String VARY = "Origin, Access-Control-Request-Headers, Access-Control-Request-Method";

  private final BucketService bucketService;

  CorsResponseHeaders(BucketService bucketService) {
    this.bucketService = Objects.requireNonNull(bucketService);
  }

  /**
   * Add the CORS headers to the response of an actual, i.e. not a preflight, cross-origin request.
   *
   * @param request the request, whose bucket the router has resolved.
   * @param response the response.
   */
  void apply(HttpRequest request, HttpResponse response) {
    Optional<String> origin = request.header(HttpHeaderNames.ORIGIN.toString());
    String bucketName = bucketName(request);
    if (origin.isEmpty() || bucketName == null) {
      return;
    }

    Optional<CORSConfiguration> configuration;
    try {
      configuration = bucketService.getBucketCors(bucketName);
    } catch (LocalS3Exception e) {
      // E.g. the bucket doesn't exist; the request handler reports it.
      return;
    }
    configuration.flatMap(config -> CorsRules.match(config, origin.get(), request.getMethod().name(), List.of()))
        .ifPresent(rule -> addHeaders(response, rule, origin.get(), List.of()));
  }

  /**
   * Get the bucket of a request, as resolved by the router from the path or the {@code Host} header.
   *
   * @return the bucket name; {@code null} if the request doesn't address a bucket.
   */
  static String bucketName(HttpRequest request) {
    List<String> bucket = request.getParams().get("bucket");
    return bucket == null || bucket.isEmpty() || bucket.get(0).isBlank() ? null : bucket.get(0);
  }

  /**
   * Add the CORS headers of a matching rule to a response.
   *
   * @param response a preflight response, or the response of an actual request.
   * @param rule the matching rule.
   * @param origin the {@code Origin} of the request.
   * @param requestHeaders the {@code Access-Control-Request-Headers} of a preflight request.
   */
  static void addHeaders(HttpResponse response, CORSRule rule, String origin, Collection<String> requestHeaders) {
    String allowOrigin = CorsRules.allowOrigin(rule, origin);
    response.putHeader(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN.toString(), allowOrigin)
        .putHeader(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS.toString(), String.join(", ", rule.getAllowedMethods()))
        .putHeader(HttpHeaderNames.VARY.toString(), VARY);
    if (!requestHeaders.isEmpty()) {
      response.putHeader(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS.toString(), String.join(", ", requestHeaders));
    }
    if (rule.getExposeHeaders() != null && !rule.getExposeHeaders().isEmpty()) {
      response.putHeader(HttpHeaderNames.ACCESS_CONTROL_EXPOSE_HEADERS.toString(),
          String.join(", ", rule.getExposeHeaders()));
    }
    if (rule.getMaxAgeSeconds() != null) {
      response.putHeader(HttpHeaderNames.ACCESS_CONTROL_MAX_AGE.toString(), rule.getMaxAgeSeconds());
    }
    if (!"*".equals(allowOrigin)) {
      response.putHeader(HttpHeaderNames.ACCESS_CONTROL_ALLOW_CREDENTIALS.toString(), "true");
    }
  }

}
