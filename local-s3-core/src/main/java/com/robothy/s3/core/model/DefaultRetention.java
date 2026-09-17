package com.robothy.s3.core.model;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * The default retention of the object lock configuration of a bucket, which every new object version that is stored
 * without a retention of its own gets.
 *
 * @param mode the retention mode.
 * @param days the number of days that a new version is retained; {@code null} if {@code years} is given.
 * @param years the number of years that a new version is retained; {@code null} if {@code days} is given.
 */
public record DefaultRetention(ObjectLockMode mode, Integer days, Integer years) {

  /**
   * Validate the components.
   *
   * @throws NullPointerException if the mode is {@code null}.
   * @throws IllegalArgumentException if not exactly one of the days and the years is given.
   */
  public DefaultRetention {
    Objects.requireNonNull(mode, "mode");
    if ((days == null) == (years == null)) {
      throw new IllegalArgumentException("Exactly one of days and years must be given.");
    }
  }

  /**
   * When a version that is stored at a time is retained until.
   *
   * @param creationDate the epoch milliseconds the version is stored at.
   * @return the epoch milliseconds the version is retained until.
   */
  public long retainUntil(long creationDate) {
    var created = Instant.ofEpochMilli(creationDate).atOffset(ZoneOffset.UTC);
    return (days != null ? created.plusDays(days) : created.plusYears(years)).toInstant().toEpochMilli();
  }

}
