package com.robothy.s3.datatypes.s3vectors.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Response object for GetVectors operation.
 *
 * @param vectors         the attributes of the vectors.
 * @param errorVectorKeys list of vector keys that failed to be retrieved. Only present if there were errors.
 * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_S3VectorBuckets_GetVectors.html">GetVectors API</a>
 */
public record GetVectorsResponse(
    @JsonProperty("vectors") List<GetOutputVector> vectors,
    @JsonProperty("errorVectorKeys") List<String> errorVectorKeys) {

}
