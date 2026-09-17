package com.robothy.s3.core.model.answers;

import com.robothy.s3.core.model.internal.ObjectChecksum;
import com.robothy.s3.core.model.internal.ServerSideEncryption;
import lombok.Builder;
import lombok.Data;

@Builder
@Data
public class UploadPartAns {

  private String etag;

  /**
   * When the part was added to the upload.
   */
  private long lastModified;

  /**
   * The checksum that the part was uploaded with; {@code null} for none.
   */
  private ObjectChecksum checksum;

  /**
   * The SSE-S3 or SSE-KMS encryption of the object; {@code null} if it has none.
   */
  private ServerSideEncryption serverSideEncryption;

}
