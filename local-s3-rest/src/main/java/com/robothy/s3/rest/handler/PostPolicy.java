package com.robothy.s3.rest.handler;

import com.robothy.s3.core.exception.LocalS3RequestException;
import com.robothy.s3.core.exception.S3ErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The policy of a browser form upload, {@code POST Object}: when the form expires, and the conditions that its fields
 * and its file must satisfy. See
 * <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/sigv4-HTTPPOSTConstructPolicy.html">Creating a POST
 * policy</a>.
 *
 * <p>A policy is a base64-encoded JSON document:
 * <pre>{@code
 * { "expiration": "2030-01-01T00:00:00.000Z",
 *   "conditions": [
 *     {"bucket": "uploads"},
 *     ["starts-with", "$key", "user/42/"],
 *     ["content-length-range", 1, 10485760],
 *     {"success_action_status": "201"} ] }
 * }</pre>
 *
 * <p>A condition is an exact match, as an object or as {@code ["eq", "$field", "value"]}, a prefix match,
 * {@code ["starts-with", "$field", "prefix"]}, or the range of the size of the file,
 * {@code ["content-length-range", min, max]}. Field names are compared ignoring case. Every field of the form must be
 * named by a condition, except {@code policy}, {@code x-amz-signature}, {@code signature}, {@code AWSAccessKeyId},
 * {@code file} and the fields whose names start with {@code x-ignore-}.
 *
 * <p>The errors are those of Amazon S3, including their messages, so that a form that fails here fails the same way
 * there: {@code InvalidPolicyDocument} for a document that isn't a policy, e.g. one with a condition object that
 * doesn't name exactly one field, {@code AccessDenied} for an expired
 * policy, a failed condition or a field that no condition names, and {@code EntityTooSmall} or
 * {@code EntityTooLarge} for a file outside of the {@code content-length-range}.
 */
final class PostPolicy {

  private static final ObjectMapper JSON = JsonMapper.builderWithJackson2Defaults().build();

  /**
   * The fields that no condition needs to name: the policy and its signature, and the file.
   */
  private static final Set<String> UNCONDITIONED_FIELDS =
      Set.of("policy", "x-amz-signature", "signature", "awsaccesskeyid", "file");

  private static final String IGNORED_FIELD_PREFIX = "x-ignore-";

  private final Instant expiration;

  private final List<JsonNode> conditions;

  private PostPolicy(Instant expiration, List<JsonNode> conditions) {
    this.expiration = expiration;
    this.conditions = conditions;
  }

  /**
   * Decode a policy.
   *
   * @param base64Policy the {@code policy} field of the form.
   * @return the policy.
   * @throws LocalS3RequestException {@code InvalidPolicyDocument} if the field isn't a base64-encoded policy document.
   */
  static PostPolicy parse(String base64Policy) {
    final JsonNode document;
    try {
      // Line breaks that a form may wrap the policy in aren't part of the encoding.
      byte[] json = Base64.getDecoder().decode(base64Policy.replaceAll("\\s", ""));
      document = JSON.readTree(new String(json, StandardCharsets.UTF_8));
    } catch (IllegalArgumentException e) {
      throw invalidPolicy("Invalid Policy: Invalid 'base64' encoding.");
    } catch (JacksonException e) {
      throw invalidPolicy("Invalid Policy: Invalid JSON.");
    }
    if (document == null || !document.isObject()) {
      throw invalidPolicy("Invalid Policy: Invalid JSON.");
    }

    JsonNode expirationNode = document.get("expiration");
    if (expirationNode == null) {
      throw invalidPolicy("Invalid Policy: Policy missing expiration.");
    }
    Instant expiration;
    try {
      expiration = OffsetDateTime.parse(expirationNode.asText()).toInstant();
    } catch (DateTimeParseException e) {
      throw invalidPolicy("Invalid Policy: Invalid 'expiration' value: '" + expirationNode.asText() + "'");
    }

    JsonNode conditionsNode = document.get("conditions");
    if (conditionsNode == null) {
      throw invalidPolicy("Invalid Policy: Policy missing conditions.");
    }
    if (!conditionsNode.isArray()) {
      throw invalidPolicy("Invalid Policy: Invalid 'conditions' value: must be a List.");
    }
    List<JsonNode> conditions = new ArrayList<>();
    conditionsNode.forEach(condition -> {
      validateCondition(condition);
      conditions.add(condition);
    });
    return new PostPolicy(expiration, conditions);
  }

