package com.robothy.s3.rest.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import com.robothy.netty.http.HttpRequest;
import com.robothy.s3.core.exception.LocalS3Exception;
import com.robothy.s3.core.exception.LocalS3InvalidArgumentException;
import com.robothy.s3.core.exception.S3ErrorCode;
import com.robothy.s3.datatypes.AccessControlPolicy;
import com.robothy.s3.datatypes.Grant;
import com.robothy.s3.datatypes.Owner;
import com.robothy.s3.rest.handler.AccessControlPolicyRequests.Resource;
import com.robothy.s3.rest.utils.XmlUtils;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class AccessControlPolicyRequestsTest {

  private static final Owner OWNER = new Owner("Bob", "002");

  private static final Owner BUCKET_OWNER = new Owner("Alice", "003");

  @Test
  void expandsACannedAclAndKeepsTheOwner() {
    AccessControlPolicy acl = read(Map.of("x-amz-acl", "public-read"), "", Resource.OBJECT);

    assertEquals(OWNER, acl.getOwner());
    assertEquals(List.of("CanonicalUser:002:FULL_CONTROL", "Group:" + AccessControlPolicyRequests.ALL_USERS + ":READ"),
        describe(acl));
  }

  @Test
  void expandsEveryCannedAclOfItsResource() {
    assertEquals(List.of("CanonicalUser:002:FULL_CONTROL"), describe(read(Map.of("x-amz-acl", "private"), "",
        Resource.OBJECT)));
    assertEquals(List.of("CanonicalUser:002:FULL_CONTROL", "Group:" + AccessControlPolicyRequests.ALL_USERS + ":READ",
        "Group:" + AccessControlPolicyRequests.ALL_USERS + ":WRITE"),
        describe(read(Map.of("x-amz-acl", "public-read-write"), "", Resource.BUCKET)));
    assertEquals(List.of("CanonicalUser:002:FULL_CONTROL",
        "CanonicalUser:" + AccessControlPolicyRequests.EC2.getId() + ":READ"),
        describe(read(Map.of("x-amz-acl", "aws-exec-read"), "", Resource.OBJECT)));
    assertEquals(List.of("CanonicalUser:002:FULL_CONTROL",
        "Group:" + AccessControlPolicyRequests.AUTHENTICATED_USERS + ":READ"),
        describe(read(Map.of("x-amz-acl", "authenticated-read"), "", Resource.BUCKET)));
    assertEquals(List.of("CanonicalUser:002:FULL_CONTROL", "CanonicalUser:003:READ"),
        describe(read(Map.of("x-amz-acl", "bucket-owner-read"), "", Resource.OBJECT)));
    assertEquals(List.of("CanonicalUser:002:FULL_CONTROL", "CanonicalUser:003:FULL_CONTROL"),
        describe(read(Map.of("x-amz-acl", "bucket-owner-full-control"), "", Resource.OBJECT)));
    assertEquals(List.of("CanonicalUser:002:FULL_CONTROL", "Group:" + AccessControlPolicyRequests.LOG_DELIVERY + ":WRITE",
        "Group:" + AccessControlPolicyRequests.LOG_DELIVERY + ":READ_ACP"),
        describe(read(Map.of("x-amz-acl", "log-delivery-write"), "", Resource.BUCKET)));
  }

  @Test
  void rejectsACannedAclThatDoesntApplyToTheResource() {
    LocalS3InvalidArgumentException bucketOnly = assertThrows(LocalS3InvalidArgumentException.class,
        () -> read(Map.of("x-amz-acl", "log-delivery-write"), "", Resource.OBJECT));
    assertEquals("x-amz-acl", bucketOnly.getArgumentName());
    assertThrows(LocalS3InvalidArgumentException.class,
        () -> read(Map.of("x-amz-acl", "bucket-owner-read"), "", Resource.BUCKET));
    assertThrows(LocalS3InvalidArgumentException.class,
        () -> read(Map.of("x-amz-acl", "everyone"), "", Resource.OBJECT));
  }

  @Test
  void readsTheGranteesOfGrantHeaders() {
    AccessControlPolicy acl = read(Map.of(
        "x-amz-grant-read", "id=\"111\", uri=\"" + AccessControlPolicyRequests.ALL_USERS + "\"",
        "x-amz-grant-full-control", "emailAddress=\"a,b@example.com\"",
        "x-amz-grant-write-acp", "id=222"), "", Resource.OBJECT);

    assertEquals(OWNER, acl.getOwner());
    assertEquals(List.of("AmazonCustomerByEmail:a,b@example.com:FULL_CONTROL", "CanonicalUser:111:READ",
        "Group:" + AccessControlPolicyRequests.ALL_USERS + ":READ", "CanonicalUser:222:WRITE_ACP"), describe(acl));
  }

  @Test
  void rejectsAMalformedGrantHeader() {
    for (String value : List.of("111", "name=\"111\"", "id=\"\"", "id=\"111", "id=1,,id=2")) {
      LocalS3InvalidArgumentException e = assertThrows(LocalS3InvalidArgumentException.class,
          () -> read(Map.of("x-amz-grant-read", value), "", Resource.OBJECT), value);
      assertEquals("x-amz-grant-read", e.getArgumentName());
    }
  }

  @Test
  void readsTheAclOfTheBody() {
    AccessControlPolicy body = read(Map.of("x-amz-acl", "public-read"), "", Resource.OBJECT);
    body.setOwner(new Owner("Carol", "004"));

    AccessControlPolicy acl = read(Map.of(), XmlUtils.toXml(body), Resource.OBJECT);

    assertEquals(body, acl);
  }

  @Test
  void answersTheErrorsOfAmazonS3() {
    assertError(S3ErrorCode.InvalidRequest,
        () -> read(Map.of("x-amz-acl", "private", "x-amz-grant-read", "id=1"), "", Resource.OBJECT));
    assertError(S3ErrorCode.UnexpectedContent,
        () -> read(Map.of("x-amz-acl", "private"), "<AccessControlPolicy/>", Resource.OBJECT));
    assertError(S3ErrorCode.UnexpectedContent,
        () -> read(Map.of("x-amz-grant-read", "id=1"), "<AccessControlPolicy/>", Resource.BUCKET));
    assertError(S3ErrorCode.MissingSecurityHeader, () -> read(Map.of(), "", Resource.OBJECT));
    assertError(S3ErrorCode.MalformedACLError, () -> read(Map.of(), "<AccessControlPolicy>", Resource.OBJECT));
    assertError(S3ErrorCode.MalformedACLError, () -> read(Map.of(), "not xml", Resource.BUCKET));
  }

  /**
   * The owners are only looked up for an ACL of headers, which keeps them.
   */
  @Test
  void looksTheOwnersUpOnlyForAnAclOfHeaders() {
    AccessControlPolicy body = new AccessControlPolicy();
    body.setOwner(OWNER);
    AccessControlPolicy acl = AccessControlPolicyRequests.read(request(Map.of(), XmlUtils.toXml(body)),
        XmlUtils.createXmlMapper(), Resource.OBJECT,
        () -> {
          throw new AssertionError("The owner isn't needed.");
        },
        () -> {
          throw new AssertionError("The bucket owner isn't needed.");
        });
    assertEquals(OWNER, acl.getOwner());
    assertNull(acl.getGrants());
  }

  private static void assertError(S3ErrorCode code, Executable executable) {
    LocalS3Exception e = assertThrows(LocalS3Exception.class, executable);
    assertEquals(code, e.getS3ErrorCode());
    assertFalse(e instanceof LocalS3InvalidArgumentException);
  }

  private static AccessControlPolicy read(Map<String, String> headers, String body, Resource resource) {
    return AccessControlPolicyRequests.read(request(headers, body), XmlUtils.createXmlMapper(), resource,
        () -> OWNER, () -> BUCKET_OWNER);
  }

  private static HttpRequest request(Map<String, String> headers, String body) {
    return HttpRequest.builder()
        .method(HttpMethod.PUT)
        .uri("/bucket/key?acl")
        .path("/bucket/key")
        .httpVersion(HttpVersion.HTTP_1_1)
        .headers(new HashMap<>(headers))
        .params(new HashMap<>())
        .body(Unpooled.copiedBuffer(body, StandardCharsets.UTF_8))
        .build();
  }

  private static List<String> describe(AccessControlPolicy acl) {
    return acl.getGrants().stream().map(AccessControlPolicyRequestsTest::describe).toList();
  }

  private static String describe(Grant grant) {
    String name = switch (grant.getGrantee().getType()) {
      case "CanonicalUser" -> grant.getGrantee().getId();
      case "Group" -> grant.getGrantee().getUri();
      default -> grant.getGrantee().getEmailAddress();
    };
    return grant.getGrantee().getType() + ":" + name + ":" + grant.getPermission();
  }

}
