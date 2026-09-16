package com.robothy.s3.rest.handler;

import com.robothy.netty.http.HttpRequest;
import com.robothy.s3.core.exception.LocalS3InvalidArgumentException;
import com.robothy.s3.core.exception.LocalS3RequestException;
import com.robothy.s3.core.exception.S3ErrorCode;
import com.robothy.s3.datatypes.AccessControlPolicy;
import com.robothy.s3.datatypes.Grant;
import com.robothy.s3.datatypes.Grantee;
import com.robothy.s3.datatypes.Owner;
import com.robothy.s3.rest.netty.RequestBodies;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import tools.jackson.core.JacksonException;
import tools.jackson.dataformat.xml.XmlMapper;

/**
 * Reads the ACL of a {@code PutBucketAcl} or {@code PutObjectAcl} request, which Amazon S3 accepts in one of three
 * forms, and only one:
 * <ul>
 *   <li>a canned ACL, the {@code x-amz-acl} header, e.g. {@code public-read}, which grants the owner
 *   {@code FULL_CONTROL} and others what the canned ACL names;</li>
 *   <li>grant headers, {@code x-amz-grant-read}, {@code x-amz-grant-write}, {@code x-amz-grant-read-acp},
 *   {@code x-amz-grant-write-acp} and {@code x-amz-grant-full-control}, each naming grantees as
 *   {@code id="..."}, {@code uri="..."} or {@code emailAddress="..."}, separated by commas;</li>
 *   <li>an {@code AccessControlPolicy} document in the body.</li>
 * </ul>
 * An ACL of headers keeps the owner of the resource, since an ACL doesn't change who owns a resource.
 *
 * <p>The errors are those of Amazon S3: {@code InvalidRequest} for a canned ACL together with grant headers,
 * {@code UnexpectedContent} for headers together with a body, {@code MissingSecurityHeader} for a request without any
 * ACL, {@code InvalidArgument} for an unknown canned ACL or a malformed grant header, and {@code MalformedACLError}
 * for a body that isn't an ACL.
 */
final class AccessControlPolicyRequests {

  static final String CANNED_ACL_HEADER = "x-amz-acl";

  static final String ALL_USERS = "http://acs.amazonaws.com/groups/global/AllUsers";

  static final String AUTHENTICATED_USERS = "http://acs.amazonaws.com/groups/global/AuthenticatedUsers";

  static final String LOG_DELIVERY = "http://acs.amazonaws.com/groups/s3/LogDelivery";

  /**
   * The canonical user of Amazon EC2, which {@code aws-exec-read} grants {@code READ}.
   */
  static final Owner EC2 = new Owner("za-team", "6aa5a366c34c1cbe25dc49211496e913e0351eb0e8c37aa3477e40942ec6b97c");

  static final String CANONICAL_USER = "CanonicalUser";

  static final String GROUP = "Group";

  static final String CUSTOMER_BY_EMAIL = "AmazonCustomerByEmail";

  /**
   * The grant headers, and the permission each of them grants, in the order their grants are listed.
   */
  private static final Map<String, String> GRANT_HEADERS = grantHeaders();

  /**
   * What an ACL is put on, which decides the canned ACLs that apply.
   */
  enum Resource {

    BUCKET(Set.of("private", "public-read", "public-read-write", "aws-exec-read", "authenticated-read",
        "log-delivery-write")),

    OBJECT(Set.of("private", "public-read", "public-read-write", "aws-exec-read", "authenticated-read",
        "bucket-owner-read", "bucket-owner-full-control"));

    private final Set<String> cannedAcls;

    Resource(Set<String> cannedAcls) {
      this.cannedAcls = cannedAcls;
    }
  }

  private AccessControlPolicyRequests() {
  }

  /**
   * Read the ACL of a request.
   *
   * @param request the request.
   * @param xmlMapper reads an ACL in the body.
   * @param resource what the ACL is put on.
   * @param owner the owner of the resource, which an ACL of headers keeps.
   * @param bucketOwner the owner of the bucket of the resource, which {@code bucket-owner-read} and
   *     {@code bucket-owner-full-control} grant to.
   * @return the ACL.
   */
  static AccessControlPolicy read(HttpRequest request, XmlMapper xmlMapper, Resource resource,
                                  Supplier<Owner> owner, Supplier<Owner> bucketOwner) {
    Optional<String> cannedAcl = request.header(CANNED_ACL_HEADER).map(String::trim);
    Map<String, String> grantHeaders = new LinkedHashMap<>();
    GRANT_HEADERS.keySet().forEach(name -> request.header(name).ifPresent(value -> grantHeaders.put(name, value)));
    boolean hasBody = RequestBodies.length(request.getBody()) > 0;

    if (cannedAcl.isPresent() && !grantHeaders.isEmpty()) {
      throw new LocalS3RequestException(S3ErrorCode.InvalidRequest,
          "Specifying both Canned ACLs and Header Grants is not allowed");
    }
    if ((cannedAcl.isPresent() || !grantHeaders.isEmpty()) && hasBody) {
      throw new LocalS3RequestException(S3ErrorCode.UnexpectedContent);
    }
    if (cannedAcl.isPresent()) {
      return cannedAcl(cannedAcl.get(), resource, owner.get(), bucketOwner);
    }
    if (!grantHeaders.isEmpty()) {
      return grantHeaders(grantHeaders, owner.get());
    }
    if (!hasBody) {
      throw new LocalS3RequestException(S3ErrorCode.MissingSecurityHeader);
    }
    return body(request, xmlMapper);
  }

