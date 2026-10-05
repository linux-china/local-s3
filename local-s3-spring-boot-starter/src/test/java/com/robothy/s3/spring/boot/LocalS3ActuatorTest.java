package com.robothy.s3.spring.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.rest.LocalS3;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.simple.SimpleMetricsExportAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

class LocalS3ActuatorTest {

  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(LocalS3AutoConfiguration.class, LocalS3MetricsAutoConfiguration.class,
          LocalS3HealthAutoConfiguration.class, MetricsAutoConfiguration.class,
          CompositeMeterRegistryAutoConfiguration.class, SimpleMetricsExportAutoConfiguration.class))
      .withPropertyValues("local-s3.port=-1", "local-s3.buckets=metrics");

  @Test
  void theHealthIndicatorReportsARunningService() {
    runner.run(context -> {
      LocalS3HealthIndicator indicator = context.getBean("localS3HealthIndicator", LocalS3HealthIndicator.class);
      context.getBean(S3Client.class).putObject(request -> request.bucket("metrics").key("a.txt"),
          RequestBody.fromString("Hello"));

      Health health = indicator.health();
      assertEquals(Status.UP, health.getStatus());
      Map<String, Object> details = health.getDetails();
      assertEquals(context.getBean(LocalS3Lifecycle.class).endpoint().toString(), details.get("endpoint"));
      assertEquals("IN_MEMORY", details.get("mode"));
      assertEquals(1L, details.get("buckets"));
      assertEquals(1L, details.get("objects"));
      assertEquals(5L, details.get("objectBytes"));

      context.getBean(LocalS3Lifecycle.class).stop();
      assertEquals(Status.DOWN, indicator.health().getStatus());
    });
  }

  /**
   * The HTTP client of the probe is closed with the context, rather than its selector thread outliving it until the
   * client is garbage collected.
   */
  @Test
  void theHealthIndicatorClosesItsHttpClientWithTheContext() throws Exception {
    Thread[] selector = new Thread[1];
    runner.run(context -> {
      context.getBean(LocalS3HealthIndicator.class).health();
      selector[0] = Thread.getAllStackTraces().keySet().stream()
          .filter(thread -> thread.getName().startsWith("HttpClient-") && thread.getName().endsWith("-SelectorManager"))
          .filter(thread -> thread.getStackTrace().length > 0)
          .reduce((first, last) -> last).orElseThrow();
    });

    selector[0].join(5_000);
    assertFalse(selector[0].isAlive(), "The selector thread of the HTTP client of the probe stopped.");
  }

  @Test
  void theEndpointListsTheBucketsWithTheirObjectsAndStorage() {
    runner.withConfiguration(AutoConfigurations.of(LocalS3EndpointAutoConfiguration.class))
        .withPropertyValues("management.endpoints.web.exposure.include=locals3", "local-s3.buckets=metrics,empty")
        .run(context -> {
          S3Client s3 = context.getBean(S3Client.class);
          s3.putObject(request -> request.bucket("metrics").key("a.txt"), RequestBody.fromString("Hello"));
          s3.putObject(request -> request.bucket("metrics").key("b.txt"), RequestBody.fromString("World!"));

          LocalS3Endpoint endpoint = context.getBean(LocalS3Endpoint.class);
          LocalS3Endpoint.Descriptor descriptor = endpoint.describe();
          assertTrue(descriptor.running());
          assertEquals(context.getBean(LocalS3Lifecycle.class).endpoint().toString(), descriptor.endpoint());
          assertEquals("IN_MEMORY", descriptor.configuration().mode());
          assertEquals("127.0.0.1", descriptor.configuration().bindHost());
          assertFalse(descriptor.configuration().signedRequestsRequired());
          assertEquals(2L, descriptor.totals().buckets());
          assertEquals(2L, descriptor.totals().objects());
          assertEquals(11L, descriptor.totals().storageBytes());
          assertEquals(List.of(new LocalS3Endpoint.Bucket("empty", 0, 0, 0, 0, 0),
              new LocalS3Endpoint.Bucket("metrics", 2, 2, 0, 11, 0)), descriptor.buckets());

          context.getBean(LocalS3Lifecycle.class).stop();
          LocalS3Endpoint.Descriptor stopped = endpoint.describe();
          assertFalse(stopped.running());
          assertEquals("IN_MEMORY", stopped.configuration().mode());
          assertTrue(stopped.buckets().isEmpty());
        });
  }

  @Test
  void theEndpointIsOnlyDefinedWhenItIsExposed() {
    runner.withConfiguration(AutoConfigurations.of(LocalS3EndpointAutoConfiguration.class))
        .run(context -> assertFalse(context.containsBean("localS3Endpoint")));
  }

  @Test
  void theHealthIndicatorCanBeDisabled() {
    runner.withPropertyValues("management.health.local-s3.enabled=false")
        .run(context -> assertFalse(context.containsBean("localS3HealthIndicator")));
  }

  @Test
  void theRequestsAndTheDataOfTheServiceAreMetrics() {
    runner.run(context -> {
      S3Client s3 = context.getBean(S3Client.class);
      s3.putObject(request -> request.bucket("metrics").key("a.txt"), RequestBody.fromString("Hello"));
      s3.putObject(request -> request.bucket("metrics").key("b.txt"), RequestBody.fromString("World!"));
      s3.getObjectAsBytes(request -> request.bucket("metrics").key("a.txt"));
      try {
        s3.getObjectAsBytes(request -> request.bucket("metrics").key("missing.txt"));
      } catch (NoSuchKeyException expected) {
        // Recorded as a client error.
      }
      // The health check that the indicator requests isn't recorded, like in GET /_admin/stats.
      context.getBean(LocalS3HealthIndicator.class).health();

      MeterRegistry registry = context.getBean(MeterRegistry.class);
      // A request is recorded once its response is written, which the client may have read before.
      awaitCount(registry, "PutObject", "SUCCESS", 2);
      awaitCount(registry, "GetObject", "SUCCESS", 1);
      awaitCount(registry, "GetObject", "CLIENT_ERROR", 1);
      assertTrue(registry.find(LocalS3Metrics.REQUESTS).tag("operation", "HealthCheck").timers().isEmpty());
      Timer failedGet = registry.get(LocalS3Metrics.REQUESTS).tag("operation", "GetObject").tag("status", "404").timer();
      assertEquals(1, failedGet.count());

      assertEquals(1.0, registry.get("local.s3.buckets").gauge().value());
      assertEquals(11.0, registry.get("local.s3.objects.size").gauge().value(), 0.0);
      LocalS3 localS3 = context.getBean(LocalS3.class);
      assertEquals((double) localS3.statistics().data().objects(), registry.get("local.s3.objects").gauge().value());
      assertEquals(0.0, registry.get("local.s3.vectors").gauge().value());
    });
  }

  private static void awaitCount(MeterRegistry registry, String operation, String outcome, long expected)
      throws InterruptedException {
    long count = 0;
    for (int attempt = 0; attempt < 100; attempt++) {
      count = registry.find(LocalS3Metrics.REQUESTS).tag("operation", operation).tag("outcome", outcome).timers()
          .stream().mapToLong(Timer::count).sum();
      if (count >= expected) {
        break;
      }
      Thread.sleep(20);
    }
    assertEquals(expected, count, operation + " " + outcome);
  }

}
