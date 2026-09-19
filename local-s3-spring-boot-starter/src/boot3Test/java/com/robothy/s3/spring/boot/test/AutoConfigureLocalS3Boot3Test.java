package com.robothy.s3.spring.boot.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.rest.LocalS3;
import com.robothy.s3.rest.bootstrap.LocalS3Mode;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * {@linkplain AutoConfigureLocalS3} on Spring Boot 3: its attributes reach the application through the
 * {@code @PropertyMapping} of {@code spring-boot-test-autoconfigure}, and
 * {@linkplain LocalS3ResetTestExecutionListener}, which {@code META-INF/spring.factories} registers, resets the
 * service after each test method.
 */
@SpringBootTest(classes = AutoConfigureLocalS3Boot3Test.Config.class,
    properties = {"local-s3.port=29090", "local-s3.buckets=tests"})
@AutoConfigureLocalS3
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AutoConfigureLocalS3Boot3Test {

  @Autowired
  LocalS3 localS3;

  @Autowired
  S3Client s3;

  @Test
  @Order(1)
  void embedsAnInMemoryServiceOnARandomPort() {
    assertTrue(localS3.isRunning());
    assertEquals(LocalS3Mode.IN_MEMORY, localS3.getMode());
    assertNotEquals(29090, localS3.getPort(), "The annotation overrides the port of the application.");
    s3.putObject(request -> request.bucket("tests").key("a.txt"), RequestBody.fromString("a"));
    assertEquals(1, s3.listObjectsV2(request -> request.bucket("tests")).keyCount());
  }

  @Test
  @Order(2)
  void resetsTheDataAfterEachTest() {
    assertEquals(0, s3.listObjectsV2(request -> request.bucket("tests")).keyCount());
  }

  @SpringBootConfiguration
  static class Config {
  }

}
