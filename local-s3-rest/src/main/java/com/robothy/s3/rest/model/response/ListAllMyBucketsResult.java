package com.robothy.s3.rest.model.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.robothy.s3.datatypes.Owner;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import tools.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import tools.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import tools.jackson.dataformat.xml.annotation.JacksonXmlRootElement;

/**
 * The result of <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_ListBuckets.html">ListBuckets</a>.
 */
@JacksonXmlRootElement(localName = "ListAllMyBucketsResult")
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ListAllMyBucketsResult {

  @JacksonXmlElementWrapper(localName = "Buckets")
  @JacksonXmlProperty(localName = "Bucket")
  private List<S3Bucket> buckets;

  @JacksonXmlProperty(localName = "Owner")
  private Owner owner;

  /**
   * Continues the listing after this page; {@code null}, and left out, if the page is the last one.
   */
  @JacksonXmlProperty(localName = "ContinuationToken")
  private String continuationToken;

  /**
   * The prefix of the request; {@code null}, and left out, if the request carries none.
   */
  @JacksonXmlProperty(localName = "Prefix")
  private String prefix;

  public ListAllMyBucketsResult(List<S3Bucket> buckets, Owner owner) {
    this(buckets, owner, null, null);
  }

}
