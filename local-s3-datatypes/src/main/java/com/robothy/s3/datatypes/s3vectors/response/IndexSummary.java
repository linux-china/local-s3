package com.robothy.s3.datatypes.s3vectors.response;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Summary information about a vector index.
 *
 * @param creationTime     date and time when the vector index was created.
 * @param indexArn         the Amazon Resource Name (ARN) of the vector index.
 * @param indexName        the name of the vector index.
 * @param vectorBucketName the name of the vector bucket that contains the vector index.
 * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_S3VectorBuckets_IndexSummary.html">IndexSummary API</a>
 */
public record IndexSummary(
    @JsonProperty("creationTime") Long creationTime,
    @JsonProperty("indexArn") String indexArn,
    @JsonProperty("indexName") String indexName,
    @JsonProperty("vectorBucketName") String vectorBucketName) {

}
