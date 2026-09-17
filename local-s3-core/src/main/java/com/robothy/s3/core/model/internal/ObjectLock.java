package com.robothy.s3.core.model.internal;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.robothy.s3.core.model.ObjectLockMode;

/**
 * The Object Lock protection of an object version: a retention, a legal hold, or both. A version without any is
 * stored without an {@code ObjectLock} at all rather than with an empty one.
 *
 * @param mode the retention mode; {@code null} if the version has no retention.
 * @param retainUntilDate the epoch milliseconds the version is retained until; {@code null} if it has no retention.
 * @param legalHold {@code true} if a legal hold is on, {@code false} if it was turned off; {@code null} if it was never
 *     set.
 */
public record ObjectLock(ObjectLockMode mode, Long retainUntilDate, Boolean legalHold) {

  /**
   * The protection of a version that has none.
   */
  public static final ObjectLock NONE = new ObjectLock(null, null, null);

  /**
   * The same protection with another retention.
   *
   * @param mode the retention mode; {@code null} to remove the retention.
   * @param retainUntilDate the epoch milliseconds to retain until; {@code null} to remove the retention.
   * @return the protection.
   */
  public ObjectLock withRetention(ObjectLockMode mode, Long retainUntilDate) {
    return new ObjectLock(mode, retainUntilDate, legalHold);
  }

  /**
   * The same protection with another legal hold.
   *
   * @param legalHold whether the legal hold is on.
   * @return the protection.
   */
  public ObjectLock withLegalHold(boolean legalHold) {
    return new ObjectLock(mode, retainUntilDate, legalHold);
  }

  /**
   * Whether the version has a retention at all, expired or not.
   *
   * @return {@code true} if it has a retention.
   */
  @JsonIgnore
  public boolean hasRetention() {
    return mode != null && retainUntilDate != null;
  }

  /**
   * Whether the retention of the version protects it at a time.
   *
   * @param now the epoch milliseconds to evaluate the retention at.
   * @return {@code true} if the version has a retention that hasn't expired.
   */
  public boolean isRetainedAt(long now) {
    return hasRetention() && retainUntilDate > now;
  }

  /**
   * Whether a legal hold is on.
   *
   * @return {@code true} if a legal hold is on.
   */
  @JsonIgnore
  public boolean isLegalHoldOn() {
    return Boolean.TRUE.equals(legalHold);
  }

  /**
   * Whether the version can't be deleted, or overwritten in place, at a time.
   *
   * @param now the epoch milliseconds to evaluate the protection at.
   * @param bypassGovernanceRetention whether the request bypasses a {@linkplain ObjectLockMode#GOVERNANCE} retention.
   * @return {@code true} if a legal hold is on, or a retention protects the version and isn't bypassed.
   */
  public boolean protects(long now, boolean bypassGovernanceRetention) {
    if (isLegalHoldOn()) {
      return true;
    }
    return isRetainedAt(now) && !(mode == ObjectLockMode.GOVERNANCE && bypassGovernanceRetention);
  }

  /**
   * Whether this protects nothing and records nothing, so that it doesn't need to be stored.
   *
   * @return {@code true} if it has neither a retention nor a legal hold.
   */
  @JsonIgnore
  public boolean isEmpty() {
    return mode == null && retainUntilDate == null && legalHold == null;
  }

}
