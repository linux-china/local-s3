package com.robothy.s3.datatypes.s3vectors.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for TagResource operation, whose body carries the tags; the resource is named by the
 * {@code resourceArn} of the path, {@code POST /tags/{resourceArn}}.
 *
 * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_S3VectorBuckets_TagResource.html">TagResource API</a>
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TagResourceRequest {

  /**
   * The tags to add to the vector bucket or index, replacing the value of a tag whose key it already has.
   * Map Entries: Maximum number of 50 items.
   * Required: Yes
   */
  @JsonProperty("tags")
  private Map<String, String> tags;

}
