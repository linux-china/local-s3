package com.robothy.s3.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.rest.LocalS3;
import com.robothy.s3.rest.LocalS3Builder;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.auth.signer.AwsS3V4Signer;
import software.amazon.awssdk.auth.signer.S3SignerExecutionAttribute;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.DeletedObject;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.AssumeRoleResponse;
import software.amazon.awssdk.services.sts.model.Credentials;
import software.amazon.awssdk.services.sts.model.GetCallerIdentityResponse;
import software.amazon.awssdk.services.sts.model.StsException;

/**
 * The STS endpoint of LocalS3 issues temporary credentials the way an Iceberg REST catalog, e.g. Apache Polaris or
 * Lakekeeper, gets them from STS with {@code AssumeRole} to vend them to DuckDB, PyIceberg or Spark, which access
 * LocalS3 with them.
 */
class StsIntegrationTest {

  private static final String ACCESS_KEY_ID = "local-access-key";

  private static final String SECRET_ACCESS_KEY = "local-secret-access-key";

  private static final String ROLE_ARN = "arn:aws:iam::123456789012:role/lakehouse-reader";

  private static final String ALLOW_ALL = """
      {"Version": "2012-10-17", "Statement": [{"Effect": "Allow", "Action": "s3:*", "Resource": "*"}]}
      """;

  /**
   * The session policy that a catalog like Lakekeeper or Apache Polaris scopes the credentials of a table with.
   */
  private static final String TABLE_POLICY = """
      {
        "Version": "2012-10-17",
        "Statement": [
          {
            "Sid": "TableAccess",
            "Effect": "Allow",
            "Action": ["s3:GetObject", "s3:GetObjectVersion", "s3:PutObject", "s3:DeleteObject",
                       "s3:AbortMultipartUpload", "s3:ListMultipartUploadParts"],
            "Resource": ["arn:aws:s3:::warehouse/tables/t1/*"]
          },
          {
            "Sid": "ListBucketForFolder",
            "Effect": "Allow",
            "Action": "s3:ListBucket",
            "Resource": "arn:aws:s3:::warehouse",
            "Condition": {"StringLike": {"s3:prefix": ["tables/t1/*"]}}
          },
          {
            "Effect": "Allow",
            "Action": "s3:GetBucketLocation",
            "Resource": "arn:aws:s3:::warehouse"
          }
        ]
      }
      """;

  private final List<AutoCloseable> resources = new ArrayList<>();

  @AfterEach
  void close() throws Exception {
    for (AutoCloseable resource : resources.reversed()) {
      resource.close();
    }
  }

  @Test
  void theCredentialsOfAnAssumedRoleAccessLocalS3() throws Exception {
    URI endpoint = start(ACCESS_KEY_ID, SECRET_ACCESS_KEY);
    AssumeRoleResponse assumed = sts(endpoint, AwsBasicCredentials.create(ACCESS_KEY_ID, SECRET_ACCESS_KEY))
        .assumeRole(b -> b.roleArn(ROLE_ARN).roleSessionName("polaris").durationSeconds(900)
            .policy(ALLOW_ALL));

    assertEquals("arn:aws:sts::123456789012:assumed-role/lakehouse-reader/polaris",
        assumed.assumedRoleUser().arn());
    assertTrue(assumed.assumedRoleUser().assumedRoleId().endsWith(":polaris"));
    Credentials credentials = assumed.credentials();
    assertTrue(credentials.accessKeyId().startsWith("ASIA"));
    Duration validity = Duration.between(Instant.now(), credentials.expiration());
    assertTrue(validity.compareTo(Duration.ofMinutes(14)) > 0 && validity.compareTo(Duration.ofMinutes(15)) <= 0,
        validity::toString);

    AwsSessionCredentials session = session(credentials);
    S3Client s3 = s3(endpoint, session);
    s3.createBucket(b -> b.bucket("warehouse"));
    s3.putObject(b -> b.bucket("warehouse").key("a.txt"), RequestBody.fromString("Hello"));
    // Signed chunk by chunk, with the signing key of the temporary credentials.
    s3.putObject(b -> b.bucket("warehouse").key("b.txt").overrideConfiguration(configuration -> {
      configuration.signer(AwsS3V4Signer.create());
      configuration.executionAttributes()
          .putAttribute(S3SignerExecutionAttribute.ENABLE_PAYLOAD_SIGNING, true)
          .putAttribute(S3SignerExecutionAttribute.ENABLE_CHUNKED_ENCODING, true);
    }), RequestBody.fromString("Signed stream"));
    assertEquals("Hello", s3.getObjectAsBytes(b -> b.bucket("warehouse").key("a.txt")).asUtf8String());
    assertEquals(List.of("a.txt", "b.txt"),
        s3.listObjectsV2(b -> b.bucket("warehouse")).contents().stream().map(S3Object::key).toList());

    // A presigned URL carries the session token in X-Amz-Security-Token.
    try (S3Presigner presigner = S3Presigner.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
        .credentialsProvider(StaticCredentialsProvider.create(session))
        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build()).build()) {
      URI url = presigner.presignGetObject(b -> b.signatureDuration(Duration.ofMinutes(5))
          .getObjectRequest(r -> r.bucket("warehouse").key("b.txt"))).url().toURI();
      assertTrue(url.getQuery().contains("X-Amz-Security-Token="));
      HttpResponse<String> response = HttpClient.newHttpClient()
          .send(HttpRequest.newBuilder(url).GET().build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      assertEquals("Signed stream", response.body());
    }

    GetCallerIdentityResponse identity = sts(endpoint, session).getCallerIdentity();
    assertEquals(assumed.assumedRoleUser().arn(), identity.arn());
    assertEquals("123456789012", identity.account());
  }

