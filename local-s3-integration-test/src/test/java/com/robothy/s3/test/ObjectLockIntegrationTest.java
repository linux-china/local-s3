package com.robothy.s3.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.robothy.s3.jupiter.LocalS3;
import com.robothy.s3.rest.bootstrap.LocalS3Mode;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.DefaultRetention;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.GetObjectLockConfigurationResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRetentionResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.ObjectLockEnabled;
import software.amazon.awssdk.services.s3.model.ObjectLockLegalHoldStatus;
import software.amazon.awssdk.services.s3.model.ObjectLockMode;
import software.amazon.awssdk.services.s3.model.ObjectLockRetentionMode;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Object Lock through the AWS SDK: the configuration of a bucket, the retention and legal hold of object versions, and
 * the deletes they refuse.
 */
class ObjectLockIntegrationTest {

  private static final String LOCKED = "locked-bucket";

  @Test
  @LocalS3(buckets = "plain-bucket")
  void createsABucketWithObjectLockAndItsDefaultRetention(S3Client s3) {
    S3Exception missing = assertThrows(S3Exception.class,
        () -> s3.getObjectLockConfiguration(b -> b.bucket("plain-bucket")));
    assertEquals(404, missing.statusCode());
    assertEquals("ObjectLockConfigurationNotFoundError", missing.awsErrorDetails().errorCode());
    S3Exception notVersioned = assertThrows(S3Exception.class, () -> s3.putObjectLockConfiguration(b -> b
        .bucket("plain-bucket")
        .objectLockConfiguration(c -> c.objectLockEnabled(ObjectLockEnabled.ENABLED))));
    assertEquals("InvalidBucketState", notVersioned.awsErrorDetails().errorCode());
    S3Exception noLock = assertThrows(S3Exception.class, () -> s3.putObject(b -> b.bucket("plain-bucket").key("a")
        .objectLockLegalHoldStatus(ObjectLockLegalHoldStatus.ON), RequestBody.fromString("a")));
    assertEquals("InvalidRequest", noLock.awsErrorDetails().errorCode());

    s3.createBucket(b -> b.bucket(LOCKED).objectLockEnabledForBucket(true));
    assertEquals(BucketVersioningStatus.ENABLED, s3.getBucketVersioning(b -> b.bucket(LOCKED)).status());
    assertEquals(ObjectLockEnabled.ENABLED,
        s3.getObjectLockConfiguration(b -> b.bucket(LOCKED)).objectLockConfiguration().objectLockEnabled());
    S3Exception suspend = assertThrows(S3Exception.class, () -> s3.putBucketVersioning(b -> b.bucket(LOCKED)
        .versioningConfiguration(v -> v.status(BucketVersioningStatus.SUSPENDED))));
    assertEquals("InvalidBucketState", suspend.awsErrorDetails().errorCode());

    s3.putObjectLockConfiguration(b -> b.bucket(LOCKED).objectLockConfiguration(c -> c
        .objectLockEnabled(ObjectLockEnabled.ENABLED)
        .rule(r -> r.defaultRetention(DefaultRetention.builder().mode(ObjectLockRetentionMode.GOVERNANCE).days(1)
            .build()))));
    GetObjectLockConfigurationResponse configuration = s3.getObjectLockConfiguration(b -> b.bucket(LOCKED));
    assertEquals(ObjectLockRetentionMode.GOVERNANCE,
        configuration.objectLockConfiguration().rule().defaultRetention().mode());
    assertEquals(1, configuration.objectLockConfiguration().rule().defaultRetention().days());

    Instant before = Instant.now();
    s3.putObject(b -> b.bucket(LOCKED).key("defaulted.txt"), RequestBody.fromString("hello"));
    HeadObjectResponse head = s3.headObject(b -> b.bucket(LOCKED).key("defaulted.txt"));
    assertEquals(ObjectLockMode.GOVERNANCE, head.objectLockMode());
    assertTrue(head.objectLockRetainUntilDate().isAfter(before.plus(1, ChronoUnit.DAYS).minusSeconds(5)));
    assertNull(head.objectLockLegalHoldStatus());
  }

