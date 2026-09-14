package com.robothy.s3.rest.admin;

import com.robothy.s3.core.service.manager.ObjectStatistics;
import com.robothy.s3.core.service.manager.vectors.VectorStatistics;
import java.util.Map;

/**
 * The statistics of a running LocalS3 service.
 *
 * @param mode the mode of the service, {@code IN_MEMORY} or {@code PERSISTENCE}.
 * @param startedAt when the service started, in ISO-8601.
 * @param uptimeSeconds the seconds since the service started.
 * @param inFlightRequests the requests being handled, or their responses written, including the one that asks.
 * @param data the Amazon S3 data of the service.
 * @param vectors the S3 Vectors data of the service.
 * @param totalRequests the number of requests recorded since the service started or was reset, besides the health
 *     checks and the requests of the {@code /_admin} endpoints.
 * @param operations the statistics of the recorded requests by operation, e.g. {@code PutObject}.
 */
public record ServiceStatistics(String mode, String startedAt, long uptimeSeconds, int inFlightRequests,
                                ObjectStatistics data, VectorStatistics vectors, long totalRequests,
                                Map<String, RequestStatistics.OperationStatistics> operations) {
}
