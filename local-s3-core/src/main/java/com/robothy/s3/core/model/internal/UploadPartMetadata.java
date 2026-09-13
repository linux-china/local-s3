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

}
