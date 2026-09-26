package com.robothy.s3.rest.handler;

import static java.util.Map.entry;

import com.robothy.netty.http.HttpRequest;
import com.robothy.s3.rest.constants.AmzHeaderNames;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Authorizes the S3 requests signed with temporary credentials by the session policy they carry, see
 * {@linkplain SessionPolicy}. A request without a session token, or with one whose credentials have no policy, e.g.
 * of {@code GetSessionToken}, is allowed as before.
 *
 * <p>Each S3 operation is authorized as the IAM action that Amazon S3 authorizes it as, e.g. {@code ListObjectsV2} as
 * {@code s3:ListBucket} on {@code arn:aws:s3:::bucket} and {@code UploadPart} as {@code s3:PutObject} on
 * {@code arn:aws:s3:::bucket/key}; a copy needs {@code s3:GetObject} on its source too, and {@code DeleteObjects}
 * {@code s3:DeleteObject} on each of its objects, which {@linkplain DeleteObjectsController} reports one by one. The
 * requests of S3 Vectors, S3 Tables, the Iceberg REST catalog, STS, KMS and the admin API aren't authorized, and
 * neither is a browser upload, whose credentials are fields of its form.
 */
final class SessionPolicyAuthorizer {

  /**
   * The IAM action of each S3 operation that is authorized by its session policy.
   */
  private static final Map<String, String> ACTIONS = Map.ofEntries(
      entry("AbortMultipartUpload", "s3:AbortMultipartUpload"),
      entry("CompleteMultipartUpload", "s3:PutObject"),
      entry("CopyObject", "s3:PutObject"),
      entry("CreateBucket", "s3:CreateBucket"),
      entry("CreateMultipartUpload", "s3:PutObject"),
      entry("CreateSession", "s3express:CreateSession"),
      entry("DeleteBucket", "s3:DeleteBucket"),
      entry("DeleteBucketAnalyticsConfiguration", "s3:PutAnalyticsConfiguration"),
      entry("DeleteBucketCors", "s3:PutBucketCORS"),
      entry("DeleteBucketEncryption", "s3:PutEncryptionConfiguration"),
      entry("DeleteBucketIntelligentTieringConfiguration", "s3:PutIntelligentTieringConfiguration"),
      entry("DeleteBucketInventoryConfiguration", "s3:PutInventoryConfiguration"),
      entry("DeleteBucketLifecycle", "s3:PutLifecycleConfiguration"),
      entry("DeleteBucketMetricsConfiguration", "s3:PutMetricsConfiguration"),
      entry("DeleteBucketOwnershipControls", "s3:PutBucketOwnershipControls"),
      entry("DeleteBucketPolicy", "s3:DeleteBucketPolicy"),
      entry("DeleteBucketReplication", "s3:PutReplicationConfiguration"),
      entry("DeleteBucketTagging", "s3:PutBucketTagging"),
      entry("DeleteBucketWebsite", "s3:DeleteBucketWebsite"),
      entry("DeleteObject", "s3:DeleteObject"),
      entry("DeleteObjectTagging", "s3:DeleteObjectTagging"),
      entry("DeletePublicAccessBlock", "s3:PutBucketPublicAccessBlock"),
      entry("GetBucketAccelerateConfiguration", "s3:GetAccelerateConfiguration"),
      entry("GetBucketAcl", "s3:GetBucketAcl"),
      entry("GetBucketAnalyticsConfiguration", "s3:GetAnalyticsConfiguration"),
      entry("GetBucketCors", "s3:GetBucketCORS"),
      entry("GetBucketEncryption", "s3:GetEncryptionConfiguration"),
      entry("GetBucketIntelligentTieringConfiguration", "s3:GetIntelligentTieringConfiguration"),
      entry("GetBucketInventoryConfiguration", "s3:GetInventoryConfiguration"),
      entry("GetBucketLifecycleConfiguration", "s3:GetLifecycleConfiguration"),
      entry("GetBucketLocation", "s3:GetBucketLocation"),
      entry("GetBucketLogging", "s3:GetBucketLogging"),
      entry("GetBucketMetricsConfiguration", "s3:GetMetricsConfiguration"),
      entry("GetBucketNotificationConfiguration", "s3:GetBucketNotification"),
      entry("GetBucketOwnershipControls", "s3:GetBucketOwnershipControls"),
      entry("GetBucketPolicy", "s3:GetBucketPolicy"),
      entry("GetBucketPolicyStatus", "s3:GetBucketPolicyStatus"),
      entry("GetBucketReplication", "s3:GetReplicationConfiguration"),
      entry("GetBucketRequestPayment", "s3:GetBucketRequestPayment"),
      entry("GetBucketTagging", "s3:GetBucketTagging"),
      entry("GetBucketVersioning", "s3:GetBucketVersioning"),
      entry("GetBucketWebsite", "s3:GetBucketWebsite"),
      entry("GetObject", "s3:GetObject"),
      entry("GetObjectAcl", "s3:GetObjectAcl"),
      entry("GetObjectAttributes", "s3:GetObjectAttributes"),
      entry("GetObjectLegalHold", "s3:GetObjectLegalHold"),
      entry("GetObjectLockConfiguration", "s3:GetBucketObjectLockConfiguration"),
      entry("GetObjectRetention", "s3:GetObjectRetention"),
      entry("GetObjectTagging", "s3:GetObjectTagging"),
      entry("GetObjectTorrent", "s3:GetObjectTorrent"),
      entry("GetPublicAccessBlock", "s3:GetBucketPublicAccessBlock"),
      entry("HeadBucket", "s3:ListBucket"),
      entry("HeadObject", "s3:GetObject"),
      entry("ListBucketAnalyticsConfigurations", "s3:GetAnalyticsConfiguration"),
      entry("ListBucketIntelligentTieringConfigurations", "s3:GetIntelligentTieringConfiguration"),
      entry("ListBucketInventoryConfigurations", "s3:GetInventoryConfiguration"),
      entry("ListBucketMetricsConfigurations", "s3:GetMetricsConfiguration"),
      entry("ListBuckets", "s3:ListAllMyBuckets"),
      entry(ListDirectoryBucketsController.OPERATION, "s3express:ListAllMyDirectoryBuckets"),
      entry("ListMultipartUploads", "s3:ListBucketMultipartUploads"),
      entry("ListObjects", "s3:ListBucket"),
      entry("ListObjectsV2", "s3:ListBucket"),
      entry("ListObjectVersions", "s3:ListBucketVersions"),
      entry("ListParts", "s3:ListMultipartUploadParts"),
      entry("PutBucketAccelerateConfiguration", "s3:PutAccelerateConfiguration"),
      entry("PutBucketAcl", "s3:PutBucketAcl"),
      entry("PutBucketAnalyticsConfiguration", "s3:PutAnalyticsConfiguration"),
      entry("PutBucketCors", "s3:PutBucketCORS"),
      entry("PutBucketEncryption", "s3:PutEncryptionConfiguration"),
      entry("PutBucketIntelligentTieringConfiguration", "s3:PutIntelligentTieringConfiguration"),
      entry("PutBucketInventoryConfiguration", "s3:PutInventoryConfiguration"),
      entry("PutBucketLifecycleConfiguration", "s3:PutLifecycleConfiguration"),
      entry("PutBucketLogging", "s3:PutBucketLogging"),
      entry("PutBucketMetricsConfiguration", "s3:PutMetricsConfiguration"),
      entry("PutBucketNotificationConfiguration", "s3:PutBucketNotification"),
      entry("PutBucketOwnershipControls", "s3:PutBucketOwnershipControls"),
      entry("PutBucketPolicy", "s3:PutBucketPolicy"),
      entry("PutBucketReplication", "s3:PutReplicationConfiguration"),
      entry("PutBucketRequestPayment", "s3:PutBucketRequestPayment"),
      entry("PutBucketTagging", "s3:PutBucketTagging"),
      entry("PutBucketVersioning", "s3:PutBucketVersioning"),
      entry("PutBucketWebsite", "s3:PutBucketWebsite"),
      entry("PutObject", "s3:PutObject"),
      entry("PutObjectAcl", "s3:PutObjectAcl"),
      entry("PutObjectLegalHold", "s3:PutObjectLegalHold"),
      entry("PutObjectLockConfiguration", "s3:PutBucketObjectLockConfiguration"),
      entry("PutObjectRetention", "s3:PutObjectRetention"),
      entry("PutObjectTagging", "s3:PutObjectTagging"),
      entry("PutPublicAccessBlock", "s3:PutBucketPublicAccessBlock"),
      entry("RenameObject", "s3:PutObject"),
      entry("RestoreObject", "s3:RestoreObject"),
      entry("SelectObjectContent", "s3:GetObject"),
      entry("UploadPart", "s3:PutObject"),
      entry("UploadPartCopy", "s3:PutObject"));

