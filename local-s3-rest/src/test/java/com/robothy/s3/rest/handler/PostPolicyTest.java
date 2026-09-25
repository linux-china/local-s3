package com.robothy.s3.rest.handler;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.robothy.s3.core.exception.LocalS3RequestException;
import com.robothy.s3.core.exception.S3ErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PostPolicyTest {

  private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");

  @Test
  void acceptsAFormThatSatisfiesEveryKindOfCondition() {
    PostPolicy policy = policy("""
        {"expiration": "2030-01-01T00:00:01.000Z",
         "conditions": [
           {"bucket": "uploads"},
           ["eq", "$Key", "user/42/a.png"],
           ["starts-with", "$Content-Type", "image/"],
           ["starts-with", "$x-amz-meta-tag", ""],
           ["content-length-range", "1", 10],
           {"success_action_status": "201"}]}""");

    assertDoesNotThrow(() -> check(policy, Map.of(
        "key", "user/42/a.png",
        "content-type", "image/png, image/jpeg",
        "x-amz-meta-tag", "anything",
        "Success_Action_Status", "201",
        "policy", "ignored",
        "x-ignore-me", "ignored"), 10));
  }

  @Test
  void rejectsAnExpiredPolicy() {
    PostPolicy policy = policy("{\"expiration\": \"2030-01-01T00:00:00Z\", \"conditions\": []}");
    assertError(S3ErrorCode.AccessDenied, "Policy expired", () -> check(policy, Map.of(), 1));
  }

  @Test
  void rejectsAFormThatFailsACondition() {
    PostPolicy policy = policy("""
        {"expiration": "2031-01-01T00:00:00Z",
         "conditions": [{"bucket": "uploads"}, ["starts-with", "$Content-Type", "image/"],
                        ["content-length-range", 2, 3]]}""");

    assertError(S3ErrorCode.AccessDenied, "Policy Condition failed: [\"starts-with\",\"$Content-Type\",\"image/\"]",
        () -> check(policy, Map.of("content-type", "image/png,text/html"), 2));
    // The bucket is the one that the form is posted to, whatever a bucket field of the form claims.
    PostPolicy otherBucket = policy("{\"expiration\": \"2031-01-01T00:00:00Z\", \"conditions\": [{\"bucket\": \"other\"}]}");
    assertError(S3ErrorCode.AccessDenied, "Policy Condition failed: {\"bucket\":\"other\"}",
        () -> check(otherBucket, Map.of("bucket", "other"), 2));
    assertError(S3ErrorCode.EntityTooSmall, null, () -> check(policy, Map.of("content-type", "image/png"), 1));
    assertError(S3ErrorCode.EntityTooLarge, null, () -> check(policy, Map.of("content-type", "image/png"), 4));
    assertError(S3ErrorCode.AccessDenied, "Extra input fields: acl",
        () -> check(policy, Map.of("content-type", "image/png", "ACL", "public-read"), 2));
  }

  @Test
  void rejectsWhatIsNotAPolicy() {
    assertError(S3ErrorCode.InvalidPolicyDocument, "Invalid 'base64' encoding", () -> PostPolicy.parse("%%%"));
    assertError(S3ErrorCode.InvalidPolicyDocument, "Invalid JSON", () -> PostPolicy.parse(base64("[1, 2")));
    assertError(S3ErrorCode.InvalidPolicyDocument, "missing expiration",
        () -> PostPolicy.parse(base64("{\"conditions\": []}")));
    assertError(S3ErrorCode.InvalidPolicyDocument, "Invalid 'expiration' value",
        () -> PostPolicy.parse(base64("{\"expiration\": \"tomorrow\", \"conditions\": []}")));
    assertError(S3ErrorCode.InvalidPolicyDocument, "missing conditions",
        () -> PostPolicy.parse(base64("{\"expiration\": \"2031-01-01T00:00:00Z\"}")));
    assertError(S3ErrorCode.InvalidPolicyDocument, "unknown operation",
        () -> policy("{\"expiration\": \"2031-01-01T00:00:00Z\", \"conditions\": [[\"ne\", \"$key\", \"a\"]]}"));
    assertError(S3ErrorCode.InvalidPolicyDocument, "Invalid Condition",
        () -> policy("{\"expiration\": \"2031-01-01T00:00:00Z\", \"conditions\": [[\"eq\", \"key\", \"a\"]]}"));
    assertError(S3ErrorCode.InvalidPolicyDocument, "Invalid Condition",
        () -> policy("{\"expiration\": \"2031-01-01T00:00:00Z\", \"conditions\": [[\"content-length-range\", -1, 2]]}"));
    // A condition object names exactly one field.
    assertError(S3ErrorCode.InvalidPolicyDocument, "Simple-Conditions must have exactly one property specified",
        () -> policy("{\"expiration\": \"2031-01-01T00:00:00Z\", \"conditions\": [{}]}"));
    assertError(S3ErrorCode.InvalidPolicyDocument, "Simple-Conditions must have exactly one property specified",
        () -> policy("{\"expiration\": \"2031-01-01T00:00:00Z\", \"conditions\": [{\"acl\": \"private\", \"key\": \"a\"}]}"));
  }

  private static PostPolicy policy(String json) {
    return PostPolicy.parse(base64(json));
  }

  private static String base64(String json) {
    return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
  }

  private static void check(PostPolicy policy, Map<String, String> fields, long fileSize) {
    Map<String, String> byLowerCase = new LinkedHashMap<>();
    fields.forEach((name, value) -> byLowerCase.put(name.toLowerCase(Locale.ROOT), value));
    policy.check(NOW, "uploads", fields.keySet(),
        name -> Optional.ofNullable(byLowerCase.get(name.toLowerCase(Locale.ROOT))), fileSize);
  }

  private static void assertError(S3ErrorCode expected, String message, Runnable action) {
    LocalS3RequestException thrown = assertThrows(LocalS3RequestException.class, action::run);
    assertEquals(expected, thrown.getS3ErrorCode());
    if (message != null) {
      assertTrue(thrown.getMessage().contains(message), thrown.getMessage());
    }
  }

}
