package com.robothy.s3.docker;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.testcontainers.LocalS3Container;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * What {@linkplain LocalS3Container} configures the image with, asserted against a running container: the port it is
 * reached at, and the settings that a test would otherwise have to spell as environment variables.
 */
@Tag(ImageUnderTest.JUNIT_TAG)
class ContainerConfigurationTest {

  /**
   * The host port is Docker's to allocate, so that test classes running at the same time can't pick the same one, and
   * {@linkplain LocalS3Container#getEndpoint()} is the URL it ends up at.
   */
  @Test
  void isReachedAtThePortThatDockerAllocated() {
    try (LocalS3Container container = new LocalS3Container(ImageUnderTest.TAG)
        .withMode(LocalS3Container.Mode.IN_MEMORY)) {
      container.start();

      assertNotEquals(0, container.getPort());
      assertEquals(container.getMappedPort(LocalS3Container.S3_PORT), container.getPort());
      assertEquals("http://" + container.getHost() + ":" + container.getPort(), container.getEndpoint());

      try (S3Client s3 = client(container, "any", "any")) {
        assertDoesNotThrow(() -> s3.createBucket(b -> b.bucket("my-bucket")));
      }
    }
  }

  /**
   * Two containers of the same test run side by side, which is what a fixed port would prevent.
   */
  @Test
  void allocatesAPortOfItsOwnToEveryContainer() {
    try (LocalS3Container one = new LocalS3Container(ImageUnderTest.TAG).withMode(LocalS3Container.Mode.IN_MEMORY);
         LocalS3Container two = new LocalS3Container(ImageUnderTest.TAG).withMode(LocalS3Container.Mode.IN_MEMORY)) {
      one.start();
      two.start();
      assertNotEquals(one.getPort(), two.getPort());
    }
  }

  @Test
  void requiresTheCredentialsItConfiguredAndCreatesTheBucketsItNamed() {
    try (LocalS3Container container = new LocalS3Container(ImageUnderTest.TAG)
        .withMode(LocalS3Container.Mode.IN_MEMORY)
        .withCredentials("local-s3", "local-s3-secret")
        .withBuckets("seeded", "seeded-too")) {
      container.start();

      try (S3Client s3 = client(container, container.getAccessKey(), container.getSecretKey())) {
        assertDoesNotThrow(() -> s3.headBucket(b -> b.bucket("seeded")));
        assertDoesNotThrow(() -> s3.headBucket(b -> b.bucket("seeded-too")));
      }

      try (S3Client s3 = client(container, "wrong", "wrong")) {
        assertThrows(S3Exception.class, () -> s3.headBucket(b -> b.bucket("seeded")),
            "The service requires the credentials that the container configured.");
      }
    }
  }

  @Test
  void servesTheIcebergCatalogItTurnedOn() throws Exception {
    try (LocalS3Container container = new LocalS3Container(ImageUnderTest.TAG)
        .withMode(LocalS3Container.Mode.IN_MEMORY)
        .withIcebergCatalog(true)
        .withIcebergWarehouse("s3://warehouse/")) {
      container.start();

      HttpResponse<String> response = HttpClient.newHttpClient().send(
          HttpRequest.newBuilder(URI.create(container.getEndpoint() + "/iceberg/v1/config")).GET().build(),
          HttpResponse.BodyHandlers.ofString());

      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains("s3://warehouse"), () -> "Unexpected config: " + response.body());
    }
  }

  private static S3Client client(LocalS3Container container, String accessKey, String secretKey) {
    return S3Client.builder()
        .endpointOverride(container.getEndpointUri())
        .forcePathStyle(true)
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
        .region(Region.US_EAST_1)
        .build();
  }

}
