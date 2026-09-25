package com.robothy.s3.spring.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.core.event.S3Change;
import com.robothy.s3.rest.LocalS3;
import java.net.URL;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.devtools.restart.classloader.RestartClassLoader;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.DefaultResourceLoader;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

/**
 * Spring Boot DevTools restarts an application by closing its context and creating a new one, with a new
 * {@code RestartClassLoader}, in the same JVM: an {@code IN_MEMORY} service keeps its data across the restarts.
 */
class LocalS3DevToolsRestartTest {

  @Test
  void anInMemoryServiceKeepsItsDataAcrossTheRestartsOfDevTools() {
    try (ConfigurableApplicationContext context = run(true)) {
      context.getBean(S3Client.class).createBucket(request -> request.bucket("uploads"));
      context.getBean(S3Client.class).putObject(request -> request.bucket("uploads").key("a.txt"),
          RequestBody.fromString("before the restart"));
    }
    Changes firstChanges;
    try (ConfigurableApplicationContext context = run(true)) {
      firstChanges = context.getBean(Changes.class);
      S3Client s3 = context.getBean(S3Client.class);
      assertEquals("before the restart",
          s3.getObjectAsBytes(request -> request.bucket("uploads").key("a.txt")).asUtf8String());
      s3.putObject(request -> request.bucket("uploads").key("b.txt"), RequestBody.fromString("second run"));
      assertTrue(context.getBean(Changes.class).keys.contains("b.txt"),
          "The changes are published to the context that holds the data now.");
    }
    try (ConfigurableApplicationContext context = run(true)) {
      S3Client s3 = context.getBean(S3Client.class);
      assertEquals("second run", s3.getObjectAsBytes(request -> request.bucket("uploads").key("b.txt")).asUtf8String());
      s3.putObject(request -> request.bucket("uploads").key("c.txt"), RequestBody.fromString("third run"));
      assertFalse(firstChanges.keys.contains("c.txt"),
          "A closed context no longer hears of the changes.");
    }
    // Released for the other tests of the JVM.
    LocalS3DevToolsRestart.takeOver(LocalS3.builder().build());
  }

  @Test
  void theDataIsDroppedWhenKeepingItIsDisabled() {
    try (ConfigurableApplicationContext context = run(true, "local-s3.devtools.keep-data=false")) {
      context.getBean(S3Client.class).createBucket(request -> request.bucket("dropped"));
    }
    try (ConfigurableApplicationContext context = run(true, "local-s3.devtools.keep-data=false")) {
      assertThrows(NoSuchBucketException.class,
          () -> context.getBean(S3Client.class).headBucket(request -> request.bucket("dropped")));
    }
  }

  @Test
  void theDataIsDroppedWithoutDevTools() {
    try (ConfigurableApplicationContext context = run(false)) {
      context.getBean(S3Client.class).createBucket(request -> request.bucket("dropped"));
    }
    try (ConfigurableApplicationContext context = run(false)) {
      assertThrows(NoSuchBucketException.class,
          () -> context.getBean(S3Client.class).headBucket(request -> request.bucket("dropped")));
    }
  }

  private ConfigurableApplicationContext run(boolean devTools, String... properties) {
    ClassLoader classLoader = getClass().getClassLoader();
    if (devTools) {
      // Loads nothing itself, like a restart of an application whose classes didn't change.
      classLoader = new RestartClassLoader(classLoader, new URL[0]);
    }
    return new SpringApplicationBuilder(Application.class)
        .resourceLoader(new DefaultResourceLoader(classLoader))
        .web(WebApplicationType.NONE)
        .properties("local-s3.port=0")
        .properties(properties)
        .run();
  }

  @SpringBootApplication
  static class Application {

    @Bean
    Changes changes() {
      return new Changes();
    }

  }

  static class Changes {

    final List<String> keys = new CopyOnWriteArrayList<>();

    @EventListener
    void on(S3Change change) {
      if (change.key() != null) {
        keys.add(change.key());
      }
    }

  }

}
