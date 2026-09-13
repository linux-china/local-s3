package com.robothy.s3.rest.model.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import com.robothy.s3.datatypes.enums.StorageClass;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The answer of
 * <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_GetObjectAttributes.html">GetObjectAttributes</a>.
 *
 * <p>Every element is optional: the response carries only the attributes that the
 * {@code x-amz-object-attributes} header of the request asked for, so an element that wasn't asked for is
 * left {@code null} and dropped by {@linkplain JsonInclude}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JacksonXmlRootElement(localName = "GetObjectAttributesResponse")
public class GetObjectAttributesResult {

  @JacksonXmlProperty(localName = "ETag")
  private String etag;

  @JacksonXmlProperty(localName = "StorageClass")
  private StorageClass storageClass;

  @JacksonXmlProperty(localName = "ObjectSize")
  private Long objectSize;

  /**
   * The part layout of an object that a multipart upload stored, which is answered when the request asks
   * for {@code ObjectParts}.
   */
  @JacksonXmlProperty(localName = "ObjectParts")
  private ObjectParts objectParts;

  /**
   * A page of the parts that an object was uploaded in, which
   * {@code x-amz-max-parts} and {@code x-amz-part-number-marker} page through like {@code ListParts} does.
   */
  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class ObjectParts {

    /**
     * The number of parts the whole object has, which is not the number of parts on this page.
     */
    @JacksonXmlProperty(localName = "PartsCount")
    private int partsCount;

    @JacksonXmlProperty(localName = "PartNumberMarker")
    private int partNumberMarker;

    @JacksonXmlProperty(localName = "NextPartNumberMarker")
    private int nextPartNumberMarker;

    @JacksonXmlProperty(localName = "MaxParts")
    private int maxParts;

    /**
     * Named without the {@code is} prefix that the field of {@linkplain ListPartsResult} carries: Jackson
     * reads {@code isTruncated} as a property of its own as well as through the getter, which writes the
     * flag into the answer a second time under a name that is no element of the Amazon S3 response.
     */
    @JacksonXmlProperty(localName = "IsTruncated")
    private boolean truncated;

    @JacksonXmlProperty(localName = "Part")
    @JacksonXmlElementWrapper(useWrapping = false)
    private List<Part> parts;

  }

  /**
   * One part of the object. Amazon S3 answers the checksum of a part here as well, which LocalS3 stores
   * none of.
   */
  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Part {

    @JacksonXmlProperty(localName = "PartNumber")
    private int partNumber;

    @JacksonXmlProperty(localName = "Size")
    private long size;

  }

}
