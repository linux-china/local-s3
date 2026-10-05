package com.robothy.s3.datatypes.s3vectors.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.robothy.s3.datatypes.s3vectors.ListOutputVector;
import java.util.List;

/**
 * Response object for listing vectors in a vector index.
 *
 * @param vectors   vectors in the current segment.
 * @param nextToken pagination token to be used in the subsequent request; empty if no further pagination is
 *                  required.
 * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_S3VectorBuckets_ListVectors.html">ListVectors API</a>
 */
public record ListVectorsResponse(
    @JsonProperty("vectors") List<ListOutputVector> vectors,
    @JsonProperty("nextToken") String nextToken) {

}
