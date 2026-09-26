package com.robothy.s3.spring.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.rest.LocalS3;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.TestPropertySource;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * A plain {@code @SpringBootTest}, without {@code @AutoConfigureLocalS3}, embeds the service on a random free port
 * unless {@code local-s3.port} is set, so that the contexts that the test context framework caches side by side, e.g.
 * the one of the nested class, don't compete for the default port.
 */
@SpringBootTest(classes = LocalS3SpringBootTestPortTest.Application.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "local-s3.buckets=outer")
class LocalS3SpringBootTestPortTest {

  static LocalS3 outerLocalS3;

  @Autowired
  LocalS3 localS3;

  @Autowired
  S3Client s3;

  @Value("${local.s3.endpoint}")
  String endpoint;

  @Test
  void listensOnARandomPort() {
    outerLocalS3 = localS3;
    assertTrue(localS3.isRunning());
    assertNotEquals(29090, localS3.getPort());
    assertEquals("http://127.0.0.1:" + localS3.getPort(), endpoint);
    s3.putObject(request -> request.bucket("outer").key("a.txt"), RequestBody.fromString("a"));
    assertEquals("a", s3.getObjectAsBytes(request -> request.bucket("outer").key("a.txt")).asUtf8String());
  }

  /**
   * A context of its own, started while the one of the enclosing class is cached and running.
   */
  @Nested
  @TestPropertySource(properties = "local-s3.buckets=nested")
  class AnotherContext {

    @Autowired
    LocalS3 nestedLocalS3;

    @Test
    void runsBesideTheCachedContext() {
      assertTrue(nestedLocalS3.isRunning());
      assertNotEquals(29090, nestedLocalS3.getPort());
      if (outerLocalS3 != null) {
        assertNotSame(outerLocalS3, nestedLocalS3);
        assertTrue(outerLocalS3.isRunning(), "Both services run side by side.");
        assertNotEquals(outerLocalS3.getPort(), nestedLocalS3.getPort());
      }
    }
  }

  @Test
  void aConfiguredPortIsKeptAndOnlyATestPicksARandomOne() {
    MockEnvironment application = new MockEnvironment();
    assertFalse(LocalS3AutoConfiguration.randomPortForTest(application), "An application keeps the default port.");

    MockEnvironment test = new MockEnvironment()
        .withProperty(LocalS3AutoConfiguration.SPRING_BOOT_TEST_PROPERTY, "true");
    assertTrue(LocalS3AutoConfiguration.randomPortForTest(test));

    assertFalse(LocalS3AutoConfiguration.randomPortForTest(new MockEnvironment()
        .withProperty(LocalS3AutoConfiguration.SPRING_BOOT_TEST_PROPERTY, "true")
        .withProperty("local-s3.port", "29090")), "A port that the test sets is kept, the default one included.");

    MockEnvironment fromVariable = new MockEnvironment()
        .withProperty(LocalS3AutoConfiguration.SPRING_BOOT_TEST_PROPERTY, "true");
    fromVariable.getPropertySources().addFirst(new SystemEnvironmentPropertySource("systemEnvironment",
        Map.of("LOCAL_S3_PORT", "29095")));
    assertFalse(LocalS3AutoConfiguration.randomPortForTest(fromVariable), "So is one of an environment variable.");
  }

  @SpringBootApplication
  static class Application {
  }

}