  @Test
  void aForgedOrForeignTokenIsRejected() {
    URI endpoint = start(ACCESS_KEY_ID, SECRET_ACCESS_KEY);
    StsClient sts = sts(endpoint, AwsBasicCredentials.create(ACCESS_KEY_ID, SECRET_ACCESS_KEY));
    Credentials credentials = sts.assumeRole(b -> b.roleArn(ROLE_ARN).roleSessionName("one")).credentials();
    Credentials other = sts.assumeRole(b -> b.roleArn(ROLE_ARN).roleSessionName("two")).credentials();
    s3(endpoint, session(credentials)).createBucket(b -> b.bucket("bucket"));

    AwsSessionCredentials forged = AwsSessionCredentials.create(credentials.accessKeyId(),
        credentials.secretAccessKey(), credentials.sessionToken().substring(1));
    S3Exception forgedToken = assertThrows(S3Exception.class, () -> s3(endpoint, forged).listBuckets());
    assertEquals("InvalidToken", forgedToken.awsErrorDetails().errorCode());
    assertEquals(400, forgedToken.statusCode());

    AwsSessionCredentials foreign = AwsSessionCredentials.create(credentials.accessKeyId(),
        credentials.secretAccessKey(), other.sessionToken());
    S3Exception foreignToken = assertThrows(S3Exception.class, () -> s3(endpoint, foreign).listBuckets());
    assertEquals("InvalidToken", foreignToken.awsErrorDetails().errorCode());

    AwsSessionCredentials wrongSecret = AwsSessionCredentials.create(credentials.accessKeyId(),
        other.secretAccessKey(), credentials.sessionToken());
    S3Exception signature = assertThrows(S3Exception.class, () -> s3(endpoint, wrongSecret).listBuckets());
    assertEquals("SignatureDoesNotMatch", signature.awsErrorDetails().errorCode());
  }

  /**
   * Nothing is stored: a LocalS3 with the same credentials, e.g. the same one after a restart, accepts the temporary
   * credentials, one with other credentials doesn't.
   */
  @Test
  void theCredentialsAreValidForEveryLocalS3WithTheSameSecret() {
    URI issuing = start(ACCESS_KEY_ID, SECRET_ACCESS_KEY);
    AwsSessionCredentials session = session(sts(issuing, AwsBasicCredentials.create(ACCESS_KEY_ID, SECRET_ACCESS_KEY))
        .assumeRole(b -> b.roleArn(ROLE_ARN).roleSessionName("restart")).credentials());

    URI restarted = start(ACCESS_KEY_ID, SECRET_ACCESS_KEY);
    s3(restarted, session).createBucket(b -> b.bucket("bucket"));
    assertEquals(1, s3(restarted, session).listBuckets().buckets().size());

    URI another = start(ACCESS_KEY_ID, "another-secret-access-key");
    S3Exception rejected = assertThrows(S3Exception.class, () -> s3(another, session).listBuckets());
    assertEquals("InvalidToken", rejected.awsErrorDetails().errorCode());
  }

