package com.robothy.s3.test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.robothy.s3.rest.LocalS3;
import io.github.robothy.s3.RealS3;
import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.DeleteMarkerEntry;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.ObjectLockEnabled;
import software.amazon.awssdk.services.s3.model.ObjectVersion;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * The requests of the tests of ceph/s3-tests that {@code ceph-s3-tests/known-failures.txt} lists as {@code unverified}:
 * LocalS3 answers them otherwise than the test expects, and it isn't known yet which of the two Amazon S3 does.
 *
 * <p>Each check sends the requests of one test of s3-tests, expects the answer of LocalS3, and names what the test
 * expects instead. The checks run against LocalS3 and, with
 * {@code ./gradlew :local-s3-integration-test:realS3Test --tests '*S3TestsUnverifiedIntegrationTest'} and the credentials
 * of an AWS account in {@code AWS_ACCESS_KEY_ID} and {@code AWS_SECRET_ACCESS_KEY}, against Amazon S3, in buckets of
 * their own that are deleted afterwards. Every check is run, so a run against Amazon S3 reports each one that Amazon S3
 * answers otherwise than LocalS3, with its answer: a check that passes there is {@code consistent with AWS}, one that
 * fails is a {@code divergence} of LocalS3.
 */
class S3TestsUnverifiedIntegrationTest {

  private static final String KEY = "file1";

  private static final String MULTIPART_KEY = "mymultipart";

  private static final String TAGGED_KEY = "testtagobj1";

  /**
   * The requests of a test of s3-tests.
   *
   * @param test the test of s3-tests.
   * @param s3TestsExpects what the test expects.
   * @param localS3Answers what LocalS3 answers, which the check expects.
   * @param request sends the requests, and answers with the status and error code, or what the test reads back.
   */
  private record Check(String test, String s3TestsExpects, String localS3Answers, Supplier<String> request) {

    Executable executable() {
      return () -> assertEquals(localS3Answers, request.get(),
          test + " (s3-tests expects " + s3TestsExpects + ")");
    }
  }

