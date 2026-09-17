package com.robothy.s3.rest.model.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonUnwrapped;


import com.robothy.s3.datatypes.converter.AmazonInstantConverter;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import tools.jackson.dataformat.xml.annotation.JacksonXmlRootElement;

@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@NoArgsConstructor
@AllArgsConstructor
@JacksonXmlRootElement(localName = "CopyObjectResult")
public class CopyObjectResult {

  @JacksonXmlProperty(localName = "LastModified")
  @JsonSerialize(converter = AmazonInstantConverter.class)
  private Instant lastModified;

  @JacksonXmlProperty(localName = "ETag")
  private String etag;

  /**
   * The checksum of the copy and its type; {@code null}, and left out, for a copy without a checksum.
   */
  @JsonUnwrapped
  private ChecksumElements checksum;

}