  private static AccessControlPolicy cannedAcl(String cannedAcl, Resource resource, Owner owner,
                                               Supplier<Owner> bucketOwner) {
    if (!resource.cannedAcls.contains(cannedAcl)) {
      throw new LocalS3InvalidArgumentException(CANNED_ACL_HEADER, cannedAcl);
    }
    List<Grant> grants = new ArrayList<>();
    grants.add(grant(canonicalUser(owner), "FULL_CONTROL"));
    switch (cannedAcl) {
      case "public-read" -> grants.add(grant(group(ALL_USERS), "READ"));
      case "public-read-write" -> {
        grants.add(grant(group(ALL_USERS), "READ"));
        grants.add(grant(group(ALL_USERS), "WRITE"));
      }
      case "aws-exec-read" -> grants.add(grant(canonicalUser(EC2), "READ"));
      case "authenticated-read" -> grants.add(grant(group(AUTHENTICATED_USERS), "READ"));
      case "bucket-owner-read" -> grants.add(grant(canonicalUser(bucketOwner.get()), "READ"));
      case "bucket-owner-full-control" -> grants.add(grant(canonicalUser(bucketOwner.get()), "FULL_CONTROL"));
      case "log-delivery-write" -> {
        grants.add(grant(group(LOG_DELIVERY), "WRITE"));
        grants.add(grant(group(LOG_DELIVERY), "READ_ACP"));
      }
      default -> {
        // private grants the owner only.
      }
    }
    return AccessControlPolicy.builder().owner(owner).grants(grants).build();
  }

  private static AccessControlPolicy grantHeaders(Map<String, String> headers, Owner owner) {
    List<Grant> grants = new ArrayList<>();
    headers.forEach((name, value) -> {
      String permission = GRANT_HEADERS.get(name);
      for (String grantee : splitGrantees(name, value)) {
        grants.add(grant(grantee(name, grantee), permission));
      }
    });
    return AccessControlPolicy.builder().owner(owner).grants(grants).build();
  }

  /**
   * Split the value of a grant header at the commas that separate its grantees, but not at those within quotes.
   */
  private static List<String> splitGrantees(String header, String value) {
    List<String> grantees = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean quoted = false;
    for (char c : value.toCharArray()) {
      if (c == '"') {
        quoted = !quoted;
      }
      if (c == ',' && !quoted) {
        grantees.add(current.toString().trim());
        current.setLength(0);
      } else {
        current.append(c);
      }
    }
    grantees.add(current.toString().trim());
    if (quoted || grantees.stream().anyMatch(String::isEmpty)) {
      throw new LocalS3InvalidArgumentException(header, value);
    }
    return grantees;
  }

  /**
   * A grantee of a grant header, {@code id="..."}, {@code uri="..."} or {@code emailAddress="..."}.
   */
  private static Grantee grantee(String header, String grantee) {
    int separator = grantee.indexOf('=');
    if (separator <= 0) {
      throw new LocalS3InvalidArgumentException(header, grantee);
    }
    String type = grantee.substring(0, separator).trim().toLowerCase(Locale.ROOT);
    String value = grantee.substring(separator + 1).trim();
    if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
      value = value.substring(1, value.length() - 1);
    }
    if (value.isEmpty()) {
      throw new LocalS3InvalidArgumentException(header, grantee);
    }
    Grantee result = new Grantee();
    switch (type) {
      case "id" -> {
        result.setId(value);
        result.setType(CANONICAL_USER);
      }
      case "uri" -> {
        result.setUri(value);
        result.setType(GROUP);
      }
      case "emailaddress" -> {
        result.setEmailAddress(value);
        result.setType(CUSTOMER_BY_EMAIL);
      }
      default -> throw new LocalS3InvalidArgumentException(header, grantee);
    }
    return result;
  }

  private static AccessControlPolicy body(HttpRequest request, XmlMapper xmlMapper) {
    try (InputStream in = RequestBodies.inputStream(request.getBody())) {
      AccessControlPolicy acl = xmlMapper.readValue(in, AccessControlPolicy.class);
      if (Objects.isNull(acl)) {
        throw new LocalS3RequestException(S3ErrorCode.MalformedACLError);
      }
      return acl;
    } catch (JacksonException e) {
      throw new LocalS3RequestException(S3ErrorCode.MalformedACLError);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static Grantee canonicalUser(Owner owner) {
    Grantee grantee = new Grantee();
    grantee.setId(owner.getId());
    grantee.setDisplayName(owner.getDisplayName());
    grantee.setType(CANONICAL_USER);
    return grantee;
  }

  private static Grantee group(String uri) {
    Grantee grantee = new Grantee();
    grantee.setUri(uri);
    grantee.setType(GROUP);
    return grantee;
  }

  private static Grant grant(Grantee grantee, String permission) {
    Grant grant = new Grant();
    grant.setGrantee(grantee);
    grant.setPermission(permission);
    return grant;
  }

  private static Map<String, String> grantHeaders() {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("x-amz-grant-full-control", "FULL_CONTROL");
    headers.put("x-amz-grant-read", "READ");
    headers.put("x-amz-grant-read-acp", "READ_ACP");
    headers.put("x-amz-grant-write", "WRITE");
    headers.put("x-amz-grant-write-acp", "WRITE_ACP");
    return headers;
  }

}