  @Test
  void getSessionTokenAndRoleChainingFollowTheRulesOfSts() {
    URI endpoint = start(ACCESS_KEY_ID, SECRET_ACCESS_KEY);
    StsClient sts = sts(endpoint, AwsBasicCredentials.create(ACCESS_KEY_ID, SECRET_ACCESS_KEY));

    Credentials sessionToken = sts.getSessionToken().credentials();
    assertTrue(Duration.between(Instant.now(), sessionToken.expiration()).compareTo(Duration.ofHours(11)) > 0);
    s3(endpoint, session(sessionToken)).createBucket(b -> b.bucket("bucket"));
    StsException nested = assertThrows(StsException.class,
        () -> sts(endpoint, session(sessionToken)).getSessionToken());
    assertEquals("AccessDenied", nested.awsErrorDetails().errorCode());

    StsClient assumed = sts(endpoint, session(sts.assumeRole(b -> b.roleArn(ROLE_ARN).roleSessionName("first"))
        .credentials()));
    AssumeRoleResponse chained = assumed.assumeRole(b -> b.roleArn("arn:aws:iam::123456789012:role/writer")
        .roleSessionName("second").durationSeconds(3600));
    assertEquals("arn:aws:sts::123456789012:assumed-role/writer/second", chained.assumedRoleUser().arn());
    StsException tooLong = assertThrows(StsException.class, () -> assumed.assumeRole(b -> b.roleArn(ROLE_ARN)
        .roleSessionName("second").durationSeconds(7200)));
    assertEquals("ValidationError", tooLong.awsErrorDetails().errorCode());
  }

  @Test
  void invalidRequestsAreAnsweredWithTheErrorsOfSts() {
    URI endpoint = start(ACCESS_KEY_ID, SECRET_ACCESS_KEY);
    StsClient sts = sts(endpoint, AwsBasicCredentials.create(ACCESS_KEY_ID, SECRET_ACCESS_KEY));

    assertStsError("ValidationError", 400, () -> sts.assumeRole(b -> b.roleArn("arn:role").roleSessionName("s")));
    assertStsError("ValidationError", 400, () -> sts.assumeRole(b -> b.roleArn(ROLE_ARN).roleSessionName("a b")));
    assertStsError("ValidationError", 400,
        () -> sts.assumeRole(b -> b.roleArn(ROLE_ARN).roleSessionName("session").durationSeconds(60)));
    assertStsError("ValidationError", 400,
        () -> sts.assumeRole(b -> b.roleArn(ROLE_ARN).roleSessionName("session").durationSeconds(43201)));

    assertStsError("SignatureDoesNotMatch", 403, () -> sts(endpoint,
        AwsBasicCredentials.create(ACCESS_KEY_ID, "wrong-secret")).getCallerIdentity());
    assertStsError("InvalidClientTokenId", 403, () -> sts(endpoint,
        AwsBasicCredentials.create("unknown-key", SECRET_ACCESS_KEY)).getCallerIdentity());

    GetCallerIdentityResponse root = sts.getCallerIdentity();
    assertEquals("arn:aws:iam::000000000000:root", root.arn());
    assertEquals("000000000000", root.account());
  }

  /**
   * A LocalS3 that doesn't verify signatures issues credentials as well, so that a catalog that is configured with an
   * STS endpoint works with it, and accepts any request signed with them.
   */
  @Test
  void aLocalS3WithoutAuthenticationIssuesCredentialsToo() {
    URI endpoint = start(null, null);
    Credentials credentials = sts(endpoint, AwsBasicCredentials.create("any", "thing"))
        .assumeRole(b -> b.roleArn(ROLE_ARN).roleSessionName("anonymous")).credentials();
    S3Client s3 = s3(endpoint, session(credentials));
    s3.createBucket(b -> b.bucket("bucket"));
    assertEquals(1, s3.listBuckets().buckets().size());
  }