  /**
   * The actions that are authorized as another one when the request names a version, e.g. {@code GetObject} of a
   * version as {@code s3:GetObjectVersion}.
   */
  private static final Map<String, String> VERSION_ACTIONS = Map.of(
      "s3:GetObject", "s3:GetObjectVersion",
      "s3:DeleteObject", "s3:DeleteObjectVersion",
      "s3:GetObjectAcl", "s3:GetObjectVersionAcl",
      "s3:PutObjectAcl", "s3:PutObjectVersionAcl",
      "s3:GetObjectAttributes", "s3:GetObjectVersionAttributes",
      "s3:GetObjectTagging", "s3:GetObjectVersionTagging",
      "s3:PutObjectTagging", "s3:PutObjectVersionTagging",
      "s3:DeleteObjectTagging", "s3:DeleteObjectVersionTagging");

  /**
   * The operations that copy an object, and so read their source.
   */
  private static final Set<String> COPY_OPERATIONS = Set.of("CopyObject", "UploadPartCopy");

  /**
   * The query parameters that are the values of condition keys.
   */
  private static final Map<String, String> CONDITION_PARAMETERS = Map.of(
      "s3:prefix", "prefix",
      "s3:delimiter", "delimiter",
      "s3:max-keys", "max-keys",
      "s3:versionid", "versionId");

