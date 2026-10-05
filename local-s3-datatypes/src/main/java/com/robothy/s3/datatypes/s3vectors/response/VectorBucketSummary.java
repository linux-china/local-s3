package com.robothy.s3.datatypes.s3vectors.response;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Summary information about a vector bucket.
 *
 * @param creationTime     date and time when the vector bucket was created.
 * @param vectorBucketArn  the Amazon Resource Name (ARN) of the vector bucket.
 * @param vectorBucketName the name of the vector bucket.
 * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_S3VectorBuckets_VectorBucketSummary.html">VectorBucketSummary API</a>
 */
public record VectorBucketSummary(
    @JsonProperty("creationTime") Long creationTime,
    @JsonProperty("vectorBucketArn") String vectorBucketArn,
    @JsonProperty("vectorBucketName") String vectorBucketName) {

}
