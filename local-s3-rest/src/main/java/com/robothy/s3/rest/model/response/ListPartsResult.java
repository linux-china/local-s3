package com.robothy.s3.rest.model.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.robothy.s3.datatypes.enums.CheckSumAlgorithm;
import com.robothy.s3.datatypes.enums.ChecksumType;

import com.robothy.s3.datatypes.Owner;
import com.robothy.s3.datatypes.converter.AmazonInstantConverter;
import com.robothy.s3.datatypes.enums.StorageClass;
import java.time.Instant;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import tools.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import tools.jackson.dataformat.xml.annotation.JacksonXmlRootElement;

@AllArgsConstructor
@NoArgsConstructor
@Data
@Builder
@JacksonXmlRootElement(localName = "ListPartsResult")
public class ListPartsResult {

  @JacksonXmlProperty(localName = "Bucket")
  private String bucket;

  @JacksonXmlProperty(localName = "Key")
  private String key;

  @JacksonXmlProperty(localName = "UploadId")
  private String uploadId;

  @JacksonXmlProperty(localName = "PartNumberMarker")
  private int partNumberMarker;

  @JacksonXmlProperty(localName = "NextPartNumberMarker")
  private int nextPartNumberMarker;

  @JacksonXmlProperty(localName = "MaxParts")
  private int maxParts;

  @JacksonXmlProperty(localName = "IsTruncated")
  private boolean isTruncated;

  @JacksonXmlProperty(localName = "Part")
  @JacksonXmlElementWrapper(useWrapping = false)
  private List<Part> parts;

  @JacksonXmlProperty(localName = "Initiator")
  private Owner initiator;

  @JacksonXmlProperty(localName = "Owner")
  private Owner owner;

  @JacksonXmlProperty(localName = "StorageClass")
  private StorageClass storageClass;

  /**
   * The algorithm of the checksum of the upload; {@code null}, and left out, for none.
   */
  @JacksonXmlProperty(localName = "ChecksumAlgorithm")
  @JsonInclude(JsonInclude.Include.NON_NULL)
  private CheckSumAlgorithm checksumAlgorithm;

  @JacksonXmlProperty(localName = "ChecksumType")
  @JsonInclude(JsonInclude.Include.NON_NULL)
  private ChecksumType checksumType;

  @Builder
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Part {

    @JacksonXmlProperty(localName = "ETag")
    private String etag;

    @JacksonXmlProperty(localName = "LastModified")
    @JsonSerialize(converter = AmazonInstantConverter.class)
    private Instant lastModified;

    @JacksonXmlProperty(localName = "PartNumber")
    private int partNumber;

    @JacksonXmlProperty(localName = "Size")
    private long size;

    /**
     * The checksum of the part, without a type; {@code null}, and left out, for a part without one.
     */
    @JsonUnwrapped
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private ChecksumElements checksum;

    /**
     * Leaves the checksum out when a response that carries none of its elements is read.
     */
    public void setChecksum(ChecksumElements checksum) {
      this.checksum = ChecksumElements.nullIfEmpty(checksum);
    }

  }

}