  /**
   * Check that the form satisfies the policy.
   *
   * @param now the current time.
   * @param bucketName the bucket that the form is posted to.
   * @param fields the names of the fields of the form, as the form spells them.
   * @param field the value of a field of the form, whose name is compared ignoring case.
   * @param fileSize the size of the file.
   * @throws LocalS3RequestException if the policy expired, or the form doesn't satisfy it.
   */
  void check(Instant now, String bucketName, Iterable<String> fields, Function<String, Optional<String>> field,
             long fileSize) {
    if (!now.isBefore(expiration)) {
      throw accessDenied("Invalid according to Policy: Policy expired.");
    }

    Set<String> named = new TreeSet<>();
    for (JsonNode condition : conditions) {
      if (condition.isObject()) {
        for (Map.Entry<String, JsonNode> entry : condition.properties()) {
          String name = fieldName(entry.getKey());
          named.add(name);
          if (!entry.getValue().asText().equals(value(name, bucketName, field))) {
            throw conditionFailed(condition);
          }
        }
        continue;
      }

      String operator = condition.get(0).asText().toLowerCase(Locale.ROOT);
      if ("content-length-range".equals(operator)) {
        long min = condition.get(1).asLong();
        long max = condition.get(2).asLong();
        if (fileSize < min) {
          throw new LocalS3RequestException(S3ErrorCode.EntityTooSmall,
              "Your proposed upload is smaller than the minimum allowed size");
        }
        if (fileSize > max) {
          throw new LocalS3RequestException(S3ErrorCode.EntityTooLarge,
              "Your proposed upload exceeds the maximum allowed size");
        }
        continue;
      }

      String name = fieldName(condition.get(1).asText());
      named.add(name);
      String actual = value(name, bucketName, field);
      String expected = condition.get(2).asText();
      boolean satisfied = "eq".equals(operator) ? expected.equals(actual) : startsWith(name, actual, expected);
      if (!satisfied) {
        throw conditionFailed(condition);
      }
    }

    List<String> extra = new ArrayList<>();
    for (String name : fields) {
      String lowerCase = name.toLowerCase(Locale.ROOT);
      if (!UNCONDITIONED_FIELDS.contains(lowerCase) && !lowerCase.startsWith(IGNORED_FIELD_PREFIX)
          && !named.contains(lowerCase)) {
        extra.add(lowerCase);
      }
    }
    if (!extra.isEmpty()) {
      throw accessDenied("Invalid according to Policy: Extra input fields: " + String.join(", ", extra));
    }
  }

  /**
   * Whether a value starts with a prefix. The values of {@code Content-Type} may be a comma-separated list, e.g. of a
   * form that uploads several files, each of which must start with the prefix.
   */
  private static boolean startsWith(String name, String value, String prefix) {
    if ("content-type".equals(name)) {
      for (String contentType : value.split(",", -1)) {
        if (!contentType.trim().startsWith(prefix)) {
          return false;
        }
      }
      return true;
    }
    return value.startsWith(prefix);
  }

  /**
   * The value that a condition on a field is checked against: the bucket that the form is posted to for
   * {@code bucket}, whatever a {@code bucket} field of the form claims, so that a policy for one bucket can't be used
   * to upload to another; and the value of the field otherwise, or an empty value if the form has no such field.
   */
  private static String value(String name, String bucketName, Function<String, Optional<String>> field) {
    if ("bucket".equals(name)) {
      return bucketName;
    }
    return field.apply(name).orElse("");
  }

  private static String fieldName(String name) {
    return (name.startsWith("$") ? name.substring(1) : name).toLowerCase(Locale.ROOT);
  }

  private static void validateCondition(JsonNode condition) {
    if (condition.isObject()) {
      if (condition.size() != 1) {
        throw invalidPolicy("Invalid Policy: Invalid Simple-Condition: Simple-Conditions must have exactly one "
            + "property specified.");
      }
      for (Map.Entry<String, JsonNode> entry : condition.properties()) {
        if (!entry.getValue().isValueNode()) {
          throw invalidCondition(condition);
        }
      }
      return;
    }
    if (!condition.isArray() || condition.size() != 3 || !condition.get(0).isTextual()) {
      throw invalidCondition(condition);
    }
    String operator = condition.get(0).asText().toLowerCase(Locale.ROOT);
    switch (operator) {
      case "eq", "starts-with" -> {
        if (!condition.get(1).isTextual() || !condition.get(1).asText().startsWith("$")
            || !condition.get(2).isValueNode()) {
          throw invalidCondition(condition);
        }
      }
      case "content-length-range" -> {
        if (!isSize(condition.get(1)) || !isSize(condition.get(2))) {
          throw invalidCondition(condition);
        }
      }
      default -> throw invalidPolicy("Invalid Policy: Invalid Condition: unknown operation " + operator + ".");
    }
  }

  private static boolean isSize(JsonNode node) {
    if (node.isIntegralNumber()) {
      return node.asLong() >= 0;
    }
    if (node.isTextual()) {
      try {
        return Long.parseLong(node.asText()) >= 0;
      } catch (NumberFormatException e) {
        return false;
      }
    }
    return false;
  }

  private static LocalS3RequestException conditionFailed(JsonNode condition) {
    return accessDenied("Invalid according to Policy: Policy Condition failed: " + condition);
  }

  private static LocalS3RequestException invalidCondition(JsonNode condition) {
    return invalidPolicy("Invalid Policy: Invalid Condition: " + condition);
  }

  private static LocalS3RequestException accessDenied(String message) {
    return new LocalS3RequestException(S3ErrorCode.AccessDenied, message);
  }

  private static LocalS3RequestException invalidPolicy(String message) {
    return new LocalS3RequestException(S3ErrorCode.InvalidPolicyDocument, message);
  }

}
