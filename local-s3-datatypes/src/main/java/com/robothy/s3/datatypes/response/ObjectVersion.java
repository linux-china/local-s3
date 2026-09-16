package com.robothy.s3.datatypes.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.robothy.s3.datatypes.Owner;
import com.robothy.s3.datatypes.converter.AmazonInstantConverter;
import com.robothy.s3.datatypes.enums.CheckSumAlgorithm;
import com.robothy.s3.datatypes.enums.StorageClass;
import java.time.Instant;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import tools.jackson.dataformat.xml.annotation.JacksonXmlRootElement;

@Builder
@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
@JacksonXmlRootElement(localName = "Version")
public class ObjectVersion implements VersionItem {

  @JacksonXmlProperty(localName = "IsLatest")
  protected boolean latest;

  @Setter
  @JacksonXmlProperty(localName = "Key")
  protected String key;

  @JsonSerialize(converter = AmazonInstantConverter.class)
  @JacksonXmlProperty(localName = "LastModified")
  protected Instant lastModified;

  @JacksonXmlProperty(localName = "Owner")
  protected Owner owner;

  @JacksonXmlProperty(localName = "VersionId")
  protected String versionId;

  /**
   * The algorithm of the checksum of the version; {@code null}, and left out, for a version stored without a checksum.
   * An empty element would be read by the AWS SDK as an algorithm it doesn't know.
   */
  @JacksonXmlProperty(localName = "ChecksumAlgorithm")
  private CheckSumAlgorithm checkSumAlgorithm;

  @JacksonXmlProperty(localName = "ETag")
  private String etag;

  @JacksonXmlProperty(localName = "Size")
  private long size;

  @JacksonXmlProperty(localName = "StorageClass")
  private StorageClass storageClass;

}
