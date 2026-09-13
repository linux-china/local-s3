package com.robothy.s3.core.model.internal;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.robothy.s3.core.converters.deserializer.UploadPartMetadataMapConverter;

import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.concurrent.ConcurrentSkipListMap;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The state of a multipart upload that was created but not completed yet, which holds every part that was
 * uploaded to it; Amazon S3 allows 10,000 of them.
 *
 * <p>Like {@linkplain BucketMetadata}, it carries no {@code equals} and {@code hashCode} of its own: an
 * upload is mutable state that {@code UploadPart} adds to, so two of them are the same upload only if
 * they are the same instance.
 */
@Getter
@Setter
@NoArgsConstructor
@Builder
@AllArgsConstructor
public class UploadMetadata {

  private long createDate;

  private String contentType;

  private String[][] tagging;

  private Map<String, String> userMetadata;

  @JsonDeserialize(converter = UploadPartMetadataMapConverter.class)
  @Builder.Default
  private NavigableMap<Integer, UploadPartMetadata> parts = new ConcurrentSkipListMap<>();


  public Optional<String[][]> getTagging() {
    return Optional.ofNullable(tagging);
  }

  /**
   * Names when the upload was created and what it stores, and leaves out its parts, which a generated
   * {@code toString} would dump all 10,000 of. Read them through {@linkplain #getParts()} instead.
   */
  @Override
  public String toString() {
    return "UploadMetadata(createDate=" + createDate + ", contentType=" + contentType + ")";
  }

}
