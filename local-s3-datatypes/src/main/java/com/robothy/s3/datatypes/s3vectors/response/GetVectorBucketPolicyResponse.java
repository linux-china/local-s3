package com.robothy.s3.datatypes.s3vectors.response;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response DTO for GetVectorBucketPolicy operation.
 * If the action is successful, the service sends back an HTTP 200 response with the policy JSON.
 *
 * @param policy the JSON that defines the policy.
 * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_S3VectorBuckets_GetVectorBucketPolicy.html">GetVectorBucketPolicy API</a>
 */
public record GetVectorBucketPolicyResponse(@JsonProperty("policy") String policy) {

}
