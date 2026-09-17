package com.robothy.s3.core.model.request;

import com.robothy.s3.datatypes.enums.CheckSumAlgorithm;
import java.util.Map;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class CompleteMultipartUploadPartOption {

  private String etag;

  private int partNumber;

  /**
   * The checksums of the part that the request names, by algorithm; {@code null} or empty if it names none.
   */
  private Map<CheckSumAlgorithm, String> checksums;

}