  /**
   * Credentials of {@code AssumeRole} with a session policy can do only what the policy allows, as a catalog that
   * scopes the credentials of a table to its location expects, whether or not LocalS3 verifies signatures.
   */
  @ParameterizedTest(name = "verifying signatures: {0}")
  @ValueSource(booleans = {true, false})
  void theSessionPolicyLimitsTheCredentials(boolean verifyingSignatures) {
    URI endpoint = verifyingSignatures ? start(ACCESS_KEY_ID, SECRET_ACCESS_KEY) : start(null, null);
    AwsBasicCredentials root = AwsBasicCredentials.create(ACCESS_KEY_ID, SECRET_ACCESS_KEY);
    S3Client admin = s3(endpoint, root);
    admin.createBucket(b -> b.bucket("warehouse"));
    admin.putObject(b -> b.bucket("warehouse").key("tables/t2/data.txt"), RequestBody.fromString("t2"));
    S3Client s3 = s3(endpoint, session(sts(endpoint, root)
        .assumeRole(b -> b.roleArn(ROLE_ARN).roleSessionName("table").policy(TABLE_POLICY)).credentials()));

    s3.putObject(b -> b.bucket("warehouse").key("tables/t1/data.txt"), RequestBody.fromString("t1"));
    assertEquals("t1", s3.getObjectAsBytes(b -> b.bucket("warehouse").key("tables/t1/data.txt")).asUtf8String());
    assertEquals(List.of("tables/t1/data.txt"), s3.listObjectsV2(b -> b.bucket("warehouse").prefix("tables/t1/"))
        .contents().stream().map(S3Object::key).toList());
    s3.copyObject(b -> b.sourceBucket("warehouse").sourceKey("tables/t1/data.txt")
        .destinationBucket("warehouse").destinationKey("tables/t1/copy.txt"));
    s3.getBucketLocation(b -> b.bucket("warehouse"));

    assertAccessDenied(() -> s3.putObject(b -> b.bucket("warehouse").key("tables/forbidden-write-test"),
        RequestBody.fromString("next to the table")));
    assertAccessDenied(() -> s3.getObject(b -> b.bucket("warehouse").key("tables/t2/data.txt")));
    assertAccessDenied(() -> s3.listObjectsV2(b -> b.bucket("warehouse")));
    assertAccessDenied(() -> s3.listObjectsV2(b -> b.bucket("warehouse").prefix("tables/")));
    assertAccessDenied(() -> s3.copyObject(b -> b.sourceBucket("warehouse").sourceKey("tables/t2/data.txt")
        .destinationBucket("warehouse").destinationKey("tables/t1/stolen.txt")));
    assertAccessDenied(() -> s3.createBucket(b -> b.bucket("another")));
    assertAccessDenied(() -> s3.listBuckets());

    // Each object of a DeleteObjects is authorized on its own, and reported as an error of its own if denied.
    DeleteObjectsResponse deleted = s3.deleteObjects(b -> b.bucket("warehouse").delete(d -> d.objects(
        ObjectIdentifier.builder().key("tables/t1/copy.txt").build(),
        ObjectIdentifier.builder().key("tables/t2/data.txt").build())));
    assertEquals(List.of("tables/t1/copy.txt"), deleted.deleted().stream().map(DeletedObject::key).toList());
    assertEquals(List.of("tables/t2/data.txt:AccessDenied"),
        deleted.errors().stream().map(error -> error.key() + ":" + error.code()).toList());
    assertEquals(List.of("tables/t1/data.txt", "tables/t2/data.txt"),
        admin.listObjectsV2(b -> b.bucket("warehouse")).contents().stream().map(S3Object::key).toList());
  }

