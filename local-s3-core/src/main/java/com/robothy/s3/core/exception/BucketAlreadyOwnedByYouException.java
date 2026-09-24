package com.robothy.s3.core.exception;

/**
 * The bucket to create already exists, and is owned by the requester, which every bucket of LocalS3 is: LocalS3 has a
 * single owner. A {@linkplain BucketAlreadyExistsException}, so that the callers that catch that one keep working.
 */
public class BucketAlreadyOwnedByYouException extends BucketAlreadyExistsException {

  public BucketAlreadyOwnedByYouException(String bucketName) {
    super(S3ErrorCode.BucketAlreadyOwnedByYou, "The bucket '" + bucketName + "' already exists, and you own it.");
    setBucketName(bucketName);
  }

}
