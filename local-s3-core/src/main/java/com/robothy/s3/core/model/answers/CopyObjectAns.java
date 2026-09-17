package com.robothy.s3.core.model.answers;

import com.robothy.s3.core.model.internal.ObjectChecksum;
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

}
