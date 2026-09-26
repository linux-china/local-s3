package com.robothy.s3.rest.handler;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The session policy of temporary credentials, the {@code Policy} of {@code AssumeRole}, which limits what the
 * credentials can do. The identity policy of the role, i.e. everything since LocalS3 has no IAM, is intersected with
 * it: a request is allowed if a statement allows it and none denies it, as IAM evaluates a session policy.
 *
 * <p>A catalog that vends credentials scopes them to the location of a table this way, e.g. Lakekeeper and Apache
 * Polaris, and Lakekeeper checks that credentials scoped to a table can't write next to it.
 *
 * <p>Statements are evaluated by {@code Effect}, {@code Action} or {@code NotAction}, {@code Resource} or
 * {@code NotResource}, with the wildcards {@code *} and {@code ?}, and by {@code Condition}, of the operators
 * {@code StringEquals}, {@code StringNotEquals}, {@code StringLike} and {@code StringNotLike}, with or without
 * {@code IfExists}, on the keys of {@linkplain #CONDITION_KEYS}. A condition of another operator or on another key is
 * ignored, i.e. met, since LocalS3 can't evaluate it; so is {@code Principal}, which a session policy doesn't have.
 */
final class SessionPolicy {

  /**
   * The condition keys that LocalS3 evaluates, in lower case, as IAM compares them ignoring case.
   */
  static final Set<String> CONDITION_KEYS =
      Set.of("s3:prefix", "s3:delimiter", "s3:max-keys", "s3:versionid");

  private static final ObjectMapper JSON = JsonMapper.builderWithJackson2Defaults().build();

  private final List<Statement> statements;

  private SessionPolicy(List<Statement> statements) {
    this.statements = statements;
  }

  /**
   * Parse a policy document.
   *
   * @param document the JSON of the policy.
   * @return the policy.
   * @throws IllegalArgumentException if the document isn't a policy, with the reason in its message.
   */
  static SessionPolicy parse(String document) {
    return new SessionPolicy(statements(tree(document)));
  }

  /**
   * The document of a policy without insignificant whitespace, which a session token carries.
   *
   * @param document the JSON of a policy that {@linkplain #parse} accepts.
   * @return the compact JSON of the same policy.
   */
  static String compact(String document) {
    return JSON.writeValueAsString(tree(document));
  }

  /**
   * Whether the policy allows an action on a resource.
   *
   * @param action the IAM action, e.g. {@code s3:GetObject}.
   * @param resource the ARN of the resource, e.g. {@code arn:aws:s3:::bucket/key}.
   * @param context the values of the {@linkplain #CONDITION_KEYS} that the request has, e.g. {@code s3:prefix}.
   * @return whether the action is allowed.
   */
  boolean allows(String action, String resource, Map<String, String> context) {
    boolean allowed = false;
    for (Statement statement : statements) {
      if (statement.applies(action, resource, context)) {
        if (!statement.allow()) {
          return false;
        }
        allowed = true;
      }
    }
    return allowed;
  }

  private static JsonNode tree(String document) {
    JsonNode tree;
    try {
      tree = JSON.readTree(document);
    } catch (JacksonException e) {
      throw new IllegalArgumentException("Syntax errors in policy.");
    }
    if (tree == null || !tree.isObject()) {
      throw new IllegalArgumentException("Syntax errors in policy.");
    }
    return tree;
  }

  private static List<Statement> statements(JsonNode document) {
    JsonNode statementNode = document.get("Statement");
    if (statementNode == null) {
      throw new IllegalArgumentException("Missing required field Statement");
    }
    List<Statement> statements = new ArrayList<>();
    for (JsonNode node : statementNode.isArray() ? statementNode : List.of(statementNode)) {
      if (!node.isObject()) {
        throw new IllegalArgumentException("Syntax errors in policy.");
      }
      String effect = node.path("Effect").asString("");
      if (!"Allow".equals(effect) && !"Deny".equals(effect)) {
        throw new IllegalArgumentException("Missing required field Effect");
      }
      boolean notAction = node.has("NotAction");
      boolean notResource = node.has("NotResource");
      if (notAction == node.has("Action")) {
        throw new IllegalArgumentException("Missing required field Action");
      }
      if (notResource == node.has("Resource")) {
        throw new IllegalArgumentException("Missing required field Resource");
      }
      List<String> actions = strings(node.get(notAction ? "NotAction" : "Action")).stream()
          .map(action -> action.toLowerCase(Locale.ROOT)).toList();
      List<String> resources = strings(node.get(notResource ? "NotResource" : "Resource"));
      statements.add(new Statement("Allow".equals(effect), actions, notAction, resources, notResource,
          conditions(node.get("Condition"))));
    }
    return List.copyOf(statements);
  }

  private static List<Condition> conditions(JsonNode node) {
    if (node == null) {
      return List.of();
    }
    if (!node.isObject()) {
      throw new IllegalArgumentException("Syntax errors in policy.");
    }
    List<Condition> conditions = new ArrayList<>();
    for (Map.Entry<String, JsonNode> operator : node.properties()) {
      if (!operator.getValue().isObject()) {
        throw new IllegalArgumentException("Syntax errors in policy.");
      }
      for (Map.Entry<String, JsonNode> key : operator.getValue().properties()) {
        conditions.add(new Condition(operator.getKey(), key.getKey().toLowerCase(Locale.ROOT),
            strings(key.getValue())));
      }
    }
    return List.copyOf(conditions);
  }

  private static List<String> strings(JsonNode node) {
    List<String> values = new ArrayList<>();
    for (JsonNode value : node.isArray() ? node : List.of(node)) {
      if (!value.isValueNode()) {
        throw new IllegalArgumentException("Syntax errors in policy.");
      }
      values.add(value.asString());
    }
    return List.copyOf(values);
  }

  /**
   * Whether a value matches a pattern of a policy, in which {@code *} matches any characters and {@code ?} any one.
   */
  static boolean matches(String pattern, String value) {
    int p = 0;
    int v = 0;
    int star = -1;
    int starValue = 0;
    while (v < value.length()) {
      if (p < pattern.length() && (pattern.charAt(p) == '?' || pattern.charAt(p) == value.charAt(v))) {
        p++;
        v++;
      } else if (p < pattern.length() && pattern.charAt(p) == '*') {
        star = p++;
        starValue = v;
      } else if (star >= 0) {
        p = star + 1;
        v = ++starValue;
      } else {
        return false;
      }
    }
    while (p < pattern.length() && pattern.charAt(p) == '*') {
      p++;
    }
    return p == pattern.length();
  }

  private record Statement(boolean allow, List<String> actions, boolean notAction, List<String> resources,
                           boolean notResource, List<Condition> conditions) {

    boolean applies(String action, String resource, Map<String, String> context) {
      String lowerCaseAction = action.toLowerCase(Locale.ROOT);
      boolean actionMatches = actions.stream().anyMatch(pattern -> matches(pattern, lowerCaseAction));
      boolean resourceMatches = resources.stream().anyMatch(pattern -> matches(pattern, resource));
      return actionMatches != notAction && resourceMatches != notResource
          && conditions.stream().allMatch(condition -> condition.isMet(context));
    }
  }

  private record Condition(String operator, String key, List<String> values) {

    boolean isMet(Map<String, String> context) {
      if (!CONDITION_KEYS.contains(key)) {
        return true;
      }
      String value = context.get(key);
      boolean ifExists = operator.endsWith("IfExists");
      String baseOperator = ifExists ? operator.substring(0, operator.length() - "IfExists".length()) : operator;
      boolean negated = baseOperator.startsWith("StringNot");
      if (value == null) {
        // A missing key meets a negated operator, and one qualified with IfExists, like in IAM.
        return ifExists || negated;
      }
      return switch (baseOperator) {
        case "StringEquals" -> values.contains(value);
        case "StringNotEquals" -> !values.contains(value);
        case "StringLike" -> values.stream().anyMatch(pattern -> matches(pattern, value));
        case "StringNotLike" -> values.stream().noneMatch(pattern -> matches(pattern, value));
        // An operator that LocalS3 doesn't evaluate.
        default -> true;
      };
    }
  }
}
