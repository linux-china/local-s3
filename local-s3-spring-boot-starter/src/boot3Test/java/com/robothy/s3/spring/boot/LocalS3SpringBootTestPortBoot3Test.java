package com.robothy.s3.spring.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.rest.LocalS3;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * On Spring Boot 3 as well, a plain {@code @SpringBootTest} embeds the service on a random free port unless
 * {@code local-s3.port} is set: the test context bootstrapper of Spring Boot 3 marks the environment the same way.
 */
@SpringBootTest(classes = LocalS3SpringBootTestPortBoot3Test.Config.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "local-s3.buckets=boot3")
class LocalS3SpringBootTestPortBoot3Test {

  @Autowired
  LocalS3 localS3;

  @Autowired
  S3Client s3;

  @Value("${local.s3.endpoint}")
  String endpoint;

  @Test
  void listensOnARandomPort() {
    assertTrue(localS3.isRunning());
    assertNotEquals(29090, localS3.getPort());
    assertEquals("http://127.0.0.1:" + localS3.getPort(), endpoint);
    s3.putObject(request -> request.bucket("boot3").key("a.txt"), RequestBody.fromString("a"));
    assertEquals(1, s3.listObjectsV2(request -> request.bucket("boot3")).keyCount());
  }

  @SpringBootConfiguration
  @ImportAutoConfiguration(LocalS3AutoConfiguration.class)
  static class Config {
  }

}
