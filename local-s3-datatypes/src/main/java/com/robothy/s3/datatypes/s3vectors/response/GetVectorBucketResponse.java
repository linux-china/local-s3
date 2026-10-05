package com.robothy.s3.datatypes.s3vectors.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.robothy.s3.datatypes.s3vectors.VectorBucket;

/**
 * Response DTO for GetVectorBucket operation.
 *
 * @param vectorBucket the vector bucket information.
 * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_S3VectorBuckets_GetVectorBucket.html">GetVectorBucket API</a>
 */
public record GetVectorBucketResponse(@JsonProperty("vectorBucket") VectorBucket vectorBucket) {

}
