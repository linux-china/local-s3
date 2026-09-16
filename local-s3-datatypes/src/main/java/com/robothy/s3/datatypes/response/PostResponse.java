package com.robothy.s3.datatypes.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonRootName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The document that a {@code PostObject} request answers with if its {@code success_action_status} is {@code 201}.
 */
@Getter
@Setter
@Builder
@EqualsAndHashCode
@AllArgsConstructor
@NoArgsConstructor
@JsonRootName("PostResponse")
public class PostResponse {

  @JsonProperty("Location")
  private String location;

  @JsonProperty("Bucket")
  private String bucket;

  @JsonProperty("Key")
  private String key;

  @JsonProperty("ETag")
  private String etag;

}
