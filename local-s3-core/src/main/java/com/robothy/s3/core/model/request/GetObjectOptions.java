package com.robothy.s3.core.model.request;

import java.util.Optional;
import lombok.Builder;
import lombok.Getter;

@Builder
@Getter
public class GetObjectOptions {

  @Deprecated
  private String bucketName;

  @Deprecated
  private String key;

  private String versionId;

  private Range range;

  /**
   * The conditions that the object must satisfy for the read to answer with it; {@code null} if the
   * request is unconditional.
   */
  private ObjectPreconditions preconditions;

  public Optional<String> getVersionId() {
    return Optional.ofNullable(versionId);
  }

  public Optional<Range> getRange() {
    return Optional.ofNullable(range);
  }

  /**
   * Get the conditions that the object must satisfy.
   *
   * @return the preconditions of the request; {@linkplain ObjectPreconditions#none()} if it carries none.
   */
  public ObjectPreconditions getPreconditions() {
    return Optional.ofNullable(preconditions).orElseGet(ObjectPreconditions::none);
  }

}
