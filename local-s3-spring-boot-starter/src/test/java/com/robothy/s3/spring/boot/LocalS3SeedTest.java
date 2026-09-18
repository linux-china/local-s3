package com.robothy.s3.spring.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.rest.LocalS3;
import com.robothy.s3.rest.LocalS3Seeder;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/**
 * The objects that the service starts with: the directory tree of {@code local-s3.seed.classpath}, i.e.
 * {@code src/test/resources/s3-fixtures}, and the {@linkplain LocalS3Seeder} beans of the application.
 */
class LocalS3SeedTest {

  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(LocalS3AutoConfiguration.class))
      .withPropertyValues("local-s3.port=-1");

  @Test
  void seedsTheClasspathTreeAsBucketsAndObjects() {
    runner.withPropertyValues("local-s3.seed.classpath=s3-fixtures").run(context -> {
      S3Client s3 = context.getBean(S3Client.class);

      assertEquals(List.of("reports", "uploads"),
          s3.listBuckets().buckets().stream().map(bucket -> bucket.name()).sorted().toList(),
          "A bucket is created for the first segment of the path of a fixture.");
      assertEquals("Hello, LocalS3!",
          s3.getObjectAsBytes(request -> request.bucket("uploads").key("hello.txt")).asUtf8String());
      assertEquals("{\"seeded\":true}",
          s3.getObjectAsBytes(request -> request.bucket("uploads").key("nested/deep.json")).asUtf8String(),
          "A nested directory of a bucket is part of the key.");
      assertEquals("id,total\n1,42\n",
          s3.getObjectAsBytes(request -> request.bucket("reports").key("2026/q1.csv")).asUtf8String());
      assertEquals("text/plain",
          s3.headObject(request -> request.bucket("uploads").key("hello.txt")).contentType(),
          "The content type is guessed from the key.");

      assertThrows(NoSuchKeyException.class,
          () -> s3.headObject(request -> request.bucket("uploads").key("README.md")),
          "A file that names no bucket is skipped.");
    });
  }

  @Test
  void aSeededBucketIsListedWithItsObjectsOnly() {
    runner.withPropertyValues("local-s3.seed.classpath=/s3-fixtures/", "local-s3.buckets=empty").run(context -> {
      S3Client s3 = context.getBean(S3Client.class);
      assertEquals(List.of("hello.txt", "nested/deep.json"),
          s3.listObjectsV2(request -> request.bucket("uploads")).contents().stream()
              .map(object -> object.key()).sorted().toList(),
          "A leading and a trailing slash of the location are accepted.");
      assertTrue(s3.listObjectsV2(request -> request.bucket("empty")).contents().isEmpty(),
          "A bucket of local-s3.buckets that the tree doesn't name stays empty.");
    });
  }

  @Test
  void aResetPutsTheSeededObjectsBack() {
    runner.withPropertyValues("local-s3.seed.classpath=s3-fixtures").run(context -> {
      S3Client s3 = context.getBean(S3Client.class);
      s3.deleteObject(request -> request.bucket("uploads").key("hello.txt"));
      s3.deleteObject(request -> request.bucket("uploads").key("nested/deep.json"));
      s3.deleteBucket(request -> request.bucket("uploads"));
      assertThrows(NoSuchBucketException.class, () -> s3.listObjectsV2(request -> request.bucket("uploads")));

      context.getBean(LocalS3.class).reset();

      assertEquals("Hello, LocalS3!",
          s3.getObjectAsBytes(request -> request.bucket("uploads").key("hello.txt")).asUtf8String(),
          "The fixtures are seeded again, so every test method of a shared service finds them.");
    });
  }

  @Test
  void seedsNothingWithoutALocationOrWithSeedingDisabled() {
    runner.run(context -> {
      assertFalse(context.containsBean("classpathLocalS3Seeder"));
      assertTrue(context.getBean(S3Client.class).listBuckets().buckets().isEmpty());
    });
    runner.withPropertyValues("local-s3.seed.classpath=s3-fixtures", "local-s3.seed.enabled=false").run(context -> {
      assertFalse(context.containsBean("classpathLocalS3Seeder"));
      assertTrue(context.getBean(S3Client.class).listBuckets().buckets().isEmpty(),
          "local-s3.seed.enabled=false keeps the location, e.g. of another profile, without seeding it.");
    });
  }

  @Test
  void aLocationThatTheClasspathDoesNotHoldSeedsNothing() {
    runner.withPropertyValues("local-s3.seed.classpath=no-such-fixtures").run(context -> {
      assertEquals("no-such-fixtures/", context.getBean(ClasspathLocalS3Seeder.class).getLocation());
      assertTrue(context.getBean(S3Client.class).listBuckets().buckets().isEmpty(),
          "A location that no classpath entry holds is warned about, not a failure: another profile may hold it.");
    });
  }

  @Test
  void theClasspathRootIsRefused() {
    runner.withPropertyValues("local-s3.seed.classpath=/").run(context -> assertInstanceOf(
        IllegalArgumentException.class, rootCause(context.getStartupFailure()),
        "Seeding the classpath root would put the classes of the application into the service."));
  }

  @Test
  void theSeederBeansOfTheApplicationAreAppliedToo() {
    runner.withUserConfiguration(SeederConfiguration.class)
        .withPropertyValues("local-s3.seed.classpath=s3-fixtures").run(context -> {
          S3Client s3 = context.getBean(S3Client.class);
          assertEquals("Seeded by the application.",
              s3.getObjectAsBytes(request -> request.bucket("fixtures").key("a.txt")).asUtf8String(),
              "A LocalS3Seeder bean is applied, and creates the bucket of its objects.");
          assertEquals("Hello, LocalS3!",
              s3.getObjectAsBytes(request -> request.bucket("uploads").key("hello.txt")).asUtf8String(),
              "The tree of local-s3.seed.classpath is seeded as well.");
        });
  }

  private static Throwable rootCause(Throwable throwable) {
    Throwable cause = throwable;
    while (cause.getCause() != null) {
      cause = cause.getCause();
    }
    return cause;
  }

  @Configuration(proxyBeanMethods = false)
  static class SeederConfiguration {

    @Bean
    LocalS3Seeder applicationSeeder() {
      return fixtures -> fixtures.object("fixtures", "a.txt", "Seeded by the application.".getBytes());
    }

  }

}
