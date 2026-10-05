package com.robothy.s3.datatypes.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonRootName;
import com.robothy.s3.datatypes.converter.AmazonInstantConverter;
import java.time.Instant;
import tools.jackson.databind.annotation.JsonSerialize;

@JsonRootName("GetBucketResult")
public record GetBucketResult(
    @JsonProperty("Bucket") String bucket,
    @JsonProperty("PublicAccessBlockEnabled") boolean publicAccessBlockEnabled,
    @JsonProperty("CreationDate") @JsonSerialize(converter = AmazonInstantConverter.class) Instant creationDate) {

}
