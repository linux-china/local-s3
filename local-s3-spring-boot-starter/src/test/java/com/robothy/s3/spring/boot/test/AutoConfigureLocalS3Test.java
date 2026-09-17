package com.robothy.s3.spring.boot.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.robothy.s3.rest.LocalS3;
import com.robothy.s3.rest.bootstrap.LocalS3Mode;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * {@linkplain AutoConfigureLocalS3} embeds a service on a random port in a context without auto-configuration, and
 * resets its data after each test method.
 */
@SpringBootTest(classes = AutoConfigureLocalS3Test.Config.class,
    properties = {"local-s3.port=29090", "local-s3.buckets=tests"})
@AutoConfigureLocalS3
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AutoConfigureLocalS3Test {

  /**
   * The service of the context of this class, kept for the nested class, whose enclosing instance is injected from the
   * nested context.
   */
  static LocalS3 outerLocalS3;

  @Autowired
  LocalS3 localS3;

  @Autowired
  S3Client s3;

  @Test
  @Order(1)
  void embedsAnInMemoryServiceOnARandomPort() {
    outerLocalS3 = localS3;
    assertTrue(localS3.isRunning());
    assertEquals(LocalS3Mode.IN_MEMORY, localS3.getMode());
    assertNotEquals(29090, localS3.getPort(), "The annotation overrides the port of the application.");
    s3.putObject(request -> request.bucket("tests").key("a.txt"), RequestBody.fromString("a"));
    s3.createBucket(request -> request.bucket("created"));
    assertEquals(1, s3.listObjectsV2(request -> request.bucket("tests")).keyCount());
  }

  @Test
  @Order(2)
  void resetsTheDataAfterEachTest() {
    assertEquals(0, s3.listObjectsV2(request -> request.bucket("tests")).keyCount());
    assertEquals(1, s3.listBuckets().buckets().size(), "Only the default bucket is created again.");
  }

  /**
   * A context of its own, which the test context framework caches beside the one of the enclosing class.
   */
  @Nested
  @TestPropertySource(properties = "local-s3.buckets=nested")
  @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
  class AnotherContext {

    @Autowired
    LocalS3 nestedLocalS3;

    @Autowired
    S3Client nestedS3;

    @Test
    @Order(1)
    void runsBesideTheServiceOfTheOtherContext() {
      assumeTrue(outerLocalS3 != null, "The tests of the enclosing class run first.");
      assertNotSame(outerLocalS3, nestedLocalS3);
      assertTrue(outerLocalS3.isRunning(), "The cached context of the enclosing class is still open.");
      assertTrue(nestedLocalS3.isRunning());
      assertNotEquals(outerLocalS3.getPort(), nestedLocalS3.getPort());
      nestedS3.putObject(request -> request.bucket("nested").key("b.txt"), RequestBody.fromString("b"));
    }

    @Test
    @Order(2)
    void isResetToo() {
      assertEquals(0, nestedS3.listObjectsV2(request -> request.bucket("nested")).keyCount());
    }

  }

  @Nested
  @AutoConfigureLocalS3(reset = false)
  @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
  class WithoutReset {

    @Autowired
    S3Client keptS3;

    @Test
    @Order(1)
    void putsAnObject() {
      keptS3.putObject(request -> request.bucket("tests").key("kept.txt"), RequestBody.fromString("kept"));
    }

    @Test
    @Order(2)
    void keepsTheData() {
      assertEquals("kept", keptS3.getObjectAsBytes(request -> request.bucket("tests").key("kept.txt")).asUtf8String());
      keptS3.deleteObject(request -> request.bucket("tests").key("kept.txt"));
    }

  }

  @SpringBootConfiguration
  static class Config {
  }

}
