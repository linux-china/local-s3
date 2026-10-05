package com.robothy.s3.datatypes.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonRootName;

/**
 * The document that a {@code PostObject} request answers with if its {@code success_action_status} is {@code 201}.
 */
@JsonRootName("PostResponse")
public record PostResponse(
    @JsonProperty("Location") String location,
    @JsonProperty("Bucket") String bucket,
    @JsonProperty("Key") String key,
    @JsonProperty("ETag") String etag) {

}
