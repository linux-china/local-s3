package com.robothy.s3.datatypes.s3vectors.response;

import java.util.List;

/**
 * Response object for DeleteVectors operation.
 * Contains lists of successful and failed vector keys.
 *
 * @param deletedVectorKeys list of vector keys that were successfully deleted.
 * @param errorVectorKeys   list of vector keys that failed to be deleted. Only present if there were errors.
 * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_S3VectorBuckets_DeleteVectors.html">DeleteVectors API</a>
 */
public record DeleteVectorsResponse(List<String> deletedVectorKeys, List<String> errorVectorKeys) {

}
