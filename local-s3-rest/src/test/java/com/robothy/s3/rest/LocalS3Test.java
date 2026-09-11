package com.robothy.s3.rest;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.jupiter.api.Test;

class LocalS3Test {

  @Test
  void start() throws Exception {
    LocalS3 localS3 = LocalS3.builder()
        .bindHost("127.0.0.1")
        .port(-1)
        .build();
    localS3.start();
    localS3.shutdown();

    Path tempDirectory = Files.createTempDirectory("local-s3");
    localS3 = LocalS3.builder()
        .port(-1)
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
    assertEquals(LocalS3.DEFAULT_MAX_REQUEST_BODY_SIZE, localS3.getMaxRequestBodySize());
    assertThrows(IllegalArgumentException.class, () -> LocalS3.builder().maxRequestBodySize(0));
    assertThrows(IllegalArgumentException.class, () -> LocalS3.builder().maxRequestBodySize(Integer.MAX_VALUE + 1L));
  }

  @Test
  void rejectsRequestBodyLargerThanLimit() throws Exception {
    LocalS3 localS3 = LocalS3.builder()
        .port(-1)
        .maxRequestBodySize(1024)
        .buckets("limit-bucket")
        .build();
    localS3.start();
    try {
      HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
      String objectUrl = "http://127.0.0.1:" + localS3.getPort() + "/limit-bucket/";

      HttpResponse<String> accepted = client.send(HttpRequest.newBuilder(URI.create(objectUrl + "small"))
          .PUT(HttpRequest.BodyPublishers.ofByteArray(new byte[1024])).build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(200, accepted.statusCode());

      HttpResponse<String> rejected = client.send(HttpRequest.newBuilder(URI.create(objectUrl + "large"))
          .PUT(HttpRequest.BodyPublishers.ofByteArray(new byte[1025])).build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(400, rejected.statusCode());
      assertTrue(rejected.body().contains("EntityTooLarge"));
    } finally {
      localS3.shutdown();
    }
  }

  @Test
  void shutdownRemovesShutdownHook() throws Exception {
    LocalS3 localS3 = LocalS3.builder().port(-1).build();
    localS3.start();
    Thread hook = (Thread) FieldUtils.readField(localS3, "shutdownHook", true);
    assertNotNull(hook);

    localS3.shutdown();

    assertFalse(Runtime.getRuntime().removeShutdownHook(hook), "The shutdown hook should already be removed.");
    assertDoesNotThrow(localS3::shutdown);
  }
}
