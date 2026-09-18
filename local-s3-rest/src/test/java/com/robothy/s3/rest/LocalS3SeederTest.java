package com.robothy.s3.rest;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.core.exception.InvalidBucketNameException;
import com.robothy.s3.core.service.BucketService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The {@linkplain LocalS3Seeder seeders} that put the initial buckets and objects of a service into it.
 */
class LocalS3SeederTest {

  @Test
  void seedsTheInitialObjectsWhenTheServiceStartsAndAgainWhenItIsReset(@TempDir Path directory) throws Exception {
    Path file = Files.writeString(directory.resolve("from-file.txt"), "From a file.");
    LocalS3 localS3 = LocalS3.builder()
        .port(-1)
        .seeder(fixtures -> {
          fixtures.bucket("an-empty-bucket");
          fixtures.object("fixtures", "hello.txt", "Hello!".getBytes(UTF_8));
          fixtures.object("fixtures", "nested/from-file.txt", file);
          fixtures.object("fixtures", "from-stream.txt",
              new ByteArrayInputStream("From a stream.".getBytes(UTF_8)), 14, "text/plain");
        })
        .build();
    localS3.start();
    try {
      BucketService bucketService = localS3.getS3Manager().bucketService();
      assertDoesNotThrow(() -> bucketService.getBucket("an-empty-bucket"));
      assertDoesNotThrow(() -> bucketService.getBucket("fixtures"),
          "The bucket of a seeded object is created if the seeder doesn't create it.");
      assertEquals("Hello!", get(localS3, "/fixtures/hello.txt"));
      assertEquals("From a file.", get(localS3, "/fixtures/nested/from-file.txt"));
      assertEquals("From a stream.", get(localS3, "/fixtures/from-stream.txt"));

      HttpClient client = HttpClient.newHttpClient();
      client.send(HttpRequest.newBuilder(endpoint(localS3, "/fixtures/hello.txt")).DELETE().build(),
          HttpResponse.BodyHandlers.discarding());
      assertEquals(404, status(localS3, "/fixtures/hello.txt"));

      localS3.reset();

      assertEquals("Hello!", get(localS3, "/fixtures/hello.txt"),
          "A reset puts the seeded objects back, like it creates the default buckets again.");
      assertTrue(file.toFile().exists(), "The file of a fixture is read, not taken over by the storage.");
    } finally {
      localS3.shutdown();
    }
  }

  @Test
  void aSeederThatFailsFailsTheStartOfTheService() {
    LocalS3 localS3 = LocalS3.builder()
        .port(-1)
        .seeder(fixtures -> {
          throw new IOException("No fixtures.");
        })
        .build();

    UncheckedIOException failure = assertThrows(UncheckedIOException.class, localS3::start);

    assertEquals("No fixtures.", failure.getCause().getMessage());
    assertEquals(0, localS3.getPort(), "The server of a service that failed to start listens on no port.");
  }

  @Test
  void anInvalidBucketNameOfASeederFailsTheStartOfTheService() {
    LocalS3 localS3 = LocalS3.builder()
        .port(-1)
        .seeder(fixtures -> fixtures.object("An_Invalid_Bucket", "a.txt", new byte[0]))
        .build();

    assertThrows(InvalidBucketNameException.class, localS3::start);
  }

  private static URI endpoint(LocalS3 localS3, String path) {
    return URI.create("http://127.0.0.1:" + localS3.getPort() + path);
  }

  private static String get(LocalS3 localS3, String path) throws Exception {
    return HttpClient.newHttpClient().send(HttpRequest.newBuilder(endpoint(localS3, path)).build(),
        HttpResponse.BodyHandlers.ofString()).body();
  }

  private static int status(LocalS3 localS3, String path) throws Exception {
    return HttpClient.newHttpClient().send(HttpRequest.newBuilder(endpoint(localS3, path)).build(),
        HttpResponse.BodyHandlers.discarding()).statusCode();
  }

}