  @Test
  @LocalS3
  void refusesToDeleteAProtectedVersion(S3Client s3) {
    s3.createBucket(b -> b.bucket(LOCKED).objectLockEnabledForBucket(true));
    Instant retainUntil = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
    PutObjectResponse governed = s3.putObject(b -> b.bucket(LOCKED).key("governed.txt")
        .objectLockMode(ObjectLockMode.GOVERNANCE).objectLockRetainUntilDate(retainUntil), RequestBody.fromString("g"));
    PutObjectResponse held = s3.putObject(b -> b.bucket(LOCKED).key("held.txt")
        .objectLockLegalHoldStatus(ObjectLockLegalHoldStatus.ON), RequestBody.fromString("h"));

    GetObjectRetentionResponse retention = s3.getObjectRetention(b -> b.bucket(LOCKED).key("governed.txt"));
    assertEquals(ObjectLockRetentionMode.GOVERNANCE, retention.retention().mode());
    assertEquals(retainUntil, retention.retention().retainUntilDate());
    assertEquals(ObjectLockLegalHoldStatus.ON,
        s3.getObjectLegalHold(b -> b.bucket(LOCKED).key("held.txt")).legalHold().status());
    assertEquals("NoSuchObjectLockConfiguration", assertThrows(S3Exception.class,
        () -> s3.getObjectLegalHold(b -> b.bucket(LOCKED).key("governed.txt"))).awsErrorDetails().errorCode());

    S3Exception denied = assertThrows(S3Exception.class, () -> s3.deleteObject(b -> b.bucket(LOCKED)
        .key("governed.txt").versionId(governed.versionId())));
    assertEquals(403, denied.statusCode());
    assertEquals("AccessDenied", denied.awsErrorDetails().errorCode());
    // A delete without a version ID adds a delete marker, which Object Lock doesn't prevent.
    assertTrue(s3.deleteObject(b -> b.bucket(LOCKED).key("governed.txt")).deleteMarker());

    DeleteObjectsResponse batch = s3.deleteObjects(b -> b.bucket(LOCKED).delete(d -> d.objects(
        ObjectIdentifier.builder().key("held.txt").versionId(held.versionId()).build())));
    assertEquals("AccessDenied", batch.errors().get(0).code());

    // A legal hold that is turned off no longer protects the version.
    s3.putObjectLegalHold(b -> b.bucket(LOCKED).key("held.txt").legalHold(l -> l.status(ObjectLockLegalHoldStatus.OFF)));
    s3.deleteObject(b -> b.bucket(LOCKED).key("held.txt").versionId(held.versionId()));

    // A governance retention can't be shortened, but can be bypassed.
    assertEquals("AccessDenied", assertThrows(S3Exception.class, () -> s3.putObjectRetention(b -> b.bucket(LOCKED)
        .key("governed.txt").versionId(governed.versionId())
        .retention(r -> r.mode(ObjectLockRetentionMode.GOVERNANCE).retainUntilDate(retainUntil.minusSeconds(60)))))
        .awsErrorDetails().errorCode());
    s3.deleteObject(b -> b.bucket(LOCKED).key("governed.txt").versionId(governed.versionId())
        .bypassGovernanceRetention(true));
    assertEquals(List.of(), s3.listObjectVersions(b -> b.bucket(LOCKED).prefix("governed.txt")).versions());
  }

