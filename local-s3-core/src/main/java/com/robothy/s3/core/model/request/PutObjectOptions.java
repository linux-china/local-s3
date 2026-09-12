package com.robothy.s3.core.model.request;

import java.io.InputStream;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;

@Builder
@Getter
@EqualsAndHashCode(exclude = "content")
public class PutObjectOptions {

  private String contentType;

  private long size;

  private InputStream content;

  private String contentMd5;

  private String[][] tagging;

  private Map<String, String> userMetadata;

  /**
   * The conditions that the object the key holds must satisfy for the put to store the new one; {@code null}
   * if the request is unconditional.
   */
  private ObjectPreconditions preconditions;

  /**
   * Get tagging in the put object request.
   *
   * @return tagging.
   */
  public Optional<String[][]> getTagging() {
    return Optional.ofNullable(tagging);
  }

  /**
   * Get the conditions that the object the key holds must satisfy.
   *
   * @return the preconditions of the request; {@linkplain ObjectPreconditions#none()} if it carries none.
   */
  public ObjectPreconditions getPreconditions() {
    return Optional.ofNullable(preconditions).orElseGet(ObjectPreconditions::none);
  }

}
