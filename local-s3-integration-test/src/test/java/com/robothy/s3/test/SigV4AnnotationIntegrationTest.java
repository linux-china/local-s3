package com.robothy.s3.test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import com.robothy.s3.jupiter.LocalS3;
import com.robothy.s3.jupiter.LocalS3Endpoint;
import org.junit.jupiter.api.Nested;
import java.net.URI;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3vectors.S3VectorsClient;
import software.amazon.awssdk.services.s3vectors.model.S3VectorsException;

/**
 * {@code @LocalS3} turns AWS Signature Version 4 verification on when it is given credentials, and the
 * clients it injects are signed with them. Without that, the one feature a test of signing needs can only be
 * reached by building the service by hand, which is what
 * {@linkplain SigV4AuthenticationIntegrationTest} does.
 *
 * <p>Every test here asserts both halves: that the injected client is accepted, and that a client with other
 * credentials is rejected. The second half is what tells an accepted client apart from a service that
 * verifies nothing.
 */
class SigV4AnnotationIntegrationTest {

  private static final String ACCESS_KEY = "an-access-key";

  private static final String SECRET_KEY = "a-secret-key";

  @Test
  @LocalS3(accessKey = ACCESS_KEY, secretKey = SECRET_KEY)
  void anInjectedClientIsSignedWithTheConfiguredCredentials(S3Client s3, LocalS3Endpoint endpoint) {
    assertDoesNotThrow(() -> s3.createBucket(request -> request.bucket("signed-bucket")));

    try (S3Client wrongSecret = client(endpoint, ACCESS_KEY, "another-secret")) {
      S3Exception rejected = assertThrows(S3Exception.class, wrongSecret::listBuckets);
      assertEquals("SignatureDoesNotMatch", rejected.awsErrorDetails().errorCode());
    }

    try (S3Client unknownKey = client(endpoint, "another-access-key", SECRET_KEY)) {
      S3Exception rejected = assertThrows(S3Exception.class, unknownKey::listBuckets);
      assertEquals("InvalidAccessKeyId", rejected.awsErrorDetails().errorCode());
    }

    try (S3Client anonymous = anonymousClient(endpoint)) {
      S3Exception rejected = assertThrows(S3Exception.class, anonymous::listBuckets);
      assertEquals("AccessDenied", rejected.awsErrorDetails().errorCode());
    }
  }

  /**
   * The injected {@linkplain S3VectorsClient} is signed as well, so a test of the S3 Vectors API reaches
   * the service that verifies signatures the same way.
   */
  @Test
  @LocalS3(accessKey = ACCESS_KEY, secretKey = SECRET_KEY)
  void anInjectedVectorsClientIsSignedWithTheConfiguredCredentials(S3VectorsClient vectors,
                                                                   LocalS3Endpoint endpoint) {
    assertDoesNotThrow(() -> vectors.createVectorBucket(request -> request.vectorBucketName("signed-vectors")));

    try (S3VectorsClient wrongSecret = vectorsClient(endpoint, ACCESS_KEY, "another-secret")) {
      assertThrows(S3VectorsException.class, () -> wrongSecret.listVectorBuckets(request -> request.maxResults(1)));
    }
  }

  /**
   * A service that was given no credentials verifies nothing, which is what every test that says nothing
   * about signing relies on: the injected client is anonymous and is accepted.
   */
  @Test
  @LocalS3
  void aServiceWithoutCredentialsVerifiesNothing(S3Client s3, LocalS3Endpoint endpoint) {
    assertDoesNotThrow(() -> s3.createBucket(request -> request.bucket("unsigned-bucket")));

    // Signed with credentials the service was never given, which it has no reason to reject.
    try (S3Client signed = client(endpoint, ACCESS_KEY, SECRET_KEY)) {
      assertDoesNotThrow(() -> signed.listBuckets());
    }
  }

  /**
   * The credentials of a class annotation reach the clients of its tests, like the service they are
   * launched against does.
   */
  @Nested
  @LocalS3(accessKey = ACCESS_KEY, secretKey = SECRET_KEY)
  class OnTheTestClass {

    @Test
    void anInjectedClientIsSigned(S3Client s3, LocalS3Endpoint endpoint) {
      assertDoesNotThrow(() -> s3.createBucket(request -> request.bucket("class-signed-bucket")));

      try (S3Client wrongSecret = client(endpoint, ACCESS_KEY, "another-secret")) {
        S3Exception rejected = assertThrows(S3Exception.class, wrongSecret::listBuckets);
        assertEquals("SignatureDoesNotMatch", rejected.awsErrorDetails().errorCode());
      }
    }

  }

  private static S3Client client(LocalS3Endpoint endpoint, String accessKey, String secretKey) {
    return client(endpoint, StaticCredentialsProvider.create(
        AwsBasicCredentials.create(accessKey, secretKey)));
  }

  private static S3Client anonymousClient(LocalS3Endpoint endpoint) {
    return client(endpoint, AnonymousCredentialsProvider.create());
  }

  private static S3Client client(LocalS3Endpoint endpoint, AwsCredentialsProvider credentials) {
    return S3Client.builder()
        .forcePathStyle(true)
        .endpointOverride(URI.create("http://localhost:" + endpoint.port()))
        .region(Region.of("local"))
        .credentialsProvider(credentials)
        .build();
  }

  private static S3VectorsClient vectorsClient(LocalS3Endpoint endpoint, String accessKey, String secretKey) {
    return S3VectorsClient.builder()
        .endpointOverride(URI.create("http://localhost:" + endpoint.port()))
        .region(Region.of("local"))
        .credentialsProvider(StaticCredentialsProvider.create(
            AwsBasicCredentials.create(accessKey, secretKey)))
        .build();
  }

}
