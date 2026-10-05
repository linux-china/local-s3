package com.robothy.s3.datatypes.s3vectors.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Response object for ListIndexes operation.
 * Returns a list of all vector indexes within the specified vector bucket.
 *
 * @param indexes   the attributes of the vector indexes.
 * @param nextToken the next pagination token.
 * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_S3VectorBuckets_ListIndexes.html">ListIndexes API</a>
 */
public record ListIndexesResponse(
    @JsonProperty("indexes") List<IndexSummary> indexes,
    @JsonProperty("nextToken") String nextToken) {

}
