package com.robothy.s3.core.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.core.model.internal.BucketMetadata;
import com.robothy.s3.datatypes.AccessControlPolicy;
import com.robothy.s3.datatypes.Grant;
import com.robothy.s3.datatypes.Grantee;
import com.robothy.s3.datatypes.PublicAccessBlockConfiguration;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Which buckets {@linkplain BucketPublicAccess} lets an anonymous request read, which is what the static website
 * endpoint serves a bucket by.
 */
class BucketPublicAccessTest {

  private static final String KEY = "index.html";

  private BucketMetadata bucket() {
    BucketMetadata bucket = new BucketMetadata();
    bucket.setBucketName("site");
    return bucket;
  }

  private BucketMetadata withPolicy(String policy) {
    BucketMetadata bucket = bucket();
    bucket.setPolicy(policy);
    return bucket;
  }

  /**
   * A bucket whose ACL grants a permission to a group.
   */
  private BucketMetadata withGrant(String uri, String permission) {
    Grantee grantee = new Grantee();
    grantee.setUri(uri);
    grantee.setType("Group");
    Grant grant = new Grant();
    grant.setGrantee(grantee);
    grant.setPermission(permission);
    BucketMetadata bucket = bucket();
    bucket.setAcl(AccessControlPolicy.builder().grants(List.of(grant)).build());
    return bucket;
  }

  private static String readPolicy(String resource) {
    return "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":\"*\","
        + "\"Action\":\"s3:GetObject\",\"Resource\":\"" + resource + "\"}]}";
  }

  @Test
  void aBucketThatWasNotMadePublicIsNotReadableAnonymously() {
    assertFalse(BucketPublicAccess.allowsAnonymousRead(bucket(), KEY));
  }

  @Nested
  class Acls {

    @Test
    void aReadGrantToAllUsersMakesTheBucketPublic() {
      assertTrue(BucketPublicAccess.allowsAnonymousRead(
          withGrant(BucketPublicAccess.ALL_USERS_GROUP, "READ"), KEY));
      assertTrue(BucketPublicAccess.allowsAnonymousRead(
          withGrant(BucketPublicAccess.ALL_USERS_GROUP, "FULL_CONTROL"), KEY));
    }

    @Test
    void aGrantToAnotherGroupOrOfAnotherPermissionDoesNot() {
      assertFalse(BucketPublicAccess.allowsAnonymousRead(
          withGrant("http://acs.amazonaws.com/groups/global/AuthenticatedUsers", "READ"), KEY),
          "An authenticated request is not an anonymous one.");
      assertFalse(BucketPublicAccess.allowsAnonymousRead(
          withGrant(BucketPublicAccess.ALL_USERS_GROUP, "WRITE"), KEY),
          "Writing the bucket does not make it readable.");
    }

    @Test
    void ignorePublicAclsTakesThePublicGrantAway() {
      BucketMetadata bucket = withGrant(BucketPublicAccess.ALL_USERS_GROUP, "READ");
      bucket.setPublicAccessBlock(PublicAccessBlockConfiguration.builder().ignorePublicAcls(true).build());

      assertFalse(BucketPublicAccess.allowsAnonymousRead(bucket, KEY));
    }
  }

  @Nested
  class Policies {

    @Test
    void aPolicyThatAllowsGetObjectToEveryPrincipalMakesTheKeyPublic() {
      assertTrue(BucketPublicAccess.allowsAnonymousRead(withPolicy(readPolicy("arn:aws:s3:::site/*")), KEY));
    }

    @Test
    void aPolicyOnlyCoversTheKeysItsResourcesName() {
      BucketMetadata bucket = withPolicy(readPolicy("arn:aws:s3:::site/public/*"));

      assertTrue(BucketPublicAccess.allowsAnonymousRead(bucket, "public/index.html"));
      assertFalse(BucketPublicAccess.allowsAnonymousRead(bucket, "private/secrets.txt"));
    }

    @Test
    void aPolicyOfAnotherBucketDoesNotApply() {
      assertFalse(BucketPublicAccess.allowsAnonymousRead(withPolicy(readPolicy("arn:aws:s3:::other/*")), KEY));
    }

    @Test
    void aPolicyThatNamesAnotherPrincipalOrActionDoesNotMakeItPublic() {
      assertFalse(BucketPublicAccess.allowsAnonymousRead(withPolicy(
          "{\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"AWS\":\"arn:aws:iam::1:root\"},"
              + "\"Action\":\"s3:GetObject\",\"Resource\":\"arn:aws:s3:::site/*\"}]}"), KEY));
      assertFalse(BucketPublicAccess.allowsAnonymousRead(withPolicy(
          "{\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":\"*\","
              + "\"Action\":\"s3:PutObject\",\"Resource\":\"arn:aws:s3:::site/*\"}]}"), KEY));
    }

    @Test
    void thePrincipalMayBeWrittenAsAnAwsObject() {
      assertTrue(BucketPublicAccess.allowsAnonymousRead(withPolicy(
          "{\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"AWS\":[\"*\"]},"
              + "\"Action\":[\"s3:GetObject\"],\"Resource\":[\"arn:aws:s3:::site/*\"]}]}"), KEY));
    }

    @Test
    void anExplicitDenyWinsOverAnAllow() {
      assertFalse(BucketPublicAccess.allowsAnonymousRead(withPolicy(
          "{\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":\"*\",\"Action\":\"s3:GetObject\","
              + "\"Resource\":\"arn:aws:s3:::site/*\"},"
              + "{\"Effect\":\"Deny\",\"Principal\":\"*\",\"Action\":\"s3:*\","
              + "\"Resource\":\"arn:aws:s3:::site/*\"}]}"), KEY));
    }

    @Test
    void aStatementWithAConditionIsNotEvaluated() {
      assertFalse(BucketPublicAccess.allowsAnonymousRead(withPolicy(
          "{\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":\"*\",\"Action\":\"s3:GetObject\","
              + "\"Resource\":\"arn:aws:s3:::site/*\","
              + "\"Condition\":{\"StringLike\":{\"aws:Referer\":\"https://example.com/*\"}}}]}"), KEY),
          "LocalS3 evaluates no conditions, so a narrowed policy must not open the bucket wider than it says.");
    }

    @Test
    void aPolicyThatIsNotJsonMakesNothingPublic() {
      assertFalse(BucketPublicAccess.allowsAnonymousRead(withPolicy("not json"), KEY));
    }

    @Test
    void blockPublicPolicyAndRestrictPublicBucketsTakeThePolicyAway() {
      BucketMetadata blocked = withPolicy(readPolicy("arn:aws:s3:::site/*"));
      blocked.setPublicAccessBlock(PublicAccessBlockConfiguration.builder().blockPublicPolicy(true).build());
      assertFalse(BucketPublicAccess.allowsAnonymousRead(blocked, KEY));

      BucketMetadata restricted = withPolicy(readPolicy("arn:aws:s3:::site/*"));
      restricted.setPublicAccessBlock(
          PublicAccessBlockConfiguration.builder().restrictPublicBuckets(true).build());
      assertFalse(BucketPublicAccess.allowsAnonymousRead(restricted, KEY));
    }
  }

}
