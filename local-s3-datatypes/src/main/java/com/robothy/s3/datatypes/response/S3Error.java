package com.robothy.s3.datatypes.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Builder
@JacksonXmlRootElement(localName = "Error")
@EqualsAndHashCode
public class S3Error {

  @JacksonXmlProperty(localName = "Code")
  private String code;

  @JacksonXmlProperty(localName = "Message")
  private String message;

  @JacksonXmlProperty(localName = "RequestId")
  private String requestId;

  @JacksonXmlProperty(localName = "ArgumentName")
  private String argumentName;

  @JacksonXmlProperty(localName = "ArgumentValue")
  private String argumentValue;

  /**
   * The name of the header whose condition didn't hold, which a {@code PreconditionFailed} error reports,
   * e.g. {@code If-None-Match}. Left out of an error that isn't about a condition; the other fields are
   * written even when they are empty, which the clients of LocalS3 have always seen.
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JacksonXmlProperty(localName = "Condition")
  private String condition;

  @JacksonXmlProperty(localName = "BucketName")
  private String bucketName;

  @JacksonXmlProperty(localName = "Key")
  private String key;

  @JacksonXmlProperty(localName = "VersionId")
  private String versionId;
}
