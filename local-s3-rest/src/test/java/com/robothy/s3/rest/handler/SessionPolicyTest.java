package com.robothy.s3.rest.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.netty.http.HttpRequestHandler;
import com.robothy.s3.rest.service.ServiceFactory;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class SessionPolicyTest {

  private static final String OBJECT = "arn:aws:s3:::warehouse/tables/t1/data.parquet";

  @Test
  void anActionIsAllowedIfAStatementAllowsItAndNoneDeniesIt() {
    SessionPolicy policy = SessionPolicy.parse("""
        {"Version": "2012-10-17", "Statement": [
          {"Effect": "Allow", "Action": ["s3:Get*", "s3:PutObject"], "Resource": "arn:aws:s3:::warehouse/tables/*"},
          {"Effect": "Deny", "Action": "s3:PutObject", "Resource": "arn:aws:s3:::warehouse/tables/*/metadata/*"}]}
        """);

    assertTrue(policy.allows("s3:GetObject", OBJECT, Map.of()));
    assertTrue(policy.allows("S3:getobject", OBJECT, Map.of()), "Actions are compared ignoring case.");
    assertTrue(policy.allows("s3:PutObject", OBJECT, Map.of()));
    assertFalse(policy.allows("s3:PutObject", "arn:aws:s3:::warehouse/tables/t1/metadata/v1.json", Map.of()));
    assertFalse(policy.allows("s3:DeleteObject", OBJECT, Map.of()), "Nothing allows it.");
    assertFalse(policy.allows("s3:GetObject", "arn:aws:s3:::WAREHOUSE/tables/t1/data.parquet", Map.of()),
        "Resources are compared by case.");
    assertFalse(policy.allows("s3:GetObject", "arn:aws:s3:::warehouse/other", Map.of()));
  }

  @Test
  void aSingleStatementAndNotActionOrNotResourceAreEvaluated() {
    SessionPolicy policy = SessionPolicy.parse("""
        {"Statement": {"Effect": "Allow", "NotAction": "s3:Delete*", "NotResource": "arn:aws:s3:::private/*"}}
        """);

    assertTrue(policy.allows("s3:PutObject", OBJECT, Map.of()));
    assertFalse(policy.allows("s3:DeleteObject", OBJECT, Map.of()));
    assertFalse(policy.allows("s3:PutObject", "arn:aws:s3:::private/key", Map.of()));
  }

  @Test
  void conditionsOnTheKeysOfLocalS3AreEvaluatedAndOthersIgnored() {
    SessionPolicy policy = SessionPolicy.parse("""
        {"Statement": [
          {"Effect": "Allow", "Action": "s3:ListBucket", "Resource": "arn:aws:s3:::warehouse",
           "Condition": {"StringLike": {"s3:prefix": ["tables/t1/*", "tables/t1"]},
                         "StringEqualsIfExists": {"s3:delimiter": "/"},
                         "IpAddress": {"aws:SourceIp": "10.0.0.0/8"},
                         "StringEquals": {"aws:PrincipalTag/team": "data"}}},
          {"Effect": "Deny", "Action": "s3:ListBucket", "Resource": "*",
           "Condition": {"StringNotEquals": {"S3:max-keys": ["10", "1000"]}}}]}
        """);
    String bucket = "arn:aws:s3:::warehouse";

    assertTrue(policy.allows("s3:ListBucket", bucket, Map.of("s3:prefix", "tables/t1/", "s3:max-keys", "1000")));
    assertTrue(policy.allows("s3:ListBucket", bucket,
        Map.of("s3:prefix", "tables/t1", "s3:delimiter", "/", "s3:max-keys", "10")));
    assertFalse(policy.allows("s3:ListBucket", bucket,
        Map.of("s3:prefix", "tables/t1", "s3:delimiter", "-", "s3:max-keys", "10")));
    assertFalse(policy.allows("s3:ListBucket", bucket, Map.of("s3:prefix", "tables/", "s3:max-keys", "10")));
    assertFalse(policy.allows("s3:ListBucket", bucket, Map.of("s3:max-keys", "10")),
        "A missing key doesn't meet StringLike.");
    assertFalse(policy.allows("s3:ListBucket", bucket, Map.of("s3:prefix", "tables/t1/", "s3:max-keys", "5")));
    assertFalse(policy.allows("s3:ListBucket", bucket, Map.of("s3:prefix", "tables/t1/")),
        "A missing key meets a negated operator, like in IAM.");
  }

  @Test
  void wildcardsMatchAnyCharactersOrOne() {
    assertTrue(SessionPolicy.matches("*", ""));
    assertTrue(SessionPolicy.matches("a*c", "abbbc"));
    assertTrue(SessionPolicy.matches("a*c*", "abcxc"));
    assertTrue(SessionPolicy.matches("a?c", "abc"));
    assertFalse(SessionPolicy.matches("a?c", "ac"));
    assertFalse(SessionPolicy.matches("a*c", "abcd"));
    assertTrue(SessionPolicy.matches("arn:aws:s3:::b/t/*", "arn:aws:s3:::b/t/"));
  }

  @Test
  void aDocumentThatIsNoPolicyIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> SessionPolicy.parse("not json"));
    assertThrows(IllegalArgumentException.class, () -> SessionPolicy.parse("[]"));
    assertThrows(IllegalArgumentException.class, () -> SessionPolicy.parse("{\"Version\": \"2012-10-17\"}"));
    assertThrows(IllegalArgumentException.class, () -> SessionPolicy.parse(
        "{\"Statement\": [{\"Effect\": \"Maybe\", \"Action\": \"*\", \"Resource\": \"*\"}]}"));
    assertThrows(IllegalArgumentException.class, () -> SessionPolicy.parse(
        "{\"Statement\": [{\"Effect\": \"Allow\", \"Resource\": \"*\"}]}"));
    assertThrows(IllegalArgumentException.class, () -> SessionPolicy.parse(
        "{\"Statement\": [{\"Effect\": \"Allow\", \"Action\": \"*\", \"NotAction\": \"*\", \"Resource\": \"*\"}]}"));
    assertThrows(IllegalArgumentException.class, () -> SessionPolicy.parse(
        "{\"Statement\": [{\"Effect\": \"Allow\", \"Action\": \"*\"}]}"));
    assertThrows(IllegalArgumentException.class, () -> SessionPolicy.parse(
        "{\"Statement\": [{\"Effect\": \"Allow\", \"Action\": \"*\", \"Resource\": \"*\", \"Condition\": []}]}"));
  }

  @Test
  void anEmptyListOfStatementsAllowsNothing() {
    assertFalse(SessionPolicy.parse("{\"Statement\": []}").allows("s3:GetObject", OBJECT, Map.of()));
  }

  @Test
  void theCompactDocumentHasNoLineFeed() {
    String compact = SessionPolicy.compact("""
        {
          "Statement": [{"Effect": "Allow", "Action": "*", "Resource": "*", "Sid": "a\\nb"}]
        }
        """);
    assertFalse(compact.contains("\n"), compact);
    assertTrue(SessionPolicy.parse(compact).allows("s3:GetObject", OBJECT, Map.of()));
  }

  /**
   * Every operation of the authorizer is one that the router routes, and every S3 operation that the router
   * implements is authorized, except those that authorize themselves: a browser upload, whose credentials are fields of
   * its form, and {@code DeleteObjects}, which authorizes each of its objects.
   */
  @Test
  void everyS3OperationIsAuthorizedAsAnAction() {
    LocalS3Router router = (LocalS3Router) LocalS3RouterFactory.create(
        Mockito.mock(ServiceFactory.class, Mockito.RETURNS_MOCKS), null, null);
    Set<String> implemented = new TreeSet<>();
    Set<String> routed = new TreeSet<>();
    router.routesByOperation().forEach((operation, route) -> {
      routed.add(operation);
      HttpRequestHandler handler = route.getHandler();
      if (!(handler instanceof NotImplementedOperationController)
          && !handler.getClass().getPackageName().endsWith(".handler.s3vectors")) {
        implemented.add(operation);
      }
    });

    Set<String> authorized = new TreeSet<>(SessionPolicyAuthorizer.operations());
    assertTrue(routed.containsAll(authorized), "Not routed: " + difference(authorized, routed));
    Set<String> unauthorized = difference(implemented, authorized);
    unauthorized.removeIf(operation -> operation.startsWith("Admin") || operation.contains("Health")
        || operation.contains("Preflight"));
    assertEquals(Set.of(PostObjectController.OPERATION, "DeleteObjects"), unauthorized);
  }

  private static Set<String> difference(Set<String> a, Set<String> b) {
    Set<String> difference = new TreeSet<>(a);
    difference.removeAll(b);
    return difference;
  }
}
