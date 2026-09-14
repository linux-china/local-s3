package com.robothy.s3.core.model.request;

import com.robothy.s3.core.model.internal.SystemMetadata;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;

@Getter
@Builder
@EqualsAndHashCode
public class CopyObjectOptions {

  /**
   * Metadata directive for copy operation.
   */
  public enum MetadataDirective {
    /**
     * Copy metadata from source object (default).
     */
    COPY,

    /**
     * Replace with metadata provided in the request.
     */
    REPLACE
  }

  /**
   * Tagging directive for copy operation.
   */
  public enum TaggingDirective {
    /**
     * Copy the tagging of the source object (default).
     */
    COPY,

    /**
     * Replace with the tagging provided in the request.
     */
    REPLACE
  }

  private String sourceBucket;

  private String sourceKey;

  private String sourceVersion;

  private MetadataDirective metadataDirective;

  private Map<String, String> userMetadata;

  /**
   * The content type of the copy when {@linkplain #getMetadataDirective()} is {@linkplain MetadataDirective#REPLACE};
   * {@code null} if the request carries none.
   */
  private String contentType;

  /**
   * The system-defined metadata of the copy when {@linkplain #getMetadataDirective()} is
   * {@linkplain MetadataDirective#REPLACE}; {@code null} if the request carries none.
   */
  private SystemMetadata systemMetadata;

  private TaggingDirective taggingDirective;

  private String[][] tagging;

  /**
   * The {@code If-Match} and {@code If-None-Match} conditions that the object the destination key holds must satisfy;
   * {@code null} if the request carries none.
   */
  @Builder.Default
  private ObjectPreconditions preconditions = ObjectPreconditions.none();

  /**
   * The {@code x-amz-copy-source-if-*} conditions that the source object must satisfy; {@code null} if the request
   * carries none.
   */
  @Builder.Default
  private ObjectPreconditions sourcePreconditions = ObjectPreconditions.none();

  public Optional<String> getSourceVersion() {
    return Optional.ofNullable(sourceVersion);
  }

  public MetadataDirective getMetadataDirective() {
    return metadataDirective != null ? metadataDirective : MetadataDirective.COPY;
  }

  public Map<String, String> getUserMetadata() {
    return userMetadata != null ? userMetadata : Collections.emptyMap();
  }

  public TaggingDirective getTaggingDirective() {
    return taggingDirective != null ? taggingDirective : TaggingDirective.COPY;
  }

  /**
   * Get the tagging to apply when {@linkplain #getTaggingDirective()} is {@linkplain TaggingDirective#REPLACE}.
   *
   * @return the requested tagging; empty to leave the copy untagged.
   */
  public Optional<String[][]> getTagging() {
    return Optional.ofNullable(tagging);
  }

  /**
   * Get the conditions of the destination of the copy, which are evaluated like the ones of {@code PutObject}.
   *
   * @return the conditions; {@linkplain ObjectPreconditions#none()} if the request carries none.
   */
  public ObjectPreconditions getPreconditions() {
    return preconditions != null ? preconditions : ObjectPreconditions.none();
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