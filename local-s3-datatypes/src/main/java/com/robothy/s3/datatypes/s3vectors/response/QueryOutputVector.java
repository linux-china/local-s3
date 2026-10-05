package com.robothy.s3.datatypes.s3vectors.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.robothy.s3.datatypes.s3vectors.request.PutInputVector;
import tools.jackson.databind.JsonNode;

/**
 * Represents an output vector for QueryVectors operation.
 * Contains vector key and optional distance, data, and metadata based on request parameters.
 *
 * @param data     the vector data (included if returnData was true in request).
 * @param distance the computed distance between the query vector and this vector; included if returnDistance was
 *                 true in request.
 * @param key      the unique identifier for this vector.
 * @param metadata user-defined metadata associated with this vector; included if returnMetadata was true in request.
 * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_S3VectorBuckets_QueryOutputVector.html">QueryOutputVector</a>
 */
public record QueryOutputVector(
    @JsonProperty("data") PutInputVector.VectorData data,
    @JsonProperty("distance") Double distance,
    @JsonProperty("key") String key,
    @JsonProperty("metadata") JsonNode metadata) {

}
