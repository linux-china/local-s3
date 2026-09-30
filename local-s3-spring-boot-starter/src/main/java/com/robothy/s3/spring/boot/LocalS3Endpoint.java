package com.robothy.s3.spring.boot;

import com.robothy.s3.core.service.manager.ObjectStatistics;
import com.robothy.s3.core.service.manager.vectors.VectorStatistics;
import com.robothy.s3.rest.LocalS3;
import com.robothy.s3.rest.admin.ServiceStatistics;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;

/**
 * The Actuator endpoint {@code locals3} of the embedded LocalS3 service, {@code GET /actuator/locals3}: how the
 * service is configured, the amount of its data, and its buckets with the number of objects and the bytes they hold,
 * i.e. {@linkplain LocalS3#statistics()} with the statistics of each bucket.
 *
 * <p>Like every endpoint of Actuator other than {@code health}, it isn't exposed over HTTP unless the application
 * says so, e.g. with {@code management.endpoints.web.exposure.include=health,locals3}. Counting the data walks every
 * bucket, which a {@code PERSISTENCE} service with many objects reads from its data directory, so each request costs
 * as much as {@code GET /_admin/stats}.
 *
 * <p>The endpoint API, {@code org.springframework.boot.actuate.endpoint.annotation}, is the same in Spring Boot 3 and
 * Spring Boot 4, both in {@code spring-boot-actuator}.
 */
@Endpoint(id = LocalS3Endpoint.ID)
public class LocalS3Endpoint {

  static final String ID = "locals3";

  private final LocalS3Lifecycle lifecycle;

  public LocalS3Endpoint(LocalS3Lifecycle lifecycle) {
    this.lifecycle = Objects.requireNonNull(lifecycle);
  }

  /**
   * The description of the service.
   *
   * @return the configuration of the service and, while it is running, the amount of its data by bucket.
   */
  @ReadOperation
  public Descriptor describe() {
    LocalS3 localS3 = lifecycle.getLocalS3();
    Path dataPath = localS3.getDataPath();
    Configuration configuration = new Configuration(localS3.getMode().name(), localS3.getBindHost(),
        dataPath == null ? null : dataPath.toString(), localS3.getConfig().authenticationEnabled());
    if (!localS3.isRunning()) {
      return new Descriptor(false, null, configuration, null, null, List.of());
    }
    ServiceStatistics statistics = localS3.statistics();
    List<Bucket> buckets = localS3.getS3Manager().bucketStatistics().entrySet().stream()
        .map(bucket -> Bucket.of(bucket.getKey(), bucket.getValue()))
        .toList();
    return new Descriptor(true, localS3.endpoint(), configuration, statistics.uptimeSeconds(),
        Totals.of(statistics.data(), statistics.vectors(), statistics.totalRequests()), buckets);
  }

  /**
   * The description of the service.
   *
   * @param running       whether the service is running; the other fields but {@code configuration} are
   *                      {@code null} or empty while it isn't.
   * @param endpoint      the endpoint that clients in the same JVM reach the service at.
   * @param configuration how the service is configured.
   * @param uptimeSeconds the seconds since the service started.
   * @param totals        the amount of data of the service.
   * @param buckets       the buckets, by name.
   */
  public record Descriptor(boolean running, String endpoint, Configuration configuration, Long uptimeSeconds,
                           Totals totals, List<Bucket> buckets) {
  }

  /**
   * How the service is configured.
   *
   * @param mode                   {@code IN_MEMORY} or {@code PERSISTENCE}.
   * @param bindHost               the host that the service listens on.
   * @param dataPath               the data directory, or {@code null} if it has none.
   * @param signedRequestsRequired whether the service has credentials and requires the requests to be signed with
   *                               them.
   */
  public record Configuration(String mode, String bindHost, String dataPath, boolean signedRequestsRequired) {
  }

  /**
   * The amount of data of the service.
   *
   * @param buckets          the number of buckets.
   * @param objects          the number of objects whose latest version isn't a delete marker.
   * @param objectVersions   the number of object versions that aren't delete markers.
   * @param deleteMarkers    the number of delete markers.
   * @param storageBytes     the bytes of all object versions.
   * @param multipartUploads the number of multipart uploads in progress.
   * @param vectorBuckets    the number of vector buckets.
   * @param vectorIndexes    the number of vector indexes.
   * @param vectors          the number of vectors.
   * @param totalRequests    the number of requests answered since the service started or was reset.
   */
  public record Totals(long buckets, long objects, long objectVersions, long deleteMarkers, long storageBytes,
                       long multipartUploads, long vectorBuckets, long vectorIndexes, long vectors,
                       long totalRequests) {

    static Totals of(ObjectStatistics data, VectorStatistics vectors, long totalRequests) {
      return new Totals(data.buckets(), data.objects(), data.objectVersions(), data.deleteMarkers(),
          data.objectBytes(), data.multipartUploads(), vectors.vectorBuckets(), vectors.indexes(), vectors.vectors(), totalRequests);
    }

  }

  /**
   * A bucket.
   *
   * @param name             the name of the bucket.
   * @param objects          the number of objects whose latest version isn't a delete marker.
   * @param objectVersions   the number of object versions that aren't delete markers.
   * @param deleteMarkers    the number of delete markers.
   * @param storageBytes     the bytes of all object versions of the bucket.
   * @param multipartUploads the number of multipart uploads in progress.
   */
  public record Bucket(String name, long objects, long objectVersions, long deleteMarkers, long storageBytes,
                       long multipartUploads) {

    static Bucket of(String name, ObjectStatistics statistics) {
      return new Bucket(name, statistics.objects(), statistics.objectVersions(), statistics.deleteMarkers(),
          statistics.objectBytes(), statistics.multipartUploads());
    }

  }

}
