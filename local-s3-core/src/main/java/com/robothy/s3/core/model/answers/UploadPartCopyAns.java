package com.robothy.s3.core.model.answers;

import lombok.Builder;
import lombok.Data;

/**
 * Result of
 * <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_UploadPartCopy.html">UploadPartCopy</a>.
 */
@Builder
@Data
public class UploadPartCopyAns {

  /**
   * The ETag of the part, which is the one of the copied bytes rather than the one of the source object.
   */
  private String etag;

  private long lastModified;

  /**
   * The version of the source object that was copied.
   */
  private String sourceVersionId;

  /**
   * The number of bytes copied into the part.
   */
  private long size;

}
