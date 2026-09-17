package com.robothy.s3.rest.admin;

import com.robothy.s3.core.model.answers.LifecycleActionAns;
import java.time.Instant;
import java.util.List;

/**
 * The administration of a running LocalS3 service, which the {@code /_admin} endpoints answer through: the statistics
 * of its data and requests, the recent requests, and the reset of its data.
 */
public interface LocalS3Admin {

  /**
   * The statistics of the service.
   *
   * @return the statistics.
   */
  ServiceStatistics statistics();

  /**
   * The last requests that the service answered, the most recent first.
   *
   * @param limit the max number of requests.
   * @return the requests.
   */
  List<RequestStatistics.RecentRequest> recentRequests(int limit);

  /**
   * Replace the data of an {@code IN_MEMORY} service with the data it started with, create its default buckets
   * again, and forget the recorded requests.
   *
   * @throws UnsupportedOperationException if the service persists its data.
   */
  void reset();

  /**
   * Apply the lifecycle configurations of the buckets at a time, which LocalS3 never does by itself.
   *
   * @param bucketName the bucket whose configuration to apply; {@code null} for every bucket that has one.
   * @param now the time to apply the rules at.
   * @return the actions taken.
   * @throws com.robothy.s3.core.exception.BucketNotExistException if the bucket doesn't exist.
   * @see com.robothy.s3.core.service.LifecycleExecutionService
   */
  List<LifecycleActionAns> applyLifecycle(String bucketName, Instant now);

}
