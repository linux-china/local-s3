package com.robothy.s3.datatypes.s3vectors.response;

import com.fasterxml.jackson.annotation.JsonProperty;

public record S3VectorsError(@JsonProperty("message") String message) {

}
