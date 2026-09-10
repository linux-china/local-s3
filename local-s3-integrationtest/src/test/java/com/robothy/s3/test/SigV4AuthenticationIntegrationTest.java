package com.robothy.s3.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.robothy.s3.rest.LocalS3;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.auth.signer.AwsS3V4Signer;
import software.amazon.awssdk.auth.signer.S3SignerExecutionAttribute;
import software.amazon.awssdk.core.client.config.SdkAdvancedClientOption;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

class SigV4AuthenticationIntegrationTest {

  private static final String ACCESS_KEY_ID = "local-access-key";
  private static final String SECRET_ACCESS_KEY = "local-secret-access-key";
  private static final Region REGION = Region.of("local");

  @Test
  void shouldAuthenticateSignedAndPresignedRequests() throws Exception {
    LocalS3 localS3 = LocalS3.builder()
        .port(-1)
        .credentials(ACCESS_KEY_ID, SECRET_ACCESS_KEY)
        .build();
    localS3.start();
    URI endpoint = URI.create("http://localhost:" + localS3.getPort());

    try (S3Client client = client(endpoint, credentials(ACCESS_KEY_ID, SECRET_ACCESS_KEY));
         S3Client invalidClient = client(endpoint, credentials(ACCESS_KEY_ID, "wrong-secret"));
         S3Client unknownKeyClient = client(endpoint, credentials("unknown-key", SECRET_ACCESS_KEY));
         S3Client anonymousClient = client(endpoint, AnonymousCredentialsProvider.create());
         S3Presigner presigner = presigner(endpoint)) {
      String bucket = "signed-bucket";
      String key = "hello+world.txt";
      client.createBucket(request -> request.bucket(bucket));
      client.putObject(request -> request.bucket(bucket).key(key), RequestBody.fromString("Hello"));
      client.putObject(request -> request.bucket(bucket).key("signed-stream.txt")
              .overrideConfiguration(configuration -> {
                configuration.signer(AwsS3V4Signer.create());
                configuration.executionAttributes()
                    .putAttribute(S3SignerExecutionAttribute.ENABLE_PAYLOAD_SIGNING, true)
                    .putAttribute(S3SignerExecutionAttribute.ENABLE_CHUNKED_ENCODING, true);
              }),
          RequestBody.fromString("Signed stream"));

      assertEquals(1, client.listBuckets().buckets().size());

      S3Exception invalidSignature = assertThrows(S3Exception.class,
          () -> invalidClient.listBuckets());
      assertEquals("SignatureDoesNotMatch", invalidSignature.awsErrorDetails().errorCode());

      S3Exception unknownAccessKey = assertThrows(S3Exception.class,
          () -> unknownKeyClient.listBuckets());
      assertEquals("InvalidAccessKeyId", unknownAccessKey.awsErrorDetails().errorCode());

      S3Exception missingSignature = assertThrows(S3Exception.class,
          () -> anonymousClient.listBuckets());
      assertEquals("AccessDenied", missingSignature.awsErrorDetails().errorCode());

      PresignedGetObjectRequest presigned = presigner.presignGetObject(
          GetObjectPresignRequest.builder()
              .signatureDuration(Duration.ofMinutes(5))
              .getObjectRequest(request -> request.bucket(bucket).key(key))
              .build());
      HttpResponse<String> response = HttpClient.newHttpClient().send(
          HttpRequest.newBuilder(presigned.url().toURI()).GET().build(),
          HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      assertEquals("Hello", response.body());
    } finally {
      localS3.shutdown();
    }
  }

  private static AwsCredentialsProvider credentials(String accessKeyId, String secretAccessKey) {
    return StaticCredentialsProvider.create(
        AwsBasicCredentials.create(accessKeyId, secretAccessKey));
  }

  private static S3Client client(URI endpoint, AwsCredentialsProvider credentialsProvider) {
    return S3Client.builder()
        .endpointOverride(endpoint)
        .region(REGION)
        .credentialsProvider(credentialsProvider)
        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
        .overrideConfiguration(configuration -> configuration
            .putAdvancedOption(SdkAdvancedClientOption.DISABLE_HOST_PREFIX_INJECTION, true))
        .build();
  }

  private static S3Presigner presigner(URI endpoint) {
    return S3Presigner.builder()
        .endpointOverride(endpoint)
        .region(REGION)
        .credentialsProvider(credentials(ACCESS_KEY_ID, SECRET_ACCESS_KEY))
        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
        .build();
  }
}
