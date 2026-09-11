package com.robothy.s3.rest.handler;

import com.robothy.s3.datatypes.CORSConfiguration;
import com.robothy.s3.datatypes.CORSRule;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Matches cross-origin requests against the CORS rules of a bucket like Amazon S3: the first rule that allows the
 * origin, the method and all request headers applies. Origins and headers may contain one {@code *} wildcard;
 * header names are case-insensitive.
 */
final class CorsRules {

  private CorsRules() {
  }

  /**
   * Find the rule that applies to a request.
   *
   * @param configuration the CORS configuration of the bucket.
   * @param origin the {@code Origin} of the request.
   * @param method the method of the request, or the {@code Access-Control-Request-Method} of a preflight request.
   * @param requestHeaders the {@code Access-Control-Request-Headers} of a preflight request.
   * @return the first matching rule; empty if no rule allows the request.
   */
  static Optional<CORSRule> match(CORSConfiguration configuration, String origin, String method,
                                  Collection<String> requestHeaders) {
    if (configuration == null || configuration.getCorsRules() == null) {
      return Optional.empty();
    }
    return configuration.getCorsRules().stream()
        .filter(rule -> anyMatches(rule.getAllowedOrigins(), origin, false))
        .filter(rule -> rule.getAllowedMethods() != null && rule.getAllowedMethods().contains(method))
        .filter(rule -> requestHeaders.stream().allMatch(header -> anyMatches(rule.getAllowedHeaders(), header, true)))
        .findFirst();
  }

  /**
   * The {@code Access-Control-Allow-Origin} of a request that a rule allows.
   *
   * @return {@code *} if the rule allows any origin; otherwise the origin of the request.
   */
  static String allowOrigin(CORSRule rule, String origin) {
    return rule.getAllowedOrigins().contains("*") ? "*" : origin;
  }

  static boolean wildcardMatches(String pattern, String value, boolean ignoreCase) {
    String normalizedPattern = ignoreCase ? pattern.toLowerCase(Locale.ROOT) : pattern;
    String normalizedValue = ignoreCase ? value.toLowerCase(Locale.ROOT) : value;
    int wildcard = normalizedPattern.indexOf('*');
    if (wildcard < 0) {
      return normalizedPattern.equals(normalizedValue);
    }
    String prefix = normalizedPattern.substring(0, wildcard);
    String suffix = normalizedPattern.substring(wildcard + 1);
    return normalizedValue.length() >= prefix.length() + suffix.length()
        && normalizedValue.startsWith(prefix) && normalizedValue.endsWith(suffix);
  }

  private static boolean anyMatches(List<String> patterns, String value, boolean ignoreCase) {
    return patterns != null && value != null
        && patterns.stream().anyMatch(pattern -> wildcardMatches(pattern, value, ignoreCase));
  }

}
