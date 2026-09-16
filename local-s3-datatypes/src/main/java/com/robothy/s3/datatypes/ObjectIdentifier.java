package com.robothy.s3.datatypes;

import java.time.Instant;
import java.util.Optional;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import tools.jackson.dataformat.xml.annotation.JacksonXmlProperty;


/**
 * An object of a <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_ObjectIdentifier.html">DeleteObjects</a>
 * request, optionally with the conditions of a conditional delete.
 */
@NoArgsConstructor
@Getter
@Setter
public class ObjectIdentifier {

  @JacksonXmlProperty(localName = "Key")
  private String key;

  @JacksonXmlProperty(localName = "VersionId")
  private String versionId;

  /**
   * Deletes the object only if its current version has this entity tag, or exists at all for {@code *};
   * {@code null} if the request doesn't carry it.
   */
  @JacksonXmlProperty(localName = "ETag")
  private String eTag;

  /**
   * Deletes the object only if it was last modified at this time; {@code null} if the request doesn't carry it.
   */
  @JacksonXmlProperty(localName = "LastModifiedTime")
  private Instant lastModifiedTime;

  /**
   * Deletes the object only if it has this many bytes; {@code null} if the request doesn't carry it.
   */
  @JacksonXmlProperty(localName = "Size")
  private Long size;

  public ObjectIdentifier(String key, String versionId) {
    this.key = key;
    this.versionId = versionId;
  }

  public Optional<String> getVersionId() {
    return Optional.ofNullable(versionId);
  }

}
