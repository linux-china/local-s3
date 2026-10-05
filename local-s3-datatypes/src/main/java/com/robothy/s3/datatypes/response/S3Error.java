package com.robothy.s3.datatypes.response;

import com.fasterxml.jackson.annotation.JsonInclude;
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
 *
 * @param condition                   the name of the header whose condition didn't hold, which a
 *                                    {@code PreconditionFailed} error reports, e.g. {@code If-None-Match}.
 * @param clientComputedContentSha256 the {@code x-amz-content-sha256} that the client sent, which an
 *                                    {@code XAmzContentSHA256Mismatch} error reports.
 * @param s3ComputedContentSha256     the SHA-256 of the body that was received, which an
 *                                    {@code XAmzContentSHA256Mismatch} error reports.
 * @param hostId                      the {@code x-amz-id-2} of the response, which the body repeats, like Amazon S3
 *                                    does.
 */
@JacksonXmlRootElement(localName = "Error")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record S3Error(
    @JacksonXmlProperty(localName = "Code") String code,
    @JacksonXmlProperty(localName = "Message") String message,
    @JacksonXmlProperty(localName = "ArgumentName") String argumentName,
    @JacksonXmlProperty(localName = "ArgumentValue") String argumentValue,
    @JacksonXmlProperty(localName = "Condition") String condition,
    @JacksonXmlProperty(localName = "BucketName") String bucketName,
    @JacksonXmlProperty(localName = "Key") String key,
    @JacksonXmlProperty(localName = "VersionId") String versionId,
    @JacksonXmlProperty(localName = "ClientComputedContentSHA256") String clientComputedContentSha256,
    @JacksonXmlProperty(localName = "S3ComputedContentSHA256") String s3ComputedContentSha256,
    @JacksonXmlProperty(localName = "RequestId") String requestId,
    @JacksonXmlProperty(localName = "HostId") String hostId) {

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {

    private String code;
    private String message;
    private String argumentName;
    private String argumentValue;
    private String condition;
    private String bucketName;
    private String key;
    private String versionId;
    private String clientComputedContentSha256;
    private String s3ComputedContentSha256;
    private String requestId;
    private String hostId;

    private Builder() {
    }

    public Builder code(String code) {
      this.code = code;
      return this;
    }

    public Builder message(String message) {
      this.message = message;
      return this;
    }

    public Builder argumentName(String argumentName) {
      this.argumentName = argumentName;
      return this;
    }

    public Builder argumentValue(String argumentValue) {
      this.argumentValue = argumentValue;
      return this;
    }

    public Builder condition(String condition) {
      this.condition = condition;
      return this;
    }

    public Builder bucketName(String bucketName) {
      this.bucketName = bucketName;
      return this;
    }

    public Builder key(String key) {
      this.key = key;
      return this;
    }

    public Builder versionId(String versionId) {
      this.versionId = versionId;
      return this;
    }

    public Builder clientComputedContentSha256(String clientComputedContentSha256) {
      this.clientComputedContentSha256 = clientComputedContentSha256;
      return this;
    }

    public Builder s3ComputedContentSha256(String s3ComputedContentSha256) {
      this.s3ComputedContentSha256 = s3ComputedContentSha256;
      return this;
    }

    public Builder requestId(String requestId) {
      this.requestId = requestId;
      return this;
    }

    public Builder hostId(String hostId) {
      this.hostId = hostId;
      return this;
    }

    public S3Error build() {
      return new S3Error(code, message, argumentName, argumentValue, condition, bucketName, key, versionId,
          clientComputedContentSha256, s3ComputedContentSha256, requestId, hostId);
    }

  }

}
