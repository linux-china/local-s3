package com.robothy.s3.rest.model.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;
import tools.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import tools.jackson.dataformat.xml.annotation.JacksonXmlRootElement;

@Builder
@AllArgsConstructor
@NoArgsConstructor
@JacksonXmlRootElement(localName = "InitiateMultipartUploadResult")
public class InitiateMultipartUploadResult {

  @JacksonXmlProperty(localName = "Bucket")
  private String bucket;

  @JacksonXmlProperty(localName = "Key")
  private String key;

  @JacksonXmlProperty(localName = "UploadId")
  private String uploadId;

}
