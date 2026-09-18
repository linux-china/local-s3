package com.robothy.s3.core.util;

import com.robothy.s3.core.model.internal.BucketMetadata;
import com.robothy.s3.datatypes.AccessControlPolicy;
import com.robothy.s3.datatypes.Grant;
import com.robothy.s3.datatypes.Grantee;
import com.robothy.s3.datatypes.PublicAccessBlockConfiguration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Decides whether a bucket lets anonymous requests read an object, which is what the
 * <a href="https://docs.aws.amazon.com/AmazonS3/latest/userguide/WebsiteAccessPermissionsReqd.html">static website
 * endpoint</a> serves an object by: a request that carries no credentials is answered only from a bucket that was
 * made public, by its ACL or by its bucket policy.
 *
 * <p>Amazon S3 evaluates the policies of the account, the bucket and the object together; LocalS3 has no accounts, so
 * only the bucket is evaluated, with the rules that decide a public bucket:
 *
 * <ul>
 *   <li>a bucket policy that allows {@code s3:GetObject} of the object to every principal makes it readable, and one
 *       that denies it to every principal makes it unreadable whatever else allows it;</li>
 *   <li>a bucket ACL that grants {@code READ} to the {@code AllUsers} group makes every object of the bucket
 *       readable;</li>
 *   <li>the {@linkplain PublicAccessBlockConfiguration public access block} of the bucket takes both away:
 *       {@code IgnorePublicAcls} the ACL, {@code BlockPublicPolicy} the policy, and {@code RestrictPublicBuckets}
 *       either of them.</li>
 * </ul>
 */
public final class BucketPublicAccess {

  /**
   * The group of every request, whatever its credentials, which a bucket that grants it {@code READ} is public by.
   */
  public static final String ALL_USERS_GROUP = "http://acs.amazonaws.com/groups/global/AllUsers";

  /**
   * The permissions of a grant that let the grantee read the objects of the bucket.
   */
  private static final List<String> READ_PERMISSIONS = List.of("READ", "FULL_CONTROL");

  /**
   * The actions of a statement that cover reading an object.
   */
  private static final List<String> READ_ACTIONS = List.of("s3:getobject", "s3:getobjectversion", "s3:*", "*");

  private static final JsonMapper JSON = JsonMapper.builderWithJackson2Defaults().build();

  private BucketPublicAccess() {
  }

  /**
   * Whether an anonymous request may read an object of a bucket.
   *
   * @param bucket the bucket.
   * @param key the object key, which the resources of the bucket policy are matched against; {@code null} for a
   *     request that addresses the bucket itself, which only a policy of the whole bucket allows.
   * @return {@code true} if the bucket is public for that key.
   */
  public static boolean allowsAnonymousRead(BucketMetadata bucket, String key) {
    Objects.requireNonNull(bucket, "bucket");
    PublicAccessBlockConfiguration block = bucket.getPublicAccessBlock().orElse(null);
    boolean restricted = isSet(block, PublicAccessBlockConfiguration::getRestrictPublicBuckets);
    if (restricted) {
      return false;
    }

    PolicyDecision policy = isSet(block, PublicAccessBlockConfiguration::getBlockPublicPolicy)
        ? PolicyDecision.NONE
        : bucket.getPolicy().map(document -> evaluatePolicy(document, bucket.getBucketName(), key))
            .orElse(PolicyDecision.NONE);
    if (policy == PolicyDecision.DENY) {
      // An explicit deny wins over every allow, like it does on Amazon S3.
      return false;
    }
    if (policy == PolicyDecision.ALLOW) {
      return true;
    }
    return !isSet(block, PublicAccessBlockConfiguration::getIgnorePublicAcls) && grantsPublicRead(bucket.getAcl());
  }

  private static boolean isSet(PublicAccessBlockConfiguration block,
                               Function<PublicAccessBlockConfiguration, Boolean> setting) {
    return block != null && Boolean.TRUE.equals(setting.apply(block));
  }

  /**
   * Whether the ACL of a bucket grants a read permission to the {@linkplain #ALL_USERS_GROUP AllUsers} group.
   */
  private static boolean grantsPublicRead(Optional<AccessControlPolicy> acl) {
    return acl.map(AccessControlPolicy::getGrants).orElseGet(List::of).stream()
        .filter(grant -> grant != null && READ_PERMISSIONS.contains(permissionOf(grant)))
        .map(Grant::getGrantee)
        .anyMatch(grantee -> grantee != null && ALL_USERS_GROUP.equals(uriOf(grantee)));
  }

  private static String permissionOf(Grant grant) {
    return grant.getPermission() == null ? null : grant.getPermission().trim().toUpperCase(Locale.ROOT);
  }

