package com.robothy.s3.core.model.answers;

import com.robothy.s3.core.model.internal.ObjectChecksum;
import com.robothy.s3.core.model.internal.ServerSideEncryption;
import lombok.Builder;
import lombok.Getter;

@Builder
@Getter
public class CopyObjectAns {

  private String etag;

  private long size;

  private String sourceVersionId;

  private String versionId;

  private long lastModified;

  /**
   * The checksum that the copy was stored with; {@code null} for none.
   */
  private ObjectChecksum checksum;

  /**
   * The SSE-S3 or SSE-KMS encryption that the copy was stored with; {@code null} if it has none.
   */
  private ServerSideEncryption serverSideEncryption;

}
