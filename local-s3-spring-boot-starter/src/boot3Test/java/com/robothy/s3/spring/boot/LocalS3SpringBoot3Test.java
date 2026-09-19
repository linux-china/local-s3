package com.robothy.s3.spring.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.rest.LocalS3;
import com.robothy.s3.rest.bootstrap.LocalS3Mode;
import com.robothy.s3.spring.boot.test.AutoConfigureLocalS3;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.autoconfigure.properties.AnnotationsPropertySource;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.SpringVersion;
import org.springframework.util.ClassUtils;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * The starter is one artifact for Spring Boot 3 and Spring Boot 4: these tests run it on a Spring Boot 3 classpath,
 * without {@code spring-boot-health}, and check that the auto-configurations and the integrations that Spring Boot 4
 * moved to modules of its own apply through the Spring Boot 3 layout.
 *
 * @see LocalS3ActuatorHealthAutoConfiguration
 */
class LocalS3SpringBoot3Test {

  @Test
  void theTestsRunOnSpringBoot3() {
    assertTrue(SpringVersion.getVersion().startsWith("6."),
        "Spring Framework of Spring Boot 3, not " + SpringVersion.getVersion());
    ClassLoader classLoader = getClass().getClassLoader();
    assertFalse(ClassUtils.isPresent("org.springframework.boot.health.contributor.HealthIndicator", classLoader),
        "spring-boot-health is a Spring Boot 4 module and must be off this classpath.");
    assertTrue(ClassUtils.isPresent("org.springframework.boot.actuate.health.HealthIndicator", classLoader));
  }

  @Test
  void anApplicationEmbedsLocalS3ThroughTheStarter() {
    LocalS3 localS3;
    try (ConfigurableApplicationContext context = new SpringApplicationBuilder(Application.class)
        .web(WebApplicationType.NONE)
        .properties("local-s3.port=0", "local-s3.buckets=app")
        .run()) {
      localS3 = context.getBean(LocalS3.class);
      assertTrue(localS3.isRunning());
      S3Client s3 = context.getBean(S3Client.class);
      s3.putObject(request -> request.bucket("app").key("hello.txt"), RequestBody.fromString("Hello Boot 3"));
      assertEquals("Hello Boot 3",
          s3.getObjectAsBytes(request -> request.bucket("app").key("hello.txt")).asUtf8String());
      assertEquals(1, context.getBeansOfType(S3AsyncClient.class).size());
      assertEquals(1, context.getBeansOfType(S3Presigner.class).size());

      // The Actuator health of Spring Boot 3, from the same bean name as on Spring Boot 4.
      HealthIndicator indicator = context.getBean("localS3HealthIndicator", HealthIndicator.class);
      assertInstanceOf(LocalS3ActuatorHealthIndicator.class, indicator);
      Health health = indicator.health();
      assertEquals(Status.UP, health.getStatus());
      Map<String, Object> details = health.getDetails();
      assertEquals(context.getBean(LocalS3Lifecycle.class).endpoint().toString(), details.get("endpoint"));
      assertEquals("IN_MEMORY", details.get("mode"));
      assertEquals(1L, details.get("buckets"));
      assertEquals(1L, details.get("objects"));

      // Micrometer, whose API Spring Boot 3 and Spring Boot 4 share.
      MeterRegistry registry = new SimpleMeterRegistry();
      context.getBean(LocalS3Metrics.class).bindTo(registry);
      assertEquals(1.0, registry.get("local.s3.buckets").gauge().value());

      context.getBean(LocalS3Lifecycle.class).stop();
      assertEquals(Status.DOWN, indicator.health().getStatus());
    }
    assertFalse(localS3.isRunning(), "Closing the application stops the service.");
  }

  @Test
  void theHealthIndicatorCanBeDisabled() {
    try (ConfigurableApplicationContext context = new SpringApplicationBuilder(Application.class)
        .web(WebApplicationType.NONE)
        .properties("local-s3.port=0", "management.health.local-s3.enabled=false")
        .run()) {
      assertFalse(context.containsBean("localS3HealthIndicator"));
    }
  }

  @Test
  void theAttributesOfAutoConfigureLocalS3MapToPropertiesThroughTheSpringBoot3PropertyMapping() {
    AnnotationsPropertySource source = new AnnotationsPropertySource(AnnotatedTest.class);
    assertEquals(29099, source.getProperty("local-s3.port"));
    assertEquals("PERSISTENCE", String.valueOf(source.getProperty("local-s3.mode")));
    // Only the Spring Boot 4 mapping skips reset(); on Spring Boot 3 it becomes a property that LocalS3Properties has
    // no field for, and that the lenient binding of @ConfigurationProperties ignores.
    assertEquals(true, source.getProperty("local-s3.reset"));
  }

  @AutoConfigureLocalS3(port = 29099, mode = LocalS3Mode.PERSISTENCE)
  static class AnnotatedTest {
  }

  @SpringBootApplication
  static class Application {
  }

}
