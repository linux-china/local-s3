package com.robothy.s3.spring.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.rest.LocalS3;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * A Spring Boot application finds the auto-configurations of the starter by itself.
 */
class LocalS3SpringApplicationTest {

  @Test
  void anApplicationEmbedsLocalS3ThroughTheStarter() {
    LocalS3 localS3;
    try (ConfigurableApplicationContext context = new SpringApplicationBuilder(Application.class)
        .web(WebApplicationType.NONE)
        .properties("local-s3.port=0", "local-s3.buckets=app")
        .run()) {
      localS3 = context.getBean(LocalS3.class);
      assertTrue(localS3.isRunning());
      S3Client s3 = context.getBean(S3Client.class);
      s3.putObject(request -> request.bucket("app").key("hello.txt"), RequestBody.fromString("Hello Spring"));
      assertEquals("Hello Spring", s3.getObjectAsBytes(request -> request.bucket("app").key("hello.txt")).asUtf8String());
      assertEquals(1, context.getBeansOfType(S3AsyncClient.class).size());
      assertEquals(1, context.getBeansOfType(S3Presigner.class).size());
      assertTrue(context.containsBean("localS3HealthIndicator"));
      assertTrue(context.containsBean("localS3Metrics"));
    }
    assertFalse(localS3.isRunning(), "Closing the application stops the service.");
  }

  @SpringBootApplication
  static class Application {
  }

}
