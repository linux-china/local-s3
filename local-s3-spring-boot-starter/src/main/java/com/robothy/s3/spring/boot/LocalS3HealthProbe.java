package com.robothy.s3.spring.boot;

import com.robothy.s3.rest.LocalS3;
import com.robothy.s3.rest.admin.ServiceStatistics;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Probes the embedded LocalS3 service, without the Health API of Spring Boot: Spring Boot 3 has it in
 * {@code org.springframework.boot.actuate.health} of {@code spring-boot-actuator}, and Spring Boot 4 in
 * {@code org.springframework.boot.health.contributor} of {@code spring-boot-health}. The health indicators of both
 * layouts turn a {@linkplain Result} into the {@code Health} of the Spring Boot on the classpath.
 *
 * @see LocalS3HealthIndicator
 */
public class LocalS3HealthProbe {

  private static final Duration TIMEOUT = Duration.ofSeconds(2);

  private final LocalS3Lifecycle lifecycle;

  private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

  public LocalS3HealthProbe(LocalS3Lifecycle lifecycle) {
    this.lifecycle = Objects.requireNonNull(lifecycle);
  }

  /**
   * The outcome of a probe.
   *
   * @param up      whether the service is running and answers its health check.
   * @param details the endpoint and the amount of data of the service, reported as the details of the health.
   * @param error   what the probe failed with, or {@code null} if it didn't fail.
   */
  public record Result(boolean up, Map<String, Object> details, Throwable error) {

    public Result {
      details = Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

  }

  /**
   * Probe the service: {@code up} if it is running and answers its health check {@code GET /_health}.
   *
   * @return the outcome of the probe.
   */
  public Result probe() {
    LocalS3 localS3 = lifecycle.getLocalS3();
    if (!localS3.isRunning()) {
      return new Result(false, Map.of("running", false), null);
    }

    URI endpoint = lifecycle.endpoint();
    try {
      HttpResponse<Void> response = httpClient.send(HttpRequest.newBuilder(endpoint.resolve("/_health"))
          .timeout(TIMEOUT).GET().build(), HttpResponse.BodyHandlers.discarding());
      if (response.statusCode() != 200) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("endpoint", endpoint.toString());
        details.put("healthCheckStatus", response.statusCode());
        return new Result(false, details, null);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new Result(false, Map.of("endpoint", endpoint.toString()), e);
    } catch (Exception e) {
      return new Result(false, Map.of("endpoint", endpoint.toString()), e);
    }

    ServiceStatistics statistics = localS3.statistics();
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("endpoint", endpoint.toString());
    details.put("mode", statistics.mode());
    details.put("uptimeSeconds", statistics.uptimeSeconds());
    details.put("inFlightRequests", statistics.inFlightRequests());
    details.put("buckets", statistics.data().buckets());
    details.put("objects", statistics.data().objects());
    details.put("objectBytes", statistics.data().objectBytes());
    details.put("vectorBuckets", statistics.vectors().vectorBuckets());
    return new Result(true, details, null);
  }

}
