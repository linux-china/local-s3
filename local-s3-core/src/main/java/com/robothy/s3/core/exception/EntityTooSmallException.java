package com.robothy.s3.core.exception;

/**
 * A part of a multipart upload that isn't the last one is smaller than the minimum part size. Amazon S3
 * reports this when the upload is completed, because only then is it known which part is the last one.
 */
public class EntityTooSmallException extends LocalS3Exception {

  public EntityTooSmallException(int partNumber, long size, long minimumPartSize) {
    super(S3ErrorCode.EntityTooSmall, "Your proposed upload is smaller than the minimum allowed size. Part "
        + partNumber + " is " + size + " bytes, and every part but the last one must be at least "
        + minimumPartSize + " bytes.");
  }

}
