package com.robothy.s3.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.robothy.s3.core.exception.BucketNotExistException;
import com.robothy.s3.core.exception.InvalidCORSConfigurationException;
import com.robothy.s3.core.service.manager.LocalS3Manager;
import com.robothy.s3.datatypes.CORSConfiguration;
import com.robothy.s3.datatypes.CORSRule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class BucketCorsServiceTest extends LocalS3ServiceTestBase {

  private static final String BUCKET = "cors-bucket";

  @ParameterizedTest
  @MethodSource("bucketServices")
  void putsGetsAndDeletesBucketCors(BucketService bucketService) {
    bucketService.createBucket(BUCKET);
    assertTrue(bucketService.getBucketCors(BUCKET).isEmpty());

    CORSConfiguration configuration = configuration();
    bucketService.putBucketCors(BUCKET, configuration);
    assertEquals(configuration, bucketService.getBucketCors(BUCKET).get());

    bucketService.deleteBucketCors(BUCKET);
    assertTrue(bucketService.getBucketCors(BUCKET).isEmpty());
    assertThrows(BucketNotExistException.class, () -> bucketService.getBucketCors("no-such-bucket"));
  }

  @Test
  void persistsBucketCors() throws IOException {
    Path dataPath = Files.createTempDirectory("local-s3");
    try {
      BucketService bucketService = LocalS3Manager.createFileSystemS3Manager(dataPath).bucketService();
      bucketService.createBucket(BUCKET);
      bucketService.putBucketCors(BUCKET, configuration());

      BucketService restarted = LocalS3Manager.createFileSystemS3Manager(dataPath).bucketService();
      assertEquals(configuration(), restarted.getBucketCors(BUCKET).get());
    } finally {
      FileUtils.deleteDirectory(dataPath.toFile());
    }
  }

  @ParameterizedTest
  @MethodSource("invalidConfigurations")
  void rejectsInvalidConfigurations(CORSConfiguration configuration) {
    BucketService bucketService = LocalS3Manager.createInMemoryS3Manager().bucketService();
    bucketService.createBucket(BUCKET);
    assertThrows(InvalidCORSConfigurationException.class, () -> bucketService.putBucketCors(BUCKET, configuration));
    assertTrue(bucketService.getBucketCors(BUCKET).isEmpty());
  }

  static Stream<CORSConfiguration> invalidConfigurations() {
    return Arrays.asList(
        null,
        new CORSConfiguration(List.of()),
        new CORSConfiguration(List.of(CORSRule.builder().allowedMethods(List.of("GET")).build())),
        new CORSConfiguration(List.of(CORSRule.builder().allowedOrigins(List.of("*")).build())),
        new CORSConfiguration(List.of(rule().allowedMethods(List.of("PATCH")).build())),
        new CORSConfiguration(List.of(rule().allowedOrigins(List.of("http://*.*.example.com")).build())),
        new CORSConfiguration(List.of(rule().allowedHeaders(List.of("x-*-*")).build())),
        new CORSConfiguration(List.of(rule().maxAgeSeconds(-1).build()))
    ).stream();
  }

  private static CORSConfiguration configuration() {
    return new CORSConfiguration(List.of(
        CORSRule.builder()
            .id("uploads")
            .allowedOrigins(List.of("http://localhost:3000", "https://*.example.com"))
            .allowedMethods(List.of("GET", "PUT"))
            .allowedHeaders(List.of("*"))
            .exposeHeaders(List.of("ETag"))
            .maxAgeSeconds(3000)
            .build(),
        rule().build()));
  }

  private static CORSRule.CORSRuleBuilder rule() {
    return CORSRule.builder().allowedOrigins(List.of("*")).allowedMethods(List.of("GET"));
  }

}
