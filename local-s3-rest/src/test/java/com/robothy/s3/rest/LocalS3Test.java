package com.robothy.s3.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LocalS3Test {

  @Test
  void start() throws Exception {
    LocalS3 localS3 = LocalS3.builder()
        .bindHost("127.0.0.1")
        .port(19090)
        .build();
    localS3.start();
    localS3.shutdown();

    Path tempDirectory = Files.createTempDirectory("local-s3");
    localS3 = LocalS3.builder()
        .port(19090)
        .dataPath(tempDirectory.toAbsolutePath().toString())
        .build();
    localS3.start();
    localS3.shutdown();
    localS3.shutdown();
  }

  @Test
  void testBuilder() {
    LocalS3 localS3 = LocalS3.builder()
        .dataPath("/tmp")
        .build();
    assertEquals("127.0.0.1", localS3.getBindHost());
    assertTrue(localS3.getDataPath().endsWith("tmp"));
    assertTrue(localS3.getPort() > 0);

    LocalS3 loopbackOnly = LocalS3.builder()
        .bindHost("127.0.0.1")
        .build();
    assertEquals("127.0.0.1", loopbackOnly.getBindHost());
    assertThrows(IllegalArgumentException.class, () -> LocalS3.builder().bindHost(" "));
  }

}