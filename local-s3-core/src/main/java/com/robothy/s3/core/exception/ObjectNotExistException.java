package com.robothy.s3.core.exception;

/**
 * Object not exists exception. Client side exception.
 *
 * <p>The message is the one of Amazon S3, {@code The specified key does not exist.}; the key is reported by
 * the {@code <Key>} of the error, where Amazon S3 reports it.
 */
public class ObjectNotExistException extends LocalS3Exception {

  public ObjectNotExistException(String key) {
    super(S3ErrorCode.NoSuchKey);
    setKey(key);
  }

}
