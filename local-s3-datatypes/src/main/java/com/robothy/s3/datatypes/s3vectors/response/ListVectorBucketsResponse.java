package com.robothy.s3.datatypes.s3vectors.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Response object for ListVectorBuckets operation.
 * Returns a list of all vector buckets owned by the authenticated sender.
 *
 * @param nextToken     included in the response when there are more buckets to be listed with pagination.
 * @param vectorBuckets the list of vector buckets owned by the requester.
 * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_S3VectorBuckets_ListVectorBuckets.html">ListVectorBuckets API</a>
 */
public record ListVectorBucketsResponse(
    @JsonProperty("nextToken") String nextToken,
    @JsonProperty("vectorBuckets") List<VectorBucketSummary> vectorBuckets) {

}
