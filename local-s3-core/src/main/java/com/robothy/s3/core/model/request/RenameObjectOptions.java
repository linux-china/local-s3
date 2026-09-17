package com.robothy.s3.core.model.request;

import java.util.Objects;
import lombok.Builder;
import lombok.Getter;

/**
 * Options of <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_RenameObject.html">RenameObject</a>, which
 * renames an object within its bucket.
 */
@Getter
@Builder
public class RenameObjectOptions {

  /**
   * The key of the object to rename, in the same bucket.
   */
  private String sourceKey;

  /**
   * The {@code If-Match}, {@code If-None-Match}, {@code If-Modified-Since} and {@code If-Unmodified-Since} conditions
   * that the object the destination key holds must satisfy; {@code null} for none.
   */
  private ObjectPreconditions preconditions;

  /**
   * The {@code x-amz-rename-source-if-*} conditions that the object to rename must satisfy; {@code null} for none.
   */
  private ObjectPreconditions sourcePreconditions;

  /**
   * Get the conditions of the destination key.
   *
   * @return the conditions; {@linkplain ObjectPreconditions#none()} if the request carries none.
   */
  public ObjectPreconditions getPreconditions() {
    return Objects.requireNonNullElseGet(preconditions, ObjectPreconditions::none);
  }

  /**
   * Get the conditions of the object to rename.
   *
   * @return the conditions; {@linkplain ObjectPreconditions#none()} if the request carries none.
   */
  public ObjectPreconditions getSourcePreconditions() {
    return Objects.requireNonNullElseGet(sourcePreconditions, ObjectPreconditions::none);
  }

}
