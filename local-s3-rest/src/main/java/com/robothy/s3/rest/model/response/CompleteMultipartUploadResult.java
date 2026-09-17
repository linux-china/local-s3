package com.robothy.s3.rest.model.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonUnwrapped;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import tools.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import tools.jackson.dataformat.xml.annotation.JacksonXmlRootElement;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@NoArgsConstructor
@AllArgsConstructor
@JacksonXmlRootElement(localName = "CompleteMultipartUploadResult")
public class CompleteMultipartUploadResult {

  @JacksonXmlProperty(localName = "Location")
  private String location;

  @JacksonXmlProperty(localName = "Bucket")
  private String bucket;

  @JacksonXmlProperty(localName = "Key")
  private String key;

  @JacksonXmlProperty(localName = "ETag")
  private String etag;

  /**
   * The checksum of the object and its type; {@code null}, and left out, for an object without a checksum.
   */
  @JsonUnwrapped
  private ChecksumElements checksum;

  /**
   * Leaves the checksum out when a response that carries none of its elements is read.
   */
  public void setChecksum(ChecksumElements checksum) {
    this.checksum = ChecksumElements.nullIfEmpty(checksum);
  }

}
