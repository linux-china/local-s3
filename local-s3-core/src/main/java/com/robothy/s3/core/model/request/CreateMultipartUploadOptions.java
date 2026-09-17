package com.robothy.s3.core.model.request;

import com.robothy.s3.core.model.internal.CustomerEncryption;
import com.robothy.s3.core.model.internal.ServerSideEncryption;
import com.robothy.s3.core.model.internal.ObjectLock;
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

  /**
   * The Object Lock settings to store the object with; {@code null} for none, which stores it with the default
   * retention of the bucket, if any.
   */
  private ObjectLock objectLock;

  /**
   * The customer-provided key to store the object with; {@code null} for none.
   */
  private CustomerEncryption customerEncryption;

  /**
   * The SSE-S3 or SSE-KMS encryption to store the object of the upload with; {@code null} for none.
   */
  private ServerSideEncryption serverSideEncryption;

}
