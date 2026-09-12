package com.robothy.s3.core.util;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class IdUtilsTest {

  @Test
  void generatesIncreasingPositiveIds() {
    IdUtils generator = new IdUtils(0, 0);
    long previous = generator.nextId();
    assertTrue(previous > 0, "The first ID should be positive, but was " + previous);

    for (int i = 0; i < 100_000; i++) {
      long id = generator.nextId();
      assertTrue(id > previous, "ID " + id + " doesn't follow " + previous);
      previous = id;
    }
  }

  /**
   * The epoch used to be expressed in seconds, which made the timestamp of an ID nearly as large as the
   * milliseconds since 1970; shifted by 22 bits, such an ID overflowed to a negative number in 2039.
   */
  @Test
  void generatesPositiveIdsAfter2039() {
    for (String date : new String[] {"2039-09-26T00:00:00Z", "2040-01-01T00:00:00Z", "2090-01-01T00:00:00Z"}) {
      FixedClockIdUtils generator = new FixedClockIdUtils(Instant.parse(date).toEpochMilli());
      long id = generator.nextId();
      assertTrue(id > 0, "The ID generated at " + date + " overflowed to " + id);
    }
  }

  /**
   * A backwards clock, e.g. an NTP correction, must not fail the request that generates an ID.
   */
  @Test
  void keepsGeneratingIdsWhenTheClockMovesBackwards() {
    long now = Instant.parse("2026-09-12T00:00:00Z").toEpochMilli();
    FixedClockIdUtils generator = new FixedClockIdUtils(now);
    long before = generator.nextId();

    generator.setNow(now - 60_000);
    long after = assertDoesNotThrow(generator::nextId);
    assertTrue(after > before, "ID " + after + " generated after the clock moved back doesn't follow " + before);
  }

  /**
   * IDs persisted by a LocalS3 that computed the timestamp against an epoch in seconds are far larger
   * than the ones generated now, and the versions of an object are ordered by ID.
   */
  @Test
  void generatesIdsAboveTheOnesAlreadyIssued() {
    IdUtils generator = new IdUtils(0, 0);
    long persisted = 7496428310230990849L;
    generator.ensureGreaterThan(persisted);

    long id = generator.nextId();
    assertTrue(id > persisted, "ID " + id + " doesn't follow the persisted " + persisted);
    assertTrue(generator.nextId() > id);
  }

  /**
   * An {@linkplain IdUtils} whose clock is set by the test.
   */
  private static class FixedClockIdUtils extends IdUtils {

    private long now;

    FixedClockIdUtils(long now) {
      super(0, 0);
      this.now = now;
    }

    void setNow(long now) {
      this.now = now;
    }

    @Override
    protected long getNewTimestamp() {
      return now;
    }
  }

}
