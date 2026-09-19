package com.robothy.s3.test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
   * The entity tag of a completed multipart upload, which the service computes while a request is handled,
   * follows the configuration of the instance that handles it.
   */
  @Test
  void eachInstanceKeepsItsOwnMultipartEtags() {
    LocalS3 composite = LocalS3.builder().port(-1).s3Api(s3 -> s3.compositeMultipartEtags(true)).build();
    LocalS3 plain = LocalS3.builder().port(-1).s3Api(s3 -> s3.compositeMultipartEtags(false)).build();
    composite.start();
    // The plain service starts last, so a shared registry would give its policy to the composite one.
    plain.start();
    try (S3Client compositeClient = client(composite); S3Client plainClient = client(plain)) {
      assertTrue(completeWithSinglePart(compositeClient).endsWith("-1\""));
      assertFalse(completeWithSinglePart(plainClient).contains("-"));
    } finally {
      plain.shutdown();
      composite.shutdown();
    }
  }

  private static String completeWithSinglePart(S3Client s3) {
    String bucket = "parts-bucket";
    String key = "target.txt";
    s3.createBucket(b -> b.bucket(bucket));
    String uploadId = s3.createMultipartUpload(b -> b.bucket(bucket).key(key)).uploadId();
    UploadPartResponse only = s3.uploadPart(b -> b.bucket(bucket).key(key).uploadId(uploadId).partNumber(1),
        RequestBody.fromString("only"));
    return s3.completeMultipartUpload(b -> b.bucket(bucket).key(key).uploadId(uploadId)
        .multipartUpload(CompletedMultipartUpload.builder().parts(
            CompletedPart.builder().partNumber(1).eTag(only.eTag()).build()).build())).eTag();
  }

}
