package com.robothy.s3.datatypes.response;

import com.robothy.s3.datatypes.Owner;
import com.robothy.s3.datatypes.converter.AmazonInstantConverter;
import java.time.Instant;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import tools.jackson.dataformat.xml.annotation.JacksonXmlRootElement;

@JacksonXmlRootElement(localName = "DeleteMarker")
public record DeleteMarkerEntry(
    @JacksonXmlProperty(localName = "IsLatest") boolean latest,
    @JacksonXmlProperty(localName = "Key") String key,
    @JacksonXmlProperty(localName = "LastModified") @JsonSerialize(converter = AmazonInstantConverter.class)
    Instant lastModified,
    @JacksonXmlProperty(localName = "Owner") Owner owner,
    @JacksonXmlProperty(localName = "VersionId") String versionId) implements VersionItem {

  public static Builder builder() {
    return new Builder();
  }

  @Override
  public DeleteMarkerEntry withKey(String key) {
    return new DeleteMarkerEntry(latest, key, lastModified, owner, versionId);
  }

  public static final class Builder {

    private boolean latest;
    private String key;
    private Instant lastModified;
    private Owner owner;
    private String versionId;

    private Builder() {
    }

    public Builder latest(boolean latest) {
      this.latest = latest;
      return this;
    }

    public Builder key(String key) {
      this.key = key;
      return this;
    }

    public Builder lastModified(Instant lastModified) {
      this.lastModified = lastModified;
      return this;
    }

    public Builder owner(Owner owner) {
      this.owner = owner;
      return this;
    }

    public Builder versionId(String versionId) {
      this.versionId = versionId;
      return this;
    }

    public DeleteMarkerEntry build() {
      return new DeleteMarkerEntry(latest, key, lastModified, owner, versionId);
    }

  }

}
