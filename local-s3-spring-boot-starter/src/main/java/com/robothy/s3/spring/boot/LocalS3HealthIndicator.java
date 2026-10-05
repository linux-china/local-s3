package com.robothy.s3.spring.boot;

import java.util.Map;
import java.util.Objects;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * The health of the embedded LocalS3 service on Spring Boot 4, whose Health API is
 * {@code org.springframework.boot.health.contributor} of {@code spring-boot-health}: {@code UP} if the service is
 * running and answers its health check {@code GET /_health}, with the endpoint and the amount of data of the service
 * as details; {@code DOWN} otherwise.
 *
 * <p>An application on Spring Boot 3 gets {@linkplain LocalS3ActuatorHealthIndicator} instead, which reports the same
 * through the Actuator Health API of Spring Boot 3. Neither class is loaded unless its API is on the classpath.
 */
public class LocalS3HealthIndicator implements HealthIndicator, AutoCloseable {

  private final LocalS3HealthProbe probe;

  public LocalS3HealthIndicator(LocalS3Lifecycle lifecycle) {
    this.probe = new LocalS3HealthProbe(Objects.requireNonNull(lifecycle));
  }

  @Override
  public Health health() {
    LocalS3HealthProbe.Result result = probe.probe();
    Health.Builder builder;
    if (result.up()) {
      builder = Health.up();
    } else {
      builder = result.error() != null ? Health.down(result.error()) : Health.down();
    }
    for (Map.Entry<String, Object> detail : result.details().entrySet()) {
      builder.withDetail(detail.getKey(), detail.getValue());
    }
    return builder.build();
  }

  /**
   * Close the probe with the context; Spring infers {@code close()} as the destroy method of the bean.
   */
  @Override
  public void close() {
    probe.close();
  }

}
