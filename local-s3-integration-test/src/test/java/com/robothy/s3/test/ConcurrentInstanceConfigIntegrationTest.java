package com.robothy.s3.test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import com.robothy.s3.rest.LocalS3;
import java.net.URI;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

/**
 * Two LocalS3 services running in the same JVM each keep the configuration of their own builder. The services
 * of an instance used to be held in a registry shared by every instance of the JVM, so the one that started
 * last could supply its configuration to the ones started before it.
 */
public class ConcurrentInstanceConfigIntegrationTest {

  private static S3Client client(LocalS3 localS3) {
    return S3Client.builder()
        .endpointOverride(URI.create("http://127.0.0.1:" + localS3.getPort()))
        .region(Region.US_EAST_1)
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("k", "s")))
        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
        .build();
  }

  /**
   * The strict service rejects what Amazon S3 rejects, while the lenient one running next to it doesn't.
   */
  @Test
  void eachInstanceKeepsItsOwnBucketNameRules() {
    LocalS3 lenient = LocalS3.builder().port(-1).strictBucketNames(false).build();
    LocalS3 strict = LocalS3.builder().port(-1).strictBucketNames(true).build();
    lenient.start();
    // The strict service starts last, so a shared registry would give its rules to the lenient one.
    strict.start();
    try (S3Client lenientClient = client(lenient); S3Client strictClient = client(strict)) {
      // A name that the SDK sends but Amazon S3 reserves, so the strict rules reject it and lenient ones don't.
      String reservedName = "test-bucket-s3alias";
      assertDoesNotThrow(() -> lenientClient.createBucket(b -> b.bucket(reservedName)));

      S3Exception thrown =
          assertThrows(S3Exception.class, () -> strictClient.createBucket(b -> b.bucket(reservedName)));
      assertEquals("InvalidBucketName", thrown.awsErrorDetails().errorCode());
    } finally {
      strict.shutdown();
      lenient.shutdown();
    }
  }

  /**
   * The same for the part sizes of a multipart upload, which the service checks while a request is handled.
   */
  @Test
  void eachInstanceKeepsItsOwnPartSizeRules() {
    LocalS3 strict = LocalS3.builder().port(-1).strictPartSizes(true).build();
    LocalS3 lenient = LocalS3.builder().port(-1).strictPartSizes(false).build();
    strict.start();
    // The lenient service starts last, so a shared registry would let the strict one accept a small part.
    lenient.start();
    try (S3Client strictClient = client(strict); S3Client lenientClient = client(lenient)) {
      assertThrows(S3Exception.class, () -> completeWithSmallParts(strictClient));
      assertDoesNotThrow(() -> completeWithSmallParts(lenientClient));
    } finally {
      lenient.shutdown();
      strict.shutdown();
    }
  }

  private static void completeWithSmallParts(S3Client s3) {
    String bucket = "parts-bucket";
    String key = "target.txt";
    s3.createBucket(b -> b.bucket(bucket));
    String uploadId = s3.createMultipartUpload(b -> b.bucket(bucket).key(key)).uploadId();
    UploadPartResponse first = s3.uploadPart(b -> b.bucket(bucket).key(key).uploadId(uploadId).partNumber(1),
        RequestBody.fromString("small"));
    UploadPartResponse second = s3.uploadPart(b -> b.bucket(bucket).key(key).uploadId(uploadId).partNumber(2),
        RequestBody.fromString("parts"));
    s3.completeMultipartUpload(b -> b.bucket(bucket).key(key).uploadId(uploadId)
        .multipartUpload(CompletedMultipartUpload.builder().parts(
            CompletedPart.builder().partNumber(1).eTag(first.eTag()).build(),
            CompletedPart.builder().partNumber(2).eTag(second.eTag()).build()).build()));
  }

}
