package com.robothy.s3.core.model.internal;

import com.robothy.s3.core.converters.deserializer.UploadPartMetadataMapConverter;
import com.robothy.s3.datatypes.enums.CheckSumAlgorithm;
import com.robothy.s3.datatypes.enums.ChecksumType;

import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.concurrent.ConcurrentSkipListMap;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import tools.jackson.databind.annotation.JsonDeserialize;

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

  /**
   * The system-defined metadata of the object besides its content type; {@code null} if it has none.
   */
  private SystemMetadata systemMetadata;

  private String[][] tagging;

  private Map<String, String> userMetadata;

  /**
   * The algorithm of the checksum of the object that the upload stores, which every part is uploaded with;
   * {@code null} if the upload was created without one, which stores an object without a checksum.
   */
  private CheckSumAlgorithm checksumAlgorithm;

  /**
   * The type of the checksum of the object that the upload stores; {@code null} if it has no
   * {@linkplain #checksumAlgorithm}.
   */
  private ChecksumType checksumType;

  /**
   * The Object Lock settings that the object the upload stores was requested with; {@code null} for none.
   */
  private ObjectLock objectLock;

  /**
   * The customer-provided key that the upload was created with, which every part must be uploaded with;
   * {@code null} for none.
   */
  private CustomerEncryption customerEncryption;

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
