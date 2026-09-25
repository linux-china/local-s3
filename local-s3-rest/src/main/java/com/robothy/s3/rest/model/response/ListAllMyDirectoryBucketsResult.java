package com.robothy.s3.rest.model.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import tools.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import tools.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import tools.jackson.dataformat.xml.annotation.JacksonXmlRootElement;

/**
 * The result of <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_ListDirectoryBuckets.html">
 * ListDirectoryBuckets</a>. Unlike {@linkplain ListAllMyBucketsResult}, it has no owner and no prefix.
 */
@JacksonXmlRootElement(localName = "ListAllMyDirectoryBucketsResult")
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ListAllMyDirectoryBucketsResult {

  @JacksonXmlElementWrapper(localName = "Buckets")
  @JacksonXmlProperty(localName = "Bucket")
  private List<S3Bucket> buckets;

  /**
   * Continues the listing after this page; {@code null}, and left out, if the page is the last one.
   */
  @JacksonXmlProperty(localName = "ContinuationToken")
  private String continuationToken;

}
