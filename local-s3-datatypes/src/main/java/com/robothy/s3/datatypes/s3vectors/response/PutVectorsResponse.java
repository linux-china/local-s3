package com.robothy.s3.datatypes.s3vectors.response;

import java.util.List;

/**
 * Response object for PutVectors operation.
 *
 * @param errorVectorKeys list of vector keys that failed to be put. Only present if there were errors.
 * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_S3VectorBuckets_PutVectors.html">PutVectors API</a>
 */
public record PutVectorsResponse(List<String> errorVectorKeys) {

}
