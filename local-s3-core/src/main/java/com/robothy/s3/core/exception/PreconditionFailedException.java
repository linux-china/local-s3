package com.robothy.s3.core.exception;

import lombok.Getter;

/**
 * A precondition of a conditional request didn't hold, e.g. the {@code If-None-Match: *} of a put whose key
 * already holds an object. Amazon S3 answers {@code 412 Precondition Failed} and names the condition that
 * failed in the {@code Condition} element of the error.
 */
@Getter
public class PreconditionFailedException extends LocalS3Exception {

  /**
   * The name of the header whose condition didn't hold, e.g. {@code If-None-Match}.
   */
  private final String condition;

  public PreconditionFailedException(String condition) {
    super(S3ErrorCode.PreconditionFailed);
    this.condition = condition;
  }

}
