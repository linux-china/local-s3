package com.robothy.s3.spring.boot;

import com.robothy.s3.rest.LocalS3;
import com.robothy.s3.rest.admin.ServiceStatistics;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * The health of the embedded LocalS3 service: {@code UP} if it is running and answers its health check
 * {@code GET /_health}, with the endpoint and the amount of data of the service as details; {@code DOWN} otherwise.
 */
public class LocalS3HealthIndicator implements HealthIndicator {

  private static final Duration TIMEOUT = Duration.ofSeconds(2);

  private final LocalS3Lifecycle lifecycle;

  private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

  public LocalS3HealthIndicator(LocalS3Lifecycle lifecycle) {
    this.lifecycle = Objects.requireNonNull(lifecycle);
  }

  @Override
  public Health health() {
    LocalS3 localS3 = lifecycle.getLocalS3();
    if (!localS3.isRunning()) {
      return Health.down().withDetail("running", false).build();
    }

    URI endpoint = lifecycle.endpoint();
    try {
      HttpResponse<Void> response = httpClient.send(HttpRequest.newBuilder(endpoint.resolve("/_health"))
          .timeout(TIMEOUT).GET().build(), HttpResponse.BodyHandlers.discarding());
      if (response.statusCode() != 200) {
        return Health.down().withDetail("endpoint", endpoint.toString())
            .withDetail("healthCheckStatus", response.statusCode()).build();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Health.down(e).withDetail("endpoint", endpoint.toString()).build();
    } catch (Exception e) {
      return Health.down(e).withDetail("endpoint", endpoint.toString()).build();
    }

    ServiceStatistics statistics = localS3.statistics();
    return Health.up()
        .withDetail("endpoint", endpoint.toString())
        .withDetail("mode", statistics.mode())
        .withDetail("uptimeSeconds", statistics.uptimeSeconds())
        .withDetail("inFlightRequests", statistics.inFlightRequests())
        .withDetail("buckets", statistics.data().buckets())
        .withDetail("objects", statistics.data().objects())
        .withDetail("objectBytes", statistics.data().objectBytes())
        .withDetail("vectorBuckets", statistics.vectors().vectorBuckets())
        .build();
  }

}