  /**
   * A statement that denies wins over one that allows, and credentials without a session policy, of
   * {@code AssumeRole} without one or of {@code GetSessionToken}, can do everything the key pair of LocalS3 can.
   */
  @Test
  void aDenyWinsAndCredentialsWithoutAPolicyAreUnlimited() {
    URI endpoint = start(ACCESS_KEY_ID, SECRET_ACCESS_KEY);
    StsClient sts = sts(endpoint, AwsBasicCredentials.create(ACCESS_KEY_ID, SECRET_ACCESS_KEY));
    s3(endpoint, session(sts.assumeRole(b -> b.roleArn(ROLE_ARN).roleSessionName("unlimited")).credentials()))
        .createBucket(b -> b.bucket("bucket"));
    s3(endpoint, session(sts.getSessionToken().credentials()))
        .putObject(b -> b.bucket("bucket").key("key"), RequestBody.fromString("value"));

    S3Client noDelete = s3(endpoint, session(sts.assumeRole(b -> b.roleArn(ROLE_ARN).roleSessionName("no-delete")
        .policy("""
            {"Version": "2012-10-17", "Statement": [
              {"Effect": "Allow", "Action": "s3:*", "Resource": "*"},
              {"Effect": "Deny", "Action": "s3:Delete*", "Resource": "arn:aws:s3:::bucket/*"}]}
            """)).credentials()));
    noDelete.putObject(b -> b.bucket("bucket").key("other"), RequestBody.fromString("value"));
    assertAccessDenied(() -> noDelete.deleteObject(b -> b.bucket("bucket").key("key")));
    assertEquals(2, noDelete.listObjectsV2(b -> b.bucket("bucket")).keyCount());
  }

  @Test
  void aMalformedSessionPolicyIsRejected() {
    URI endpoint = start(ACCESS_KEY_ID, SECRET_ACCESS_KEY);
    StsClient sts = sts(endpoint, AwsBasicCredentials.create(ACCESS_KEY_ID, SECRET_ACCESS_KEY));

    assertStsError("MalformedPolicyDocument", 400,
        () -> sts.assumeRole(b -> b.roleArn(ROLE_ARN).roleSessionName("session").policy("not json")));
    assertStsError("MalformedPolicyDocument", 400, () -> sts.assumeRole(b -> b.roleArn(ROLE_ARN)
        .roleSessionName("session").policy("{\"Statement\": [{\"Effect\": \"Allow\", \"Resource\": \"*\"}]}")));
    assertStsError("PackedPolicyTooLarge", 400, () -> sts.assumeRole(b -> b.roleArn(ROLE_ARN)
        .roleSessionName("session").policy(ALLOW_ALL + " ".repeat(2048))));
  }

  private static void assertAccessDenied(org.junit.jupiter.api.function.Executable executable) {
    S3Exception exception = assertThrows(S3Exception.class, executable);
    assertEquals(403, exception.statusCode());
    assertEquals("AccessDenied", exception.awsErrorDetails().errorCode());
  }

  private static void assertStsError(String code, int status, org.junit.jupiter.api.function.Executable executable) {
    StsException exception = assertThrows(StsException.class, executable);
    assertEquals(code, exception.awsErrorDetails().errorCode());
    assertEquals(status, exception.statusCode());
  }

  private URI start(String accessKeyId, String secretAccessKey) {
    LocalS3Builder builder = LocalS3.builder().port(-1);
    if (accessKeyId != null) {
      builder.credentials(accessKeyId, secretAccessKey);
    }
    LocalS3 localS3 = builder.build();
    localS3.start();
    resources.add(localS3::shutdown);
    return URI.create("http://localhost:" + localS3.getPort());
  }

  private StsClient sts(URI endpoint, AwsCredentials credentials) {
    StsClient client = StsClient.builder()
        .endpointOverride(endpoint)
        .region(Region.US_EAST_1)
        .credentialsProvider(StaticCredentialsProvider.create(credentials))
        .build();
    resources.add(client);
    return client;
  }

  private S3Client s3(URI endpoint, AwsCredentials credentials) {
    S3Client client = S3Client.builder()
        .endpointOverride(endpoint)
        .region(Region.US_EAST_1)
        .credentialsProvider(StaticCredentialsProvider.create(credentials))
        .forcePathStyle(true)
        .build();
    resources.add(client);
    return client;
  }

  private static AwsSessionCredentials session(Credentials credentials) {
    return AwsSessionCredentials.create(credentials.accessKeyId(), credentials.secretAccessKey(),
        credentials.sessionToken());
  }

}
