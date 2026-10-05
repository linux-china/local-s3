package com.robothy.s3.datatypes.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.robothy.s3.datatypes.Owner;
import com.robothy.s3.datatypes.converter.AmazonInstantConverter;
import com.robothy.s3.datatypes.enums.CheckSumAlgorithm;
import com.robothy.s3.datatypes.enums.ChecksumType;
import com.robothy.s3.datatypes.enums.StorageClass;
import java.time.Instant;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import tools.jackson.dataformat.xml.annotation.JacksonXmlRootElement;

/**
 * A version of an object in a {@code ListObjectVersions} answer.
 *
 * @param checkSumAlgorithm the algorithm of the checksum of the version; {@code null}, and left out, for a version
 *                          stored without a checksum. An empty element would be read by the AWS SDK as an algorithm
 *                          it doesn't know.
 * @param checksumType      the type of the checksum of the version; {@code null}, and left out, for a version stored
 *                          without a checksum.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JacksonXmlRootElement(localName = "Version")
public record ObjectVersion(
    @JacksonXmlProperty(localName = "IsLatest") boolean latest,
    @JacksonXmlProperty(localName = "Key") String key,
    @JsonSerialize(converter = AmazonInstantConverter.class) @JacksonXmlProperty(localName = "LastModified")
    Instant lastModified,
    @JacksonXmlProperty(localName = "Owner") Owner owner,
    @JacksonXmlProperty(localName = "VersionId") String versionId,
    @JacksonXmlProperty(localName = "ChecksumAlgorithm") CheckSumAlgorithm checkSumAlgorithm,
    @JacksonXmlProperty(localName = "ChecksumType") ChecksumType checksumType,
    @JacksonXmlProperty(localName = "ETag") String etag,
    @JacksonXmlProperty(localName = "Size") long size,
    @JacksonXmlProperty(localName = "StorageClass") StorageClass storageClass) implements VersionItem {

  public static Builder builder() {
    return new Builder();
  }

  @Override
  public ObjectVersion withKey(String key) {
    return new ObjectVersion(latest, key, lastModified, owner, versionId, checkSumAlgorithm, checksumType, etag, size,
        storageClass);
  }

  public static final class Builder {

    private boolean latest;
    private String key;
    private Instant lastModified;
    private Owner owner;
    private String versionId;
    private CheckSumAlgorithm checkSumAlgorithm;
    private ChecksumType checksumType;
    private String etag;
    private long size;
    private StorageClass storageClass;

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

    public Builder checkSumAlgorithm(CheckSumAlgorithm checkSumAlgorithm) {
      this.checkSumAlgorithm = checkSumAlgorithm;
      return this;
    }

    public Builder checksumType(ChecksumType checksumType) {
      this.checksumType = checksumType;
      return this;
    }

    public Builder etag(String etag) {
      this.etag = etag;
      return this;
    }

    public Builder size(long size) {
      this.size = size;
      return this;
    }

    public Builder storageClass(StorageClass storageClass) {
      this.storageClass = storageClass;
      return this;
    }

    public ObjectVersion build() {
      return new ObjectVersion(latest, key, lastModified, owner, versionId, checkSumAlgorithm, checksumType, etag,
          size, storageClass);
    }

  }

}