  private static String uriOf(Grantee grantee) {
    return grantee.getUri() == null ? null : grantee.getUri().trim();
  }

  /**
   * What the bucket policy says about an anonymous read of a key.
   */
  private enum PolicyDecision {
    /** The policy explicitly denies it. */
    DENY,
    /** The policy allows it to every principal. */
    ALLOW,
    /** The policy says nothing about it. */
    NONE
  }

  /**
   * Evaluate the statements of a bucket policy against an anonymous {@code s3:GetObject} of a key. A policy that
   * can't be read as JSON says nothing, so that a document LocalS3 stored without checking doesn't make a bucket
   * public by accident.
   */
  private static PolicyDecision evaluatePolicy(String document, String bucketName, String key) {
    JsonNode policy;
    try {
      policy = JSON.readTree(document);
    } catch (JacksonException e) {
      return PolicyDecision.NONE;
    }

    boolean allowed = false;
    for (JsonNode statement : asList(policy.path("Statement"))) {
      if (!matchesRead(statement, bucketName, key)) {
        continue;
      }
      if ("Deny".equalsIgnoreCase(statement.path("Effect").asString(""))) {
        return PolicyDecision.DENY;
      }
      if ("Allow".equalsIgnoreCase(statement.path("Effect").asString(""))) {
        allowed = true;
      }
    }
    return allowed ? PolicyDecision.ALLOW : PolicyDecision.NONE;
  }

  /**
   * Whether a statement applies to an anonymous {@code s3:GetObject} of a key: it must name every principal, an
   * action that covers reading an object, and a resource that the key matches.
   *
   * <p>A statement with a {@code Condition} is skipped rather than guessed at: LocalS3 evaluates no conditions, and a
   * statement that is meant to narrow a public policy, e.g. one that requires a referer, would otherwise open the
   * bucket wider than the policy says.
   */
  private static boolean matchesRead(JsonNode statement, String bucketName, String key) {
    return !statement.has("Condition")
        && !statement.has("NotPrincipal")
        && !statement.has("NotAction")
        && !statement.has("NotResource")
        && matchesEveryPrincipal(statement.path("Principal"))
        && matchesReadAction(statement.path("Action"))
        && matchesResource(statement.path("Resource"), bucketName, key);
  }

  /**
   * Whether a {@code Principal} names every principal: {@code "*"}, or {@code {"AWS": "*"}}.
   */
  private static boolean matchesEveryPrincipal(JsonNode principal) {
    if (principal.isObject()) {
      return asList(principal.path("AWS")).stream().anyMatch(aws -> "*".equals(aws.asString("")));
    }
    return asList(principal).stream().anyMatch(value -> "*".equals(value.asString("")));
  }

  private static boolean matchesReadAction(JsonNode action) {
    return asList(action).stream()
        .map(value -> value.asString("").trim().toLowerCase(Locale.ROOT))
        .anyMatch(READ_ACTIONS::contains);
  }

  /**
   * Whether a {@code Resource} covers the object, which its ARN is matched against. A request that addresses the
   * bucket itself, i.e. one without a key, is covered only by a resource that names every object of the bucket.
   */
  private static boolean matchesResource(JsonNode resource, String bucketName, String key) {
    String arn = "arn:aws:s3:::" + bucketName + "/" + Objects.toString(key, "");
    return asList(resource).stream()
        .map(value -> value.asString(""))
        .anyMatch(pattern -> arnPattern(pattern).matcher(arn).matches());
  }

  /**
   * The ARN pattern of a policy as a regular expression: {@code *} stands for any characters and {@code ?} for one,
   * and everything else is matched literally.
   */
  private static Pattern arnPattern(String pattern) {
    StringBuilder regex = new StringBuilder();
    StringBuilder literal = new StringBuilder();
    for (int i = 0; i < pattern.length(); i++) {
      char c = pattern.charAt(i);
      if (c == '*' || c == '?') {
        if (literal.length() > 0) {
          regex.append(Pattern.quote(literal.toString()));
          literal.setLength(0);
        }
        regex.append(c == '*' ? ".*" : ".");
      } else {
        literal.append(c);
      }
    }
    if (literal.length() > 0) {
      regex.append(Pattern.quote(literal.toString()));
    }
    return Pattern.compile(regex.toString(), Pattern.DOTALL);
  }

  /**
   * A policy element that is either one value or an array of them, as a list of values.
   */
  private static List<JsonNode> asList(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return List.of();
    }
    if (node.isArray()) {
      List<JsonNode> values = new ArrayList<>(node.size());
      node.forEach(values::add);
      return values;
    }
    return List.of(node);
  }

}
