package com.robothy.s3.datatypes.s3vectors.response;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response DTO for CreateVectorBucket operation.
 *
 * @param vectorBucketName the name of the created vector bucket.
 * @param vectorBucketArn  the Amazon Resource Name (ARN) of the created vector bucket.
 * @param creationDate     the creation date and time of the vector bucket in ISO 8601 format.
 * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_S3VectorBuckets_CreateVectorBucket.html">CreateVectorBucket API</a>
 */
public record CreateVectorBucketResponse(
    @JsonProperty("vectorBucketName") String vectorBucketName,
    @JsonProperty("vectorBucketArn") String vectorBucketArn,
    @JsonProperty("creationDate") String creationDate) {

}