  @Test
  void localS3AnswersTheUnverifiedTestsOfS3Tests() {
    try (LocalS3 localS3 = LocalS3.builder().port(-1).build()) {
      localS3.start();
      try (S3Client s3 = S3Client.builder()
          .endpointOverride(URI.create("http://127.0.0.1:" + localS3.getPort()))
          .region(Region.US_EAST_1)
          .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("s3-tests", "s3-tests")))
          .forcePathStyle(true)
          .build()) {
        assertChecks(s3, "locked", "unlocked");
      }
    }
  }

  /**
   * The checks against Amazon S3, which is what the answers of LocalS3 are to be checked against.
   */
  @Test
  @RealS3
  void amazonS3AnswersTheUnverifiedTestsOfS3Tests(S3Client s3) {
    String suffix = UUID.randomUUID().toString();
    assertChecks(s3, "local-s3-locked-" + suffix, "local-s3-unlocked-" + suffix);
  }

  private static void assertChecks(S3Client s3, String lockedBucket, String bucket) {
    s3.createBucket(request -> request.bucket(lockedBucket).objectLockEnabledForBucket(true));
    s3.createBucket(request -> request.bucket(bucket));
    String uploadId = null;
    try {
      s3.putObject(request -> request.bucket(lockedBucket).key(KEY), RequestBody.fromString("abc"));

      // test_multipart_resend_first_finishes_last uploads part 1 twice at once, and completes with the entity tag of
      // the upload that finished first, then with the one of the upload that finished last, which is the part.
      String upload = s3.createMultipartUpload(request -> request.bucket(bucket).key(MULTIPART_KEY)).uploadId();
      uploadId = upload;
      String finishedFirst = s3.uploadPart(request -> request.bucket(bucket).key(MULTIPART_KEY).uploadId(upload)
          .partNumber(1), RequestBody.fromString("BBBBBBBB")).eTag();
      String finishedLast = s3.uploadPart(request -> request.bucket(bucket).key(MULTIPART_KEY).uploadId(upload)
          .partNumber(1), RequestBody.fromString("AAAAAAAA")).eTag();

      Instant tomorrow = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
      List<Check> checks = List.of(
          new Check("test_object_lock_put_legal_hold_invalid_status", "400 MalformedXML", "400 InvalidArgument",
              () -> answer(() -> s3.putObjectLegalHold(request -> request.bucket(lockedBucket).key(KEY)
                  .legalHold(legalHold -> legalHold.status("abc"))))),
          new Check("test_object_lock_put_obj_lock_invalid_days", "400 InvalidRetentionPeriod", "400 InvalidArgument",
              () -> putDefaultRetention(s3, lockedBucket, "GOVERNANCE", 0, null)),
          new Check("test_object_lock_put_obj_lock_invalid_mode (abc)", "400 MalformedXML", "400 InvalidArgument",
              () -> putDefaultRetention(s3, lockedBucket, "abc", null, 1)),
          new Check("test_object_lock_put_obj_lock_invalid_mode (governance)", "400 MalformedXML",
              "400 InvalidArgument", () -> putDefaultRetention(s3, lockedBucket, "governance", null, 1)),
          new Check("test_object_lock_put_obj_lock_invalid_years", "400 InvalidRetentionPeriod", "400 InvalidArgument",
              () -> putDefaultRetention(s3, lockedBucket, "GOVERNANCE", null, -1)),
          new Check("test_object_lock_put_obj_retention_invalid_mode (governance)", "400 MalformedXML",
              "400 InvalidArgument", () -> answer(() -> s3.putObjectRetention(request -> request.bucket(lockedBucket)
                  .key(KEY).retention(retention -> retention.mode("governance").retainUntilDate(tomorrow))))),
          new Check("test_object_lock_put_obj_retention_invalid_mode (abc)", "400 MalformedXML",
              "400 InvalidArgument", () -> answer(() -> s3.putObjectRetention(request -> request.bucket(lockedBucket)
                  .key(KEY).retention(retention -> retention.mode("abc").retainUntilDate(tomorrow))))),
          new Check("test_multipart_resend_first_finishes_last", "200, and the content of the upload that finished last",
              "400 InvalidPart", () -> answer(() -> s3.completeMultipartUpload(request -> request.bucket(bucket)
                  .key(MULTIPART_KEY).uploadId(upload).multipartUpload(parts -> parts.parts(
                      CompletedPart.builder().partNumber(1).eTag(finishedFirst).build(),
                      CompletedPart.builder().partNumber(1).eTag(finishedLast).build()))))),
          new Check("test_put_obj_with_tags", "the tags sorted by key: bar=&foo=bar", "foo=bar&bar=",
              () -> tagsOfObjectTaggedWith(s3, bucket, "foo=bar&bar")));

      assertAll(checks.stream().map(Check::executable));
    } finally {
      if (uploadId != null) {
        String upload = uploadId;
        answer(() -> s3.abortMultipartUpload(request -> request.bucket(bucket).key(MULTIPART_KEY).uploadId(upload)));
      }
      deleteLockedBucket(s3, lockedBucket);
      for (S3Object object : s3.listObjectsV2(request -> request.bucket(bucket)).contents()) {
        s3.deleteObject(request -> request.bucket(bucket).key(object.key()));
      }
      s3.deleteBucket(request -> request.bucket(bucket));
    }
  }

  private static String putDefaultRetention(S3Client s3, String bucket, String mode, Integer days, Integer years) {
    return answer(() -> s3.putObjectLockConfiguration(request -> request.bucket(bucket)
        .objectLockConfiguration(configuration -> configuration.objectLockEnabled(ObjectLockEnabled.ENABLED)
            .rule(rule -> rule.defaultRetention(retention -> retention.mode(mode).days(days).years(years))))));
  }

  /**
   * Put an object with an {@code x-amz-tagging} header, and answer its tags as {@code GetObjectTagging} lists them.
   */
  private static String tagsOfObjectTaggedWith(S3Client s3, String bucket, String tagging) {
    s3.putObject(request -> request.bucket(bucket).key(TAGGED_KEY).tagging(tagging), RequestBody.fromString("A".repeat(100)));
    return s3.getObjectTagging(request -> request.bucket(bucket).key(TAGGED_KEY)).tagSet().stream()
        .map(tag -> tag.key() + "=" + tag.value())
        .collect(Collectors.joining("&"));
  }

  /**
   * Send a request, and answer {@code 200}, or its status and error code if it fails.
   */
  private static String answer(Runnable request) {
    try {
      request.run();
      return "200";
    } catch (S3Exception e) {
      return e.statusCode() + " " + e.awsErrorDetails().errorCode();
    }
  }

  /**
   * Delete a bucket with Object Lock and every version in it, bypassing the governance retention that a check may have
   * set if Amazon S3 took a request that LocalS3 refuses.
   */
  private static void deleteLockedBucket(S3Client s3, String bucket) {
    ListObjectVersionsResponse listing = s3.listObjectVersions(request -> request.bucket(bucket));
    for (ObjectVersion version : listing.versions()) {
      s3.deleteObject(request -> request.bucket(bucket).key(version.key()).versionId(version.versionId())
          .bypassGovernanceRetention(true));
    }
    for (DeleteMarkerEntry marker : listing.deleteMarkers()) {
      s3.deleteObject(request -> request.bucket(bucket).key(marker.key()).versionId(marker.versionId()));
    }
    s3.deleteBucket(request -> request.bucket(bucket));
  }

}
