package com.robothy.s3.core.model.request;

import com.robothy.s3.core.model.internal.SystemMetadata;
import com.robothy.s3.datatypes.enums.CheckSumAlgorithm;
import com.robothy.s3.datatypes.enums.ChecksumType;
import java.util.Map;
import java.util.Optional;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class CreateMultipartUploadOptions {

  private String contentType;

  /**
   * The system-defined metadata of the object besides its content type; {@code null} if it has none.
   */
  private SystemMetadata systemMetadata;

  private String[][] tagging;

  public Optional<String[][]> getTagging() {
    return Optional.ofNullable(tagging);
  }

  private Map<String, String> userMetadata;

  /**
   * The algorithm of the checksum of the object that the upload stores; {@code null} for none.
   */
  private CheckSumAlgorithm checksumAlgorithm;

  /**
   * The type of the checksum of the object that the upload stores; {@code null} for the default of the algorithm.
   */
  private ChecksumType checksumType;
}
