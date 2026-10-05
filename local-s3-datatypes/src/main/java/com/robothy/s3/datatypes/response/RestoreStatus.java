package com.robothy.s3.datatypes.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.robothy.s3.datatypes.converter.AmazonInstantConverter;
import java.time.Instant;
import tools.jackson.databind.annotation.JsonSerialize;

/**
 * The <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_RestoreStatus.html">RestoreStatus</a> of an object
 * in a listing, which a request asks for with {@code x-amz-optional-object-attributes: RestoreStatus}. LocalS3 restores
 * at once, so a restore is never in progress.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RestoreStatus(
    @JsonProperty("IsRestoreInProgress") boolean restoreInProgress,
    @JsonProperty("RestoreExpiryDate") @JsonSerialize(converter = AmazonInstantConverter.class)
    Instant restoreExpiryDate) {

}