  private final SessionCredentialIssuer issuer;

  SessionPolicyAuthorizer(SessionCredentialIssuer issuer) {
    this.issuer = Objects.requireNonNull(issuer);
  }

  /**
   * The session policy of the temporary credentials that a request is signed with.
   *
   * @return the policy; empty if the request carries no session token of LocalS3, or one without a policy.
   */
  Optional<SessionPolicy> policy(HttpRequest request) {
    String sessionToken = request.header(AmzHeaderNames.X_AMZ_SECURITY_TOKEN)
        .or(() -> Optional.ofNullable(request.getParams().get("X-Amz-Security-Token"))
            .flatMap(values -> values.stream().findFirst()))
        .orElse(null);
    SessionCredentialIssuer.Session session = issuer.decode(sessionToken);
    if (session == null || session.policy() == null) {
      return Optional.empty();
    }
    // The policy was validated when the credentials were issued, and the token is authenticated.
    return Optional.of(SessionPolicy.parse(session.policy()));
  }

  /**
   * Whether the session policy of a request, if any, allows the operation that a route matched it to.
   *
   * @param request the request, whose parameters the route has set, e.g. {@code bucket} and {@code key}.
   * @param operation the name of the operation, e.g. {@code PutObject}.
   * @return whether the request is allowed.
   */
  boolean allows(HttpRequest request, String operation) {
    String action = ACTIONS.get(operation);
    if (action == null) {
      return true;
    }
    Optional<SessionPolicy> policy = policy(request);
    if (policy.isEmpty()) {
      return true;
    }
    String bucket = request.parameter("bucket").orElse(null);
    String key = request.parameter("key").map(k -> k.startsWith("/") ? k.substring(1) : k).orElse("");
    Optional<String> versionId = request.parameter("versionId").filter(id -> !id.isEmpty());
    if (versionId.isPresent()) {
      action = VERSION_ACTIONS.getOrDefault(action, action);
    }
    Map<String, String> context = new HashMap<>();
    CONDITION_PARAMETERS.forEach((conditionKey, parameter) ->
        request.parameter(parameter).ifPresent(value -> context.put(conditionKey, value)));
    if (!policy.get().allows(action, resource(bucket, key), context)) {
      return false;
    }
    if (COPY_OPERATIONS.contains(operation)) {
      Optional<CopySource> source = copySource(request);
      // A malformed source is rejected by the operation itself.
      return source.isEmpty() || allowsRead(policy.get(), source.get());
    }
    return true;
  }

  /**
   * Whether a session policy allows to delete an object, or a version of it.
   *
   * @param policy the session policy.
   * @param bucket the bucket of the object.
   * @param key the key of the object.
   * @param versionId the version to delete; {@code null} for the object.
   * @return whether the deletion is allowed.
   */
  static boolean allowsDelete(SessionPolicy policy, String bucket, String key, String versionId) {
    Map<String, String> context = versionId == null ? Map.of() : Map.of("s3:versionid", versionId);
    return policy.allows(versionId == null ? "s3:DeleteObject" : "s3:DeleteObjectVersion", resource(bucket, key),
        context);
  }

  private static boolean allowsRead(SessionPolicy policy, CopySource source) {
    boolean version = source.versionId() != null && !source.versionId().isEmpty();
    Map<String, String> context = version ? Map.of("s3:versionid", source.versionId()) : Map.of();
    return policy.allows(version ? "s3:GetObjectVersion" : "s3:GetObject", resource(source.bucket(), source.key()),
        context);
  }

  private static Optional<CopySource> copySource(HttpRequest request) {
    try {
      return Optional.of(CopySource.of(request));
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  /**
   * The ARN of the resource of a request: of the object, of the bucket, or of all buckets for a request of the service,
   * e.g. {@code ListBuckets}.
   */
  private static String resource(String bucket, String key) {
    if (bucket == null) {
      return "arn:aws:s3:::*";
    }
    return key.isEmpty() ? "arn:aws:s3:::" + bucket : "arn:aws:s3:::" + bucket + "/" + key;
  }

  /**
   * The operations whose requests are authorized, for the tests.
   */
  static List<String> operations() {
    return ACTIONS.keySet().stream().sorted().toList();
  }
}
