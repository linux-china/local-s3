package com.robothy.s3.rest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The content of an {@code IN_MEMORY} service is bounded, so that uploading more than the heap holds is answered with
 * an error instead of running the JVM that embeds the service out of heap.
 */
class InMemoryMaxBytesTest {

  @Test
  void anUploadBeyondTheLimitIsInsufficientStorage() throws Exception {
    LocalS3 localS3 = LocalS3.builder().port(-1).buckets("bucket")
        .storage(storage -> storage.maxInMemoryBytes(1024)).build();
    localS3.start();
    try {
      HttpClient client = HttpClient.newHttpClient();
      assertEquals(200, put(client, localS3, "a.bin", new byte[1024]).statusCode());

      HttpResponse<String> rejected = put(client, localS3, "b.bin", new byte[1]);
      assertEquals(507, rejected.statusCode(), rejected.body());
      assertTrue(rejected.body().contains("<Code>InsufficientStorage</Code>"), rejected.body());
      assertTrue(rejected.body().contains("PERSISTENCE"), rejected.body());

      HttpResponse<String> deleted = client.send(HttpRequest.newBuilder(uri(localS3, "a.bin")).DELETE().build(),
          HttpResponse.BodyHandlers.ofString());
      assertEquals(204, deleted.statusCode());
      assertEquals(200, put(client, localS3, "b.bin", new byte[1024]).statusCode(), "Deleting frees the space.");
    } finally {
      localS3.shutdown();
    }
  }

  /**
   * A body larger than the request body file threshold is received into the heap for the storage, rather than into a
   * temporary file: it is stored as it was received, and one that can't fit is refused before it is uploaded.
   */
  @Test
  void aLargeUploadIsReceivedIntoTheBudgetOfTheStorage() throws Exception {
    int large = 6 * 1024 * 1024;
    LocalS3 localS3 = LocalS3.builder().port(-1).buckets("bucket")
        .storage(storage -> storage.maxInMemoryBytes(2L * large)).build();
    localS3.start();
    try {
      HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
      byte[] content = new byte[large];
      new Random(42).nextBytes(content);
      assertEquals(200, put(client, localS3, "a.bin", content).statusCode());
      HttpResponse<byte[]> read = client.send(HttpRequest.newBuilder(uri(localS3, "a.bin")).GET().build(),
          HttpResponse.BodyHandlers.ofByteArray());
      assertArrayEquals(content, read.body());

      // The client waits for 100 Continue, which a body that can't fit never gets.
      HttpResponse<String> rejected = client.send(HttpRequest.newBuilder(uri(localS3, "b.bin"))
              .expectContinue(true)
              .PUT(HttpRequest.BodyPublishers.ofByteArray(new byte[large + 1])).build(),
          HttpResponse.BodyHandlers.ofString());
      assertEquals(507, rejected.statusCode(), rejected.body());
      assertTrue(rejected.body().contains("<Code>InsufficientStorage</Code>"), rejected.body());

      assertEquals(200, put(client, localS3, "b.bin", content).statusCode(),
          "The stored content takes its space once, and the refused body none.");
    } finally {
      localS3.shutdown();
    }
  }

  @Test
  void theLimitIsConfiguredFromTheEnvironment() {
    assertEquals(512L * 1024 * 1024, LocalS3.builder()
        .fromEnvironment(Map.of(LocalS3Environment.LOCAL_S3_IN_MEMORY_MAX_BYTES, "512m")::get)
        .buildConfig().maxInMemoryBytes());
    assertEquals(2048, LocalS3.builder()
        .fromEnvironment(Map.of(LocalS3Environment.LOCAL_S3_IN_MEMORY_MAX_BYTES, "2048")::get)
        .buildConfig().maxInMemoryBytes());
    assertEquals(LocalS3Config.DEFAULT_MAX_IN_MEMORY_BYTES, LocalS3.builder().buildConfig().maxInMemoryBytes());
    for (String invalid : new String[] {"0", "-1", "lots", "9999999999g"}) {
      assertThrows(IllegalArgumentException.class, () -> LocalS3.builder()
          .fromEnvironment(Map.of(LocalS3Environment.LOCAL_S3_IN_MEMORY_MAX_BYTES, invalid)::get), invalid);
    }
    assertThrows(IllegalArgumentException.class,
        () -> LocalS3.builder().storage(storage -> storage.maxInMemoryBytes(0)));
  }

  private static HttpResponse<String> put(HttpClient client, LocalS3 localS3, String key, byte[] content)
      throws Exception {
    return client.send(HttpRequest.newBuilder(uri(localS3, key))
        .PUT(HttpRequest.BodyPublishers.ofByteArray(content)).build(), HttpResponse.BodyHandlers.ofString());
  }

  private static URI uri(LocalS3 localS3, String key) {
    return URI.create("http://127.0.0.1:" + localS3.getPort() + "/bucket/" + key);
  }

}
