package com.robothy.s3.core.model.request;

import java.util.Optional;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;

/**
 * Options of
 * <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_UploadPartCopy.html">UploadPartCopy</a>,
 * which uploads a part by copying a range of an object that is already stored.
 */
@Getter
@Builder
@EqualsAndHashCode
public class UploadPartCopyOptions {

  private String sourceBucket;

  private String sourceKey;

  private String sourceVersion;

  private Range copySourceRange;

  /**
   * Get the version of the source object to copy.
   *
   * @return the requested version; empty to copy the latest one.
   */
  public Optional<String> getSourceVersion() {
    return Optional.ofNullable(sourceVersion);
  }

  /**
   * Get the range of the source object that the part is copied from.
   *
   * @return the requested range; empty to copy the whole source object.
   */
  public Optional<Range> getCopySourceRange() {
    return Optional.ofNullable(copySourceRange);
  }

}
