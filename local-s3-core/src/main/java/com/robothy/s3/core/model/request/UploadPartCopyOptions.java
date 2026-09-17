package com.robothy.s3.core.model.request;

import com.robothy.s3.core.model.internal.CustomerEncryption;
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
   * The customer-provided key of the request, which must be the one the upload was created with; {@code null} for
   * none.
   */
  private CustomerEncryption customerEncryption;

  /**
   * The customer-provided key that the source object was stored with; {@code null} for none.
   */
  private CustomerEncryption sourceCustomerEncryption;

  /**
   * The {@code x-amz-copy-source-if-*} conditions that the source object must satisfy; {@code null} if the request
   * carries none.
   */
  @Builder.Default
  private ObjectPreconditions sourcePreconditions = ObjectPreconditions.none();

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

  /**
   * Get the conditions of the source object of the copy.
   *
   * @return the conditions; {@linkplain ObjectPreconditions#none()} if the request carries none.
   */
  public ObjectPreconditions getSourcePreconditions() {
    return sourcePreconditions != null ? sourcePreconditions : ObjectPreconditions.none();
  }

}
