package com.robothy.s3.rest.model.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.robothy.s3.datatypes.converter.AmazonInstantConverter;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Setter;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import tools.jackson.dataformat.xml.annotation.JacksonXmlRootElement;

/**
 * A bucket of {@linkplain ListAllMyBucketsResult}.
 */
@JacksonXmlRootElement(localName = "Bucket")
@Setter
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class S3Bucket {

  @JacksonXmlProperty(localName = "Name")
  private String name;

  @JacksonXmlProperty(localName = "CreationDate")
  @JsonSerialize(converter = AmazonInstantConverter.class)
  private Instant creationDate;

  /**
   * The region of the bucket; {@code null} to leave it out.
   */
  @JacksonXmlProperty(localName = "BucketRegion")
  private String bucketRegion;

  public S3Bucket(String name, Instant creationDate) {
    this(name, creationDate, null);
  }

}
