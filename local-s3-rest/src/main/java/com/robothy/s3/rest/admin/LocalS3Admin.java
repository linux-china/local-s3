package com.robothy.s3.rest.admin;

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

}
