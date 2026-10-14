package com.robothy.s3.core.model.internal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UploadPartMetadata {

  private String etag;

  private long lastModified;

  private long size;

  private long fileId;

  /**
   * The hex encoded MD5 digest of the stored content of the part, which the entity tag of the object that the
   * part completes is computed from. Unlike {@linkplain #etag}, which a request may supply, it describes the bytes
   * that were stored. {@code null} for a part that a LocalS3 before 2.5 stored, whose content is digested when
   * the upload is completed instead.
   */
  private String contentMd5;

  /**
   * The checksum of the stored content of the part; {@code null} for a part that was uploaded without one.
   */
  private ObjectChecksum checksum;

  /**
   * The base64 encoded default checksum of a part uploaded without {@linkplain #checksum one}, which the default
   * checksum of the object is combined from without reading the part again. It is kept apart from {@linkplain
   * #checksum} because a part of an upload without a checksum algorithm answers none. {@code null} for a part with a
   * checksum, or one that a LocalS3 before default checksums stored.
   */
  private String defaultChecksum;

}
