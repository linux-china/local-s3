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
 * @param notImplemented the number of recorded requests answered {@code 501 NotImplemented}, by the operation that
 *     LocalS3 doesn't implement, e.g. {@code SelectObjectContent}, or by the method, path shape and query parameters of
 *     a request that no route matches, e.g. {@code GET /{bucket}?analytics}; see
 *     {@linkplain RequestStatistics#notImplemented()}.
 */
public record ServiceStatistics(String mode, String startedAt, long uptimeSeconds, int inFlightRequests,
                                ObjectStatistics data, VectorStatistics vectors, long totalRequests,
                                Map<String, RequestStatistics.OperationStatistics> operations,
                                Map<String, Long> notImplemented) {
}
