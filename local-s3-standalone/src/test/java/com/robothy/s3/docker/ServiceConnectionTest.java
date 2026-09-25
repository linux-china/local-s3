package com.robothy.s3.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import com.robothy.s3.testcontainers.LocalS3Container;
import io.awspring.cloud.s3.S3Template;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * {@code @ServiceConnection} points the clients of Spring Cloud AWS at a {@linkplain LocalS3Container}, with no
 * property of the application: no endpoint, credentials, region or path-style access.
 */
@Tag(ImageUnderTest.JUNIT_TAG)
@Testcontainers
@SpringBootTest(classes = ServiceConnectionTest.Application.class)
class ServiceConnectionTest {

  @Container
  @ServiceConnection
  static LocalS3Container localS3 = new LocalS3Container(ImageUnderTest.TAG)
      .withCredentials("service-connection", "service-connection-secret")
      .withBuckets("service-connection-bucket");

  @Autowired
  S3Client s3Client;

  @Autowired
  S3Template s3Template;

  @Test
  void theClientsOfSpringCloudAwsReachTheContainer() throws IOException {
    s3Template.upload("service-connection-bucket", "hello.txt",
        new ByteArrayInputStream("Hello, LocalS3".getBytes(StandardCharsets.UTF_8)));

    assertEquals("Hello, LocalS3", s3Client.getObjectAsBytes(b -> b.bucket("service-connection-bucket")
        .key("hello.txt")).asUtf8String());
    assertEquals("Hello, LocalS3", s3Template.download("service-connection-bucket", "hello.txt")
        .getContentAsString(StandardCharsets.UTF_8));
  }

  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration
  static class Application {
  }

}
