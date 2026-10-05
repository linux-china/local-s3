package com.robothy.s3.datatypes.s3vectors.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.robothy.s3.datatypes.s3vectors.VectorIndex;

/**
 * Response object for retrieving a vector index.
 *
 * @param index the attributes of the vector index.
 * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_S3VectorBuckets_GetIndex.html">GetIndex API</a>
 */
public record GetIndexResponse(@JsonProperty("index") VectorIndex index) {

}
