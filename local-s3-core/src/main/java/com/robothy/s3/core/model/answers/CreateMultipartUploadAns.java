package com.robothy.s3.core.model.answers;

import com.robothy.s3.core.model.internal.ServerSideEncryption;
import lombok.Builder;
import lombok.Getter;

@Builder
@Getter
public class CreateMultipartUploadAns {

  private String uploadId;

  /**
   * The SSE-S3 or SSE-KMS encryption that the object of the upload is stored with, which is the default encryption of
   * the bucket if the request named none; {@code null} if it has none.
   */
  private ServerSideEncryption serverSideEncryption;

}
