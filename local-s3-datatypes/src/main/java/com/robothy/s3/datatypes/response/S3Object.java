package com.robothy.s3.datatypes.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.robothy.s3.datatypes.Owner;
import com.robothy.s3.datatypes.converter.AmazonInstantConverter;
import com.robothy.s3.datatypes.enums.CheckSumAlgorithm;
import com.robothy.s3.datatypes.enums.ChecksumType;
import com.robothy.s3.datatypes.enums.StorageClass;
import java.time.Instant;
import java.util.Comparator;
import tools.jackson.databind.annotation.JsonSerialize;

/**
 * Represents S3 <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_Object.html">Object</a>.
 *
 * @param checksumType  the type of the checksum of the object; {@code null}, and left out, for an object stored
 *                      without a checksum.
 * @param restoreStatus the restored copy of an archived object; {@code null}, and left out, unless the listing was
 *                      asked for it with {@code x-amz-optional-object-attributes: RestoreStatus} and the object has
 *                      a restored copy that hasn't expired.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"Key", "LastModified", "ETag", "ChecksumAlgorithm", "ChecksumType", "Size", "Owner",
    "StorageClass", "RestoreStatus"})
public record S3Object(
    @JsonProperty("ChecksumAlgorithm") CheckSumAlgorithm checkSumAlgorithm,
    @JsonProperty("ChecksumType") ChecksumType checksumType,
    @JsonProperty("ETag") String etag,
    @JsonProperty("Key") String key,
    @JsonProperty("LastModified") @JsonSerialize(converter = AmazonInstantConverter.class) Instant lastModified,
    @JsonProperty("Owner") Owner owner,
    @JsonProperty("Size") long size,
    @JsonProperty("StorageClass") StorageClass storageClass,
    @JsonProperty("RestoreStatus") RestoreStatus restoreStatus) {

  /**
   * Order by key ascending.
   */
  public static final Comparator<S3Object> ORDER_BY_KEY_ASC = Comparator.comparing(S3Object::key);

  /**
   * Order by last modified descending.
   */
  public static final Comparator<S3Object> ORDER_BY_LAST_MODIFIED_DESC = Comparator
      .comparing(S3Object::lastModified).reversed();

  public static Builder builder() {
    return new Builder();
  }

  public S3Object withKey(String key) {
    return new S3Object(checkSumAlgorithm, checksumType, etag, key, lastModified, owner, size, storageClass,
        restoreStatus);
  }

  public S3Object withOwner(Owner owner) {
    return new S3Object(checkSumAlgorithm, checksumType, etag, key, lastModified, owner, size, storageClass,
        restoreStatus);
  }

  public S3Object withRestoreStatus(RestoreStatus restoreStatus) {
    return new S3Object(checkSumAlgorithm, checksumType, etag, key, lastModified, owner, size, storageClass,
        restoreStatus);
  }

  public static final class Builder {

    private CheckSumAlgorithm checkSumAlgorithm;
    private ChecksumType checksumType;
    private String etag;
    private String key;
    private Instant lastModified;
    private Owner owner;
    private long size;
    private StorageClass storageClass;
    private RestoreStatus restoreStatus;

    private Builder() {
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

    public Builder size(long size) {
      this.size = size;
      return this;
    }

    public Builder storageClass(StorageClass storageClass) {
      this.storageClass = storageClass;
      return this;
    }

    public Builder restoreStatus(RestoreStatus restoreStatus) {
      this.restoreStatus = restoreStatus;
      return this;
    }

    public S3Object build() {
      return new S3Object(checkSumAlgorithm, checksumType, etag, key, lastModified, owner, size, storageClass,
          restoreStatus);
    }

  }

}
