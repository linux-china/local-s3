package com.robothy.s3.rest.model.request;

import lombok.Data;
import tools.jackson.dataformat.xml.annotation.JacksonXmlProperty;

@Data
public class CompletedPart {

  @JacksonXmlProperty(localName = "ChecksumCRC32")
  private String checksumCRC32;

  @JacksonXmlProperty(localName = "ChecksumCRC32C")
  private String checksumCRC32C;

  @JacksonXmlProperty(localName = "ChecksumCRC64NVME")
  private String checksumCRC64NVME;

  @JacksonXmlProperty(localName = "ChecksumSHA1")
  private String checksumSHA1;

  @JacksonXmlProperty(localName = "ChecksumSHA256")
  private String checksumSHA256;

  @JacksonXmlProperty(localName = "ETag")
  private String etag;

  @JacksonXmlProperty(localName = "PartNumber")
  private int partNumber;

}
