package com.robothy.s3.spring.boot;

import com.robothy.netty.http.HttpRequest;
import com.robothy.s3.core.service.manager.ObjectStatistics;
import com.robothy.s3.core.service.manager.vectors.VectorStatistics;
import com.robothy.s3.rest.LocalS3;
import com.robothy.s3.rest.admin.ServiceStatistics;
import com.robothy.s3.rest.handler.LocalS3RouterFactory;
import com.robothy.s3.rest.netty.RequestRecorder;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;

/**
 * The metrics of the embedded LocalS3 service, for Micrometer:
 *
 * <ul>
 *   <li>{@code local.s3.requests}, a timer of the requests that the service answered, tagged with their
 *   {@code operation}, e.g. {@code PutObject}, their {@code status} and their {@code outcome}, e.g.
 *   {@code CLIENT_ERROR}. Like {@code GET /_admin/stats}, it leaves out the health checks and the administration
 *   requests.</li>
 *   <li>gauges of the data that {@code GET /_admin/stats} reports: {@code local.s3.buckets}, {@code local.s3.objects},
 *   {@code local.s3.object.versions}, {@code local.s3.delete.markers}, {@code local.s3.objects.size} in bytes,
 *   {@code local.s3.multipart.uploads}, {@code local.s3.vector.buckets}, {@code local.s3.vector.indexes},
 *   {@code local.s3.vectors}, and the {@code local.s3.requests.active} in flight.</li>
 * </ul>
 *
 * <p>The requests are timed rather than read off the statistics of the service, whose counts {@code reset()} sets
 * back to zero, which a counter of Micrometer must never do. Counting the data walks every bucket, so the statistics
 * are read once for all gauges of a scrape, at most once a second.
 */
public class LocalS3Metrics implements MeterBinder, RequestRecorder {

  static final String REQUESTS = "local.s3.requests";

  private static final long STATISTICS_TTL_NANOS = TimeUnit.SECONDS.toNanos(1);

  private final Supplier<LocalS3> localS3;

  private final List<MeterRegistry> registries = new CopyOnWriteArrayList<>();

  private volatile CachedStatistics cached;

  /**
   * Create the metrics of a service, which is looked up when they are recorded, so that the service can be created
   * with them as its request recorder.
   *
   * @param localS3 supplies the service.
   */
  public LocalS3Metrics(Supplier<LocalS3> localS3) {
    this.localS3 = Objects.requireNonNull(localS3);
  }

  @Override
  public void bindTo(@NonNull MeterRegistry registry) {
    registries.add(registry);
    gauge(registry, "local.s3.buckets", "Buckets.", null, stats -> data(stats).buckets());
    gauge(registry, "local.s3.objects", "Objects whose latest version isn't a delete marker.", null,
        stats -> data(stats).objects());
    gauge(registry, "local.s3.object.versions", "Object versions, including delete markers.", null,
        stats -> data(stats).objectVersions());
    gauge(registry, "local.s3.delete.markers", "Delete markers.", null, stats -> data(stats).deleteMarkers());
    gauge(registry, "local.s3.objects.size", "Bytes of all object versions.", "bytes",
        stats -> data(stats).objectBytes());
    gauge(registry, "local.s3.multipart.uploads", "Multipart uploads in progress.", null,
        stats -> data(stats).multipartUploads());
    gauge(registry, "local.s3.vector.buckets", "Vector buckets.", null, stats -> vectors(stats).vectorBuckets());
    gauge(registry, "local.s3.vector.indexes", "Vector indexes.", null, stats -> vectors(stats).indexes());
    gauge(registry, "local.s3.vectors", "Vectors.", null, stats -> vectors(stats).vectors());
    gauge(registry, "local.s3.requests.active", "Requests whose responses aren't written yet.", null,
        ServiceStatistics::inFlightRequests);
  }

  @Override
  public void record(HttpRequest request, String operation, int status, String requestId, long durationNanos) {
    if (LocalS3RouterFactory.UNRECORDED_OPERATIONS.contains(operation)) {
      return;
    }
    for (MeterRegistry registry : registries) {
      Timer.builder(REQUESTS)
          .description("Requests answered by the embedded LocalS3 service.")
          .tag("operation", operation)
          .tag("status", String.valueOf(status))
          .tag("outcome", outcome(status))
          .register(registry)
          .record(durationNanos, TimeUnit.NANOSECONDS);
    }
  }

  private void gauge(MeterRegistry registry, String name, String description, String baseUnit,
                     ToDoubleFunction<ServiceStatistics> value) {
    Gauge.builder(name, this, metrics -> {
          ServiceStatistics statistics = metrics.statistics();
          return statistics == null ? Double.NaN : value.applyAsDouble(statistics);
        })
        .description(description)
        .baseUnit(baseUnit)
        .strongReference(true)
        .register(registry);
  }

  /**
   * The statistics of the service, read at most once a second; {@code null} while it isn't running.
   */
  private ServiceStatistics statistics() {
    LocalS3 service = localS3.get();
    if (service == null || !service.isRunning()) {
      return null;
    }
    long now = System.nanoTime();
    CachedStatistics current = cached;
    if (current == null || now - current.readAtNanos() > STATISTICS_TTL_NANOS) {
      current = new CachedStatistics(service.statistics(), now);
      cached = current;
    }
    return current.statistics();
  }

  private static ObjectStatistics data(ServiceStatistics statistics) {
    return statistics.data();
  }

  private static VectorStatistics vectors(ServiceStatistics statistics) {
    return statistics.vectors();
  }

  static String outcome(int status) {
    if (status >= 500) {
      return "SERVER_ERROR";
    }
    if (status >= 400) {
      return "CLIENT_ERROR";
    }
    if (status >= 300) {
      return "REDIRECTION";
    }
    return status >= 200 ? "SUCCESS" : "INFORMATIONAL";
  }

  private record CachedStatistics(ServiceStatistics statistics, long readAtNanos) {
  }

}
