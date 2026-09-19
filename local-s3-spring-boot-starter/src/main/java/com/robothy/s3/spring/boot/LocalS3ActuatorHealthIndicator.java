package com.robothy.s3.spring.boot;

import java.util.Map;
import java.util.Objects;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * The health of the embedded LocalS3 service on Spring Boot 3, whose Health API is
 * {@code org.springframework.boot.actuate.health} of {@code spring-boot-actuator}: {@code UP} if the service is
 * running and answers its health check {@code GET /_health}, with the endpoint and the amount of data of the service
 * as details; {@code DOWN} otherwise.
 *
 * <p>An application on Spring Boot 4 gets {@linkplain LocalS3HealthIndicator} instead, which reports the same through
 * the Health API that Spring Boot 4 moved to {@code spring-boot-health}. Neither class is loaded unless its API is on
 * the classpath.
 */
public class LocalS3ActuatorHealthIndicator implements HealthIndicator {

  private final LocalS3HealthProbe probe;

  public LocalS3ActuatorHealthIndicator(LocalS3Lifecycle lifecycle) {
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

}
