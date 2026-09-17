package com.robothy.s3.core.model.answers;

import com.robothy.s3.core.model.internal.ObjectChecksum;
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

}
