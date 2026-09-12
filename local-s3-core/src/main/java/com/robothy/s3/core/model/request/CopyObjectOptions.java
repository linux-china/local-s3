package com.robothy.s3.core.model.request;

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

  private TaggingDirective taggingDirective;

  private String[][] tagging;

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
}