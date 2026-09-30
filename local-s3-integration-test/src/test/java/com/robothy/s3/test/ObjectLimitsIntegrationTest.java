package com.robothy.s3.test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.robothy.s3.rest.LocalS3;
import io.github.robothy.s3.RealS3;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.MetadataDirective;
import software.amazon.awssdk.services.s3.model.MultipartUpload;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * The limits of Amazon S3 on what an object is written with: a key of at most 1024 bytes in UTF-8, else
 * {@code 400 KeyTooLongError}, and user-defined metadata of at most 2 KB, the UTF-8 encodings of the names, without
 * their {@code x-amz-meta-} prefix, and of the values summed, else {@code 400 MetadataTooLarge}.
 *
 * <p>The same scenarios run against LocalS3 and, with {@code ./gradlew :local-s3-integration-test:realS3Test} and the
 * credentials of an AWS account in {@code AWS_ACCESS_KEY_ID} and {@code AWS_SECRET_ACCESS_KEY}, against Amazon S3, in a
 * bucket of its own that is deleted afterwards. Every scenario is checked, so a run against Amazon S3 reports each one
 * that LocalS3 answers differently.
 */
class ObjectLimitsIntegrationTest {

  /**
   * A key of 1024 bytes: the longest that Amazon S3 takes.
   */
  private static final String LONGEST_KEY = "k".repeat(1024);

  private static final String TOO_LONG_KEY = "k".repeat(1025);

  /**
   * A key of 1023 bytes in 341 characters, which the limit counts in bytes rather than in characters.
   */
  private static final String LONGEST_MULTIBYTE_KEY = "中".repeat(341);

  /**
   * A key of 1026 bytes in 513 characters.
   */
  private static final String TOO_LONG_MULTIBYTE_KEY = "é".repeat(513);

  /**
   * Metadata of 2048 bytes: a name of 1 byte and a value of 2047.
   */
  private static final Map<String, String> LARGEST_METADATA = Map.of("a", "v".repeat(2047));

  private static final Map<String, String> TOO_LARGE_METADATA = Map.of("a", "v".repeat(2048));

  /**
   * Metadata of 2049 bytes over several entries, which the limit sums.
   */
  private static final Map<String, String> TOO_LARGE_SPLIT_METADATA = Map.of(
      "first", "v".repeat(1019), "second", "v".repeat(1019));

  private static final String SOURCE = "source.txt";

  @Test
  void localS3EnforcesTheLimitsLikeAmazonS3() {
    try (LocalS3 localS3 = LocalS3.builder().port(-1).build()) {
      localS3.start();
      try (S3Client s3 = S3Client.builder()
          .endpointOverride(URI.create("http://127.0.0.1:" + localS3.getPort()))
          .region(Region.US_EAST_1)
          .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("limits", "limits")))
          .forcePathStyle(true)
          .build()) {
        assertScenarios(s3, "limits");
      }
    }
  }

  /**
   * The scenarios against Amazon S3, which is what the expectations above are to be checked against.
   */
  @Test
  @RealS3
  void amazonS3EnforcesTheLimitsSo(S3Client s3) {
    assertScenarios(s3, "local-s3-limits-" + UUID.randomUUID());
  }

  private static void assertScenarios(S3Client s3, String bucket) {
    s3.createBucket(request -> request.bucket(bucket));
    try {
      s3.putObject(request -> request.bucket(bucket).key(SOURCE), RequestBody.fromString("source"));

      List<Executable> checks = new ArrayList<>();
      checks.add(() -> put(s3, bucket, LONGEST_KEY, Map.of()));
      checks.add(() -> put(s3, bucket, LONGEST_MULTIBYTE_KEY, Map.of()));
      checks.add(() -> assertRejected("KeyTooLongError", "PutObject of a key of 1025 bytes",
          () -> put(s3, bucket, TOO_LONG_KEY, Map.of())));
      checks.add(() -> assertRejected("KeyTooLongError", "PutObject of a key of 1026 bytes in 513 characters",
          () -> put(s3, bucket, TOO_LONG_MULTIBYTE_KEY, Map.of())));
      checks.add(() -> assertRejected("KeyTooLongError", "CopyObject to a key of 1025 bytes",
          () -> s3.copyObject(request -> request.sourceBucket(bucket).sourceKey(SOURCE)
              .destinationBucket(bucket).destinationKey(TOO_LONG_KEY))));
      checks.add(() -> assertRejected("KeyTooLongError", "CreateMultipartUpload of a key of 1025 bytes",
          () -> s3.createMultipartUpload(request -> request.bucket(bucket).key(TOO_LONG_KEY))));

      checks.add(() -> put(s3, bucket, "largest-metadata", LARGEST_METADATA));
      checks.add(() -> assertRejected("MetadataTooLarge", "PutObject with metadata of 2049 bytes",
          () -> put(s3, bucket, "too-large-metadata", TOO_LARGE_METADATA)));
      checks.add(() -> assertRejected("MetadataTooLarge", "PutObject with metadata of 2049 bytes in two entries",
          () -> put(s3, bucket, "too-large-split-metadata", TOO_LARGE_SPLIT_METADATA)));
      checks.add(() -> assertRejected("MetadataTooLarge", "CopyObject replacing the metadata with 2049 bytes",
          () -> s3.copyObject(request -> request.sourceBucket(bucket).sourceKey(SOURCE)
              .destinationBucket(bucket).destinationKey("copy-too-large-metadata")
              .metadataDirective(MetadataDirective.REPLACE).metadata(TOO_LARGE_METADATA))));
      checks.add(() -> assertRejected("MetadataTooLarge", "CreateMultipartUpload with metadata of 2049 bytes",
          () -> s3.createMultipartUpload(request -> request.bucket(bucket).key("upload-too-large-metadata")
              .metadata(TOO_LARGE_METADATA))));
      assertAll(checks);
    } finally {
      for (MultipartUpload upload : s3.listMultipartUploads(request -> request.bucket(bucket)).uploads()) {
        s3.abortMultipartUpload(request -> request.bucket(bucket).key(upload.key()).uploadId(upload.uploadId()));
      }
      for (S3Object object : s3.listObjectsV2(request -> request.bucket(bucket)).contents()) {
        s3.deleteObject(request -> request.bucket(bucket).key(object.key()));
      }
      s3.deleteBucket(request -> request.bucket(bucket));
    }
  }

  private static void put(S3Client s3, String bucket, String key, Map<String, String> metadata) {
    s3.putObject(request -> request.bucket(bucket).key(key).metadata(metadata), RequestBody.fromString("content"));
  }

  private static void assertRejected(String errorCode, String scenario, Executable request) {
    S3Exception failure = assertThrows(S3Exception.class, request, scenario);
    assertEquals(400, failure.statusCode(), scenario);
    assertEquals(errorCode, failure.awsErrorDetails().errorCode(), scenario);
  }

}
