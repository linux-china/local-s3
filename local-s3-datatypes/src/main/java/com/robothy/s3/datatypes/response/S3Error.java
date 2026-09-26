package com.robothy.s3.datatypes.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import tools.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import tools.jackson.dataformat.xml.annotation.JacksonXmlRootElement;

/**
 * The {@code <Error>} document of a failed S3 request, written in the order Amazon S3 writes it: the code and
 * the message, the fields that say what the request named, and the two IDs of the request last.
 *
 * <p>A field that the error doesn't carry is left out, like Amazon S3 leaves it out, rather than written as an
 * empty element: an error of Amazon S3 names only what is relevant to it, e.g. {@code <Key>} and
 * {@code <BucketName>} for a {@code NoSuchKey}. LocalS3 wrote every field, empty when it had no value,
 * through 2.4.
 */
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Builder
@JacksonXmlRootElement(localName = "Error")
@EqualsAndHashCode
@JsonInclude(JsonInclude.Include.NON_NULL)
public class S3Error {

  @JacksonXmlProperty(localName = "Code")
  private String code;

  @JacksonXmlProperty(localName = "Message")
  private String message;

  @JacksonXmlProperty(localName = "ArgumentName")
  private String argumentName;

  @JacksonXmlProperty(localName = "ArgumentValue")
  private String argumentValue;

  /**
   * The name of the header whose condition didn't hold, which a {@code PreconditionFailed} error reports,
   * e.g. {@code If-None-Match}.
   */
  @JacksonXmlProperty(localName = "Condition")
  private String condition;

  @JacksonXmlProperty(localName = "BucketName")
  private String bucketName;

  @JacksonXmlProperty(localName = "Key")
  private String key;

  @JacksonXmlProperty(localName = "VersionId")
  private String versionId;

  /**
   * The {@code x-amz-content-sha256} that the client sent, which an {@code XAmzContentSHA256Mismatch} error reports.
   */
  @JacksonXmlProperty(localName = "ClientComputedContentSHA256")
  private String clientComputedContentSha256;

  /**
   * The SHA-256 of the body that was received, which an {@code XAmzContentSHA256Mismatch} error reports.
   */
  @JacksonXmlProperty(localName = "S3ComputedContentSHA256")
  private String s3ComputedContentSha256;

  @JacksonXmlProperty(localName = "RequestId")
  private String requestId;

  /**
   * The {@code x-amz-id-2} of the response, which the body repeats, like Amazon S3 does.
   */
  @JacksonXmlProperty(localName = "HostId")
  private String hostId;
}
