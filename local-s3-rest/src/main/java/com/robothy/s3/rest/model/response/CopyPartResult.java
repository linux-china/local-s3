package com.robothy.s3.rest.model.response;


import com.robothy.s3.datatypes.converter.AmazonInstantConverter;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import tools.jackson.dataformat.xml.annotation.JacksonXmlRootElement;

/**
 * The body that
 * <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_UploadPartCopy.html">UploadPartCopy</a>
 * answers with.
 */
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JacksonXmlRootElement(localName = "CopyPartResult")
public class CopyPartResult {

  @JacksonXmlProperty(localName = "LastModified")
  @JsonSerialize(converter = AmazonInstantConverter.class)
  private Instant lastModified;

  @JacksonXmlProperty(localName = "ETag")
  private String etag;

}
