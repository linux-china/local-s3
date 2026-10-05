package com.robothy.s3.datatypes.s3vectors.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.robothy.s3.datatypes.s3vectors.request.PutInputVector;
import tools.jackson.databind.JsonNode;

/**
 * Represents an output vector for GetVectors operation.
 * Contains vector key and optional data/metadata based on request parameters.
 *
 * @param data     the vector data (included if returnData was true in request).
 * @param key      the unique identifier for this vector.
 * @param metadata user-defined metadata associated with this vector (included if returnMetadata was true in request).
 * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_S3VectorBuckets_GetOutputVector.html">GetOutputVector</a>
 */
public record GetOutputVector(
    @JsonProperty("data") PutInputVector.VectorData data,
    @JsonProperty("key") String key,
    @JsonProperty("metadata") JsonNode metadata) {

}
