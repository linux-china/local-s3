package com.robothy.s3.datatypes.s3vectors.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * Response DTO for ListTagsForResource operation.
 *
 * @param tags the tags of the vector bucket or index; empty if it has none.
 * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_S3VectorBuckets_ListTagsForResource.html">ListTagsForResource API</a>
 */
public record ListTagsForResourceResponse(@JsonProperty("tags") Map<String, String> tags) {

}
