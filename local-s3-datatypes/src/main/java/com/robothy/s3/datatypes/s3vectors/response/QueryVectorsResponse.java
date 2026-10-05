package com.robothy.s3.datatypes.s3vectors.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.robothy.s3.datatypes.s3vectors.DistanceMetric;
import java.util.List;

/**
 * Response object for QueryVectors operation.
 * Contains the vectors found in the approximate nearest neighbor search.
 *
 * @param vectors        the vectors in the approximate nearest neighbor search.
 * @param distanceMetric the distance metric configured for the vector index.
 * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_S3VectorBuckets_QueryVectors.html">QueryVectors API</a>
 */
public record QueryVectorsResponse(
    @JsonProperty("vectors") List<QueryOutputVector> vectors,
    @JsonProperty("distanceMetric") DistanceMetric distanceMetric) {

}
