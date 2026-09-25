package com.robothy.s3.rest.handler;

import com.robothy.netty.http.HttpRequest;
import com.robothy.netty.http.HttpResponse;
import com.robothy.s3.core.exception.LocalS3Exception;
import com.robothy.s3.core.service.BucketService;
import com.robothy.s3.rest.LocalS3Config;
import com.robothy.s3.rest.service.ServiceFactory;
import com.robothy.s3.datatypes.CORSConfiguration;
import com.robothy.s3.datatypes.CORSRule;
import io.netty.handler.codec.http.HttpHeaderNames;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Adds CORS headers to the responses of cross-origin requests, i.e. requests with an {@code Origin} header, by the
 * matching rule of the bucket's CORS configuration, so that browsers let the page read the responses. Where no bucket
 * configuration applies, i.e. the bucket has none or the request addresses no bucket, the default CORS rule of the
 * service applies, if it has one; see {@linkplain com.robothy.s3.rest.LocalS3Cors}.
 */
final class CorsResponseHeaders {

  private static final String VARY = "Origin, Access-Control-Request-Headers, Access-Control-Request-Method";

  private final BucketService bucketService;

  private final CORSConfiguration defaultConfiguration;

  CorsResponseHeaders(BucketService bucketService) {
    this(bucketService, null);
  }

  /**
   * Create the CORS headers of a service.
   *
   * @param bucketService reads the CORS configurations of the buckets.
   * @param defaultConfiguration the default CORS rule of the service; {@code null} for none.
   */
  CorsResponseHeaders(BucketService bucketService, CORSConfiguration defaultConfiguration) {
    this.bucketService = Objects.requireNonNull(bucketService);
    this.defaultConfiguration = defaultConfiguration;
  }

  /**
   * The default CORS rule of a service, as a CORS configuration.
   *
   * @param serviceFactory the services of the service.
   * @return the configuration; {@code null} if the service has no default rule, e.g. a router of handlers alone.
   */
  static CORSConfiguration defaultConfiguration(ServiceFactory serviceFactory) {
    return serviceFactory.containsInstance(LocalS3Config.class)
        ? serviceFactory.getInstance(LocalS3Config.class).cors().toCorsConfiguration()
        : null;
  }

  /**
   * Add the CORS headers to the response of an actual, i.e. not a preflight, cross-origin request.
   *
   * @param request the request, whose bucket the router has resolved.
   * @param response the response.
   */
  void apply(HttpRequest request, HttpResponse response) {
    Optional<String> origin = request.header(HttpHeaderNames.ORIGIN.toString());
    if (origin.isEmpty()) {
      return;
    }
    String bucketName = bucketName(request);
    Optional<CORSConfiguration> configuration;
    if (bucketName == null) {
      configuration = Optional.ofNullable(defaultConfiguration);
    } else {
      try {
        configuration = bucketService.getBucketCors(bucketName).or(() -> Optional.ofNullable(defaultConfiguration));
      } catch (LocalS3Exception e) {
        // E.g. the bucket doesn't exist, which the request handler reports: the page reads the error by the default
        // rule, if the service has one.
        configuration = Optional.ofNullable(defaultConfiguration);
      }
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
