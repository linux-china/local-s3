package com.robothy.s3.rest.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.robothy.s3.datatypes.CORSConfiguration;
import com.robothy.s3.datatypes.CORSRule;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CorsRulesTest {

  private static final CORSConfiguration CONFIGURATION = new CORSConfiguration(List.of(
      CORSRule.builder().id("local-app")
          .allowedOrigins(List.of("http://localhost:3000"))
          .allowedMethods(List.of("GET", "PUT"))
          .allowedHeaders(List.of("Content-Type", "x-amz-*"))
          .build(),
      CORSRule.builder().id("sites")
          .allowedOrigins(List.of("https://*.example.com"))
          .allowedMethods(List.of("GET"))
          .build(),
      CORSRule.builder().id("anyone")
          .allowedOrigins(List.of("*"))
          .allowedMethods(List.of("HEAD", "PUT"))
          .allowedHeaders(List.of("*"))
          .build()));

  @Test
  void matchesTheFirstRuleAllowingTheRequest() {
    assertEquals("local-app", ruleId("http://localhost:3000", "PUT", List.of("content-type", "x-amz-meta-owner")));
    assertEquals("sites", ruleId("https://app.example.com", "GET", List.of()));
    assertEquals("anyone", ruleId("https://evil.test", "HEAD", List.of("authorization")));
    assertEquals("anyone", ruleId("http://localhost:3000", "PUT", List.of("authorization")),
        "The first rule doesn't allow the header; the last one does.");

    assertFalse(CorsRules.match(CONFIGURATION, "https://app.example.com", "DELETE", List.of()).isPresent());
    assertFalse(CorsRules.match(CONFIGURATION, "https://example.com", "GET", List.of()).isPresent(),
        "The wildcard origin needs a subdomain.");
    assertFalse(CorsRules.match(CONFIGURATION, "https://app.example.com", "GET", List.of("x-custom")).isPresent(),
        "A rule without allowed headers allows no request headers.");
    assertFalse(CorsRules.match(null, "http://localhost:3000", "GET", List.of()).isPresent());
  }

  @Test
  void allowsTheOriginOfTheRequestOrAny() {
    assertEquals("*", CorsRules.allowOrigin(CONFIGURATION.getCorsRules().get(2), "https://evil.test"));
    assertEquals("http://localhost:3000",
        CorsRules.allowOrigin(CONFIGURATION.getCorsRules().get(0), "http://localhost:3000"));
  }

  @Test
  void matchesWildcards() {
    assertTrue(CorsRules.wildcardMatches("x-amz-*", "X-Amz-Meta-Owner", true));
    assertFalse(CorsRules.wildcardMatches("http://localhost:3000", "HTTP://LOCALHOST:3000", false),
        "Origins are case-sensitive.");
    assertTrue(CorsRules.wildcardMatches("*", "anything", false));
    assertFalse(CorsRules.wildcardMatches("https://*.example.com", "https://example.com", false));
  }

  private static String ruleId(String origin, String method, List<String> requestHeaders) {
    Optional<CORSRule> rule = CorsRules.match(CONFIGURATION, origin, method, requestHeaders);
    return rule.map(CORSRule::getId).orElse(null);
  }

}