  @Test
  @LocalS3
  void aComplianceRetentionCanOnlyBeExtended(S3Client s3) {
    s3.createBucket(b -> b.bucket(LOCKED).objectLockEnabledForBucket(true));
    Instant retainUntil = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
    String versionId = s3.putObject(b -> b.bucket(LOCKED).key("compliant.txt"), RequestBody.fromString("c"))
        .versionId();
    s3.putObjectRetention(b -> b.bucket(LOCKED).key("compliant.txt")
        .retention(r -> r.mode(ObjectLockRetentionMode.COMPLIANCE).retainUntilDate(retainUntil)));

    S3Exception shortened = assertThrows(S3Exception.class, () -> s3.putObjectRetention(b -> b.bucket(LOCKED)
        .key("compliant.txt").bypassGovernanceRetention(true)
        .retention(r -> r.mode(ObjectLockRetentionMode.GOVERNANCE).retainUntilDate(retainUntil.plusSeconds(60)))));
    assertEquals("AccessDenied", shortened.awsErrorDetails().errorCode());
    assertEquals("AccessDenied", assertThrows(S3Exception.class, () -> s3.deleteObject(b -> b.bucket(LOCKED)
        .key("compliant.txt").versionId(versionId).bypassGovernanceRetention(true))).awsErrorDetails().errorCode());

    Instant extended = retainUntil.plus(1, ChronoUnit.DAYS);
    s3.putObjectRetention(b -> b.bucket(LOCKED).key("compliant.txt")
        .retention(r -> r.mode(ObjectLockRetentionMode.COMPLIANCE).retainUntilDate(extended)));
    assertEquals(extended, s3.getObjectRetention(b -> b.bucket(LOCKED).key("compliant.txt")).retention()
        .retainUntilDate());

    S3Exception past = assertThrows(S3Exception.class, () -> s3.putObject(b -> b.bucket(LOCKED).key("past.txt")
        .objectLockMode(ObjectLockMode.GOVERNANCE).objectLockRetainUntilDate(Instant.now().minusSeconds(60)),
        RequestBody.fromString("p")));
    assertEquals("InvalidArgument", past.awsErrorDetails().errorCode());
  }

  /**
   * The configuration of a bucket, and the retention, legal hold and customer key of a version, are persisted.
   */
  @Test
  void persistsObjectLockSettings(@TempDir Path dataPath) {
    Instant retainUntil = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
    com.robothy.s3.rest.LocalS3 first = persistent(dataPath);
    try (S3Client s3 = client(first)) {
      s3.createBucket(b -> b.bucket(LOCKED).objectLockEnabledForBucket(true));
      s3.putObject(b -> b.bucket(LOCKED).key("kept.txt").objectLockMode(ObjectLockMode.COMPLIANCE)
          .objectLockRetainUntilDate(retainUntil).objectLockLegalHoldStatus(ObjectLockLegalHoldStatus.ON),
          RequestBody.fromString("kept"));
    } finally {
      first.shutdown();
    }

    com.robothy.s3.rest.LocalS3 second = persistent(dataPath);
    try (S3Client s3 = client(second)) {
      HeadObjectResponse head = s3.headObject(b -> b.bucket(LOCKED).key("kept.txt"));
      assertEquals(ObjectLockMode.COMPLIANCE, head.objectLockMode());
      assertEquals(retainUntil, head.objectLockRetainUntilDate());
      assertEquals(ObjectLockLegalHoldStatus.ON, head.objectLockLegalHoldStatus());
      assertEquals(ObjectLockEnabled.ENABLED,
          s3.getObjectLockConfiguration(b -> b.bucket(LOCKED)).objectLockConfiguration().objectLockEnabled());
    } finally {
      second.shutdown();
    }
  }

  private static com.robothy.s3.rest.LocalS3 persistent(Path dataPath) {
    com.robothy.s3.rest.LocalS3 localS3 = com.robothy.s3.rest.LocalS3.builder().port(-1)
        .mode(LocalS3Mode.PERSISTENCE).dataPath(dataPath.toString()).build();
    localS3.start();
    return localS3;
  }

  private static S3Client client(com.robothy.s3.rest.LocalS3 localS3) {
    return S3Client.builder()
        .endpointOverride(URI.create("http://127.0.0.1:" + localS3.getPort()))
        .region(Region.US_EAST_1)
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")))
        .forcePathStyle(true)
        .build();
  }

}
