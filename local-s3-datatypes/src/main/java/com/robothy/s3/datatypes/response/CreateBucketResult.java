package com.robothy.s3.datatypes.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonRootName;

@JsonRootName("CreateBucketResult")
public record CreateBucketResult(@JsonProperty("BucketArn") String bucketArn) {

}
