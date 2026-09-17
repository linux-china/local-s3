package com.robothy.s3.core.model;

/**
 * The <a href="https://docs.aws.amazon.com/AmazonS3/latest/userguide/object-lock.html#object-lock-retention-modes">retention
 * mode</a> of an object version that is protected by Object Lock.
 */
public enum ObjectLockMode {

  /**
   * The retention can be shortened or removed, and the version deleted, by a request that sends
   * {@code x-amz-bypass-governance-retention: true}.
   */
  GOVERNANCE,

  /**
   * Nobody can shorten or remove the retention, or delete the version, until the retention expires.
   */
  COMPLIANCE;

  /**
   * Parse a mode as a request names it.
   *
   * @param value the mode, e.g. {@code GOVERNANCE}.
   * @return the mode; {@code null} if the value is not a mode.
   */
  public static ObjectLockMode parse(String value) {
    if (value == null) {
      return null;
    }
    for (ObjectLockMode mode : values()) {
      if (mode.name().equals(value)) {
        return mode;
      }
    }
    return null;
  }

}
