package com.robothy.s3.test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.robothy.s3.rest.LocalS3;
import io.github.robothy.s3.RealS3;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * The {@code Range} header at the edges: an empty object, e.g. a {@code _SUCCESS} marker that a reader probes, a range
 * that starts at or beyond the end of an object, suffix ranges, and ranges that aren't valid at all.
 *
 * <p>The same scenarios run against LocalS3 and, with {@code ./gradlew :local-s3-integration-test:realS3Test} and the
 * credentials of an AWS account in {@code AWS_ACCESS_KEY_ID} and {@code AWS_SECRET_ACCESS_KEY}, against Amazon S3, in a
 * bucket of its own that is deleted afterwards. Every scenario is checked, so a run against Amazon S3 reports each one
 * that LocalS3 answers differently.
 */
class RangeEdgeCaseIntegrationTest {

  private static final String EMPTY = "empty.bin";

  private static final String TEN = "ten.bin";

  private static final String TEN_BYTES = "0123456789";

  /**
   * A range request, and how Amazon S3 answers it: {@code 206} with a {@code Content-Range}, {@code 200} with the whole
   * object when the header is ignored, or {@code 416 InvalidRange}.
   *
   * @param status the expected status.
   * @param contentRange the expected {@code Content-Range}; {@code null} for none.
   * @param content the expected content; {@code null} for a {@code 416}.
   */
  private record Scenario(String key, String range, int status, String contentRange, String content) {

    @Override
    public String toString() {
      return key + " with Range: " + range;
    }
  }

  private static final List<Scenario> SCENARIOS = List.of(
      new Scenario(EMPTY, null, 200, null, ""),
      // No byte of an empty object can be read: a range of it is unsatisfiable, whichever form it takes.
      new Scenario(EMPTY, "bytes=0-", 416, null, null),
      new Scenario(EMPTY, "bytes=0-0", 416, null, null),
      new Scenario(EMPTY, "bytes=0-100", 416, null, null),
      new Scenario(EMPTY, "bytes=-1", 416, null, null),
      // A header that isn't a byte range is ignored.
      new Scenario(EMPTY, "bytes=abc", 200, null, ""),

      new Scenario(TEN, "bytes=0-", 206, "bytes 0-9/10", TEN_BYTES),
      new Scenario(TEN, "bytes=9-9", 206, "bytes 9-9/10", "9"),
      new Scenario(TEN, "bytes=8-100", 206, "bytes 8-9/10", "89"),
      new Scenario(TEN, "bytes=-3", 206, "bytes 7-9/10", "789"),
      new Scenario(TEN, "bytes=-20", 206, "bytes 0-9/10", TEN_BYTES),
      new Scenario(TEN, "bytes=10-", 416, null, null),
      new Scenario(TEN, "bytes=100-200", 416, null, null),
      new Scenario(TEN, "bytes=-0", 416, null, null),
      // Neither a range whose end precedes its start, nor several ranges, is served: the whole object is.
      new Scenario(TEN, "bytes=5-2", 200, null, TEN_BYTES),
      new Scenario(TEN, "bytes=0-1,4-5", 200, null, TEN_BYTES));

  @Test
  void localS3AnswersTheRangesLikeAmazonS3() {
    try (LocalS3 localS3 = LocalS3.builder().port(-1).build()) {
      localS3.start();
      try (S3Client s3 = S3Client.builder()
          .endpointOverride(URI.create("http://127.0.0.1:" + localS3.getPort()))
          .region(Region.US_EAST_1)
          .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("range", "range")))
          .forcePathStyle(true)
          .build()) {
        assertScenarios(s3, "ranges");
      }
    }
  }

  /**
   * The scenarios against Amazon S3, which is what the expectations above are to be checked against.
   */
  @Test
  @RealS3
  void amazonS3AnswersTheRangesSo(S3Client s3) {
    assertScenarios(s3, "local-s3-ranges-" + UUID.randomUUID());
  }

  private static void assertScenarios(S3Client s3, String bucket) {
    s3.createBucket(request -> request.bucket(bucket));
    try {
      s3.putObject(request -> request.bucket(bucket).key(EMPTY), RequestBody.empty());
      s3.putObject(request -> request.bucket(bucket).key(TEN), RequestBody.fromString(TEN_BYTES));

      List<Executable> checks = new ArrayList<>();
      for (Scenario scenario : SCENARIOS) {
        checks.add(() -> assertScenario(s3, bucket, scenario));
      }
      // HeadObject answers a range like GetObject, without the content.
      checks.add(() -> assertEquals(416, assertThrows(S3Exception.class, () -> s3.headObject(request -> request
          .bucket(bucket).key(EMPTY).range("bytes=0-"))).statusCode(), "HeadObject of " + EMPTY + " with bytes=0-"));
      assertAll(checks);
    } finally {
      for (S3Object object : s3.listObjectsV2(request -> request.bucket(bucket)).contents()) {
        s3.deleteObject(request -> request.bucket(bucket).key(object.key()));
      }
      s3.deleteBucket(request -> request.bucket(bucket));
    }
  }

  private static void assertScenario(S3Client s3, String bucket, Scenario scenario) {
    if (scenario.status() == 416) {
      S3Exception failure = assertThrows(S3Exception.class, () -> s3.getObjectAsBytes(request -> request
          .bucket(bucket).key(scenario.key()).range(scenario.range())), scenario.toString());
      assertEquals(416, failure.statusCode(), scenario.toString());
      assertEquals("InvalidRange", failure.awsErrorDetails().errorCode(), scenario.toString());
      return;
    }
    ResponseBytes<GetObjectResponse> read = s3.getObjectAsBytes(request -> request.bucket(bucket)
        .key(scenario.key()).range(scenario.range()));
    assertEquals(scenario.status(), read.response().sdkHttpResponse().statusCode(), scenario.toString());
    assertEquals(scenario.contentRange(), read.response().contentRange(), scenario.toString());
    assertEquals(scenario.content(), new String(read.asByteArray(), StandardCharsets.US_ASCII), scenario.toString());
  }

}
