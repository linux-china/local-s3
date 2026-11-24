package com.robothy.s3.core.model.answers;

import com.robothy.s3.core.model.ObjectExpiration;
import com.robothy.s3.core.model.internal.ObjectChecksum;
import com.robothy.s3.core.model.internal.ServerSideEncryption;
import lombok.Builder;
import lombok.Getter;

@Builder
@Getter
public class CompleteMultipartUploadAns {

  private String location;

  private String versionId;

  private String etag;

  private long size;

  /**
   * The checksum that the object was stored with; {@code null} for none.
   */
  private ObjectChecksum checksum;

  /**
   * The SSE-S3 or SSE-KMS encryption of the object; {@code null} if it has none.
   */
  private ServerSideEncryption serverSideEncryption;

  /**
   * When the version expires by a lifecycle rule; {@code null} if no rule expires it.
   */
  private ObjectExpiration expiration;

}
