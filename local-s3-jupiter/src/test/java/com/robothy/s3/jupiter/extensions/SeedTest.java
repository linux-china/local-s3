package com.robothy.s3.jupiter.extensions;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.robothy.s3.jupiter.LocalS3;
import com.robothy.s3.rest.LocalS3Seeder;
import java.io.FileNotFoundException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Bucket;

class SeedTest {

  @Test
  @LocalS3(seed = "classpath:fixtures/s3")
  void seedsClasspathTree(S3Client s3) {
    assertEquals("Hello!", s3.getObjectAsBytes(b -> b.bucket("uploads").key("hello.txt")).asString(UTF_8));
    assertEquals("image/png", s3.headObject(b -> b.bucket("uploads").key("images/logo.png")).contentType());
    assertEquals("a,b\n1,2\n", s3.getObjectAsBytes(b -> b.bucket("reports").key("2026.csv")).asString(UTF_8));
    // README.md directly in the tree names no bucket.
    assertEquals(2, s3.listBuckets().buckets().size());
  }

  @Test
  @LocalS3(seed = "classpath:/fixtures/s3/", seeders = SeedTest.CodeSeeder.class)
  void appliesSeedersAfterTheTree(S3Client s3, com.robothy.s3.rest.LocalS3 localS3) {
    assertEquals("Replaced", s3.getObjectAsBytes(b -> b.bucket("uploads").key("hello.txt")).asString(UTF_8));
    localS3.reset();
    assertEquals("Replaced", s3.getObjectAsBytes(b -> b.bucket("uploads").key("hello.txt")).asString(UTF_8));
    assertFalse(s3.listBuckets().buckets().stream().map(Bucket::name).toList().isEmpty());
  }

  @Test
  void seedsFileSystemDirectory(@TempDir Path dir) throws Exception {
    Files.createDirectories(dir.resolve("docs/a b"));
    Files.writeString(dir.resolve("docs/a b/c+d.txt"), "x");
    try (com.robothy.s3.rest.LocalS3 localS3 = com.robothy.s3.rest.LocalS3.builder().port(0)
        .seeder(LocalS3Extension.seeder(dir.toString())).build()) {
      localS3.start();
      HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
          URI.create("http://localhost:" + localS3.getPort() + "/docs/a%20b/c%2Bd.txt")).build(),
          HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      assertEquals("x", response.body());
    }
  }

  @Test
  void failsOnMissingLocation() {
    com.robothy.s3.rest.LocalS3 localS3 = com.robothy.s3.rest.LocalS3.builder().port(0)
        .seeder(LocalS3Extension.seeder("classpath:no/such/dir")).build();
    UncheckedIOException failure = assertThrows(UncheckedIOException.class, localS3::start);
    assertEquals(FileNotFoundException.class, failure.getCause().getClass());
    localS3.shutdown();
  }

  @Test
  void rejectsClasspathRoot() {
    assertThrows(IllegalArgumentException.class, () -> LocalS3Extension.seeder("classpath:/"));
  }

  public static class CodeSeeder implements LocalS3Seeder {
    @Override
    public void seed(Fixtures fixtures) {
      fixtures.object("uploads", "hello.txt", "Replaced".getBytes(UTF_8));
    }
  }

}
