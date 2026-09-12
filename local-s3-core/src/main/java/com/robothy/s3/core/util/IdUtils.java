package com.robothy.s3.core.util;

import java.util.UUID;

public class IdUtils {

  /**
   * The epoch of the generated IDs, 2022-02-26T02:28:33Z, in milliseconds. It used to be expressed in
   * seconds, which left the timestamp of an ID nearly as large as the milliseconds since 1970: shifted
   * by {@linkplain #TIMESTMP_SHIFT} bits, such an ID overflowed to a negative number in 2039.
   */
  private final static long S4_EPOCH = 1645837713000L;


  private final static long SEQUENCE_ID_BITS = 12;
  private final static long WORKER_ID_BITS = 5;
  private final static long DATACENTER_BITS = 5;


  private final static long MAX_SEQUENCE = ~(-1L << SEQUENCE_ID_BITS);
  private final static long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS);
  private final static long MAX_DATACENTER_ID = ~(-1L << DATACENTER_BITS);


  private final static long SEQUENCE_SHIFT = SEQUENCE_ID_BITS;
  private final static long DATACENTER_ID_SHIFT = SEQUENCE_ID_BITS + WORKER_ID_BITS;
  private final static long TIMESTMP_SHIFT = DATACENTER_ID_SHIFT + DATACENTER_BITS;

  private long datacenterId;
  private long machineId;
  private long sequence = 0L;

  /**
   * The timestamp that the last ID was generated with. It is a logical clock: it never moves backwards,
   * even when the system clock does.
   */
  private long lastStmp = -1L;

  /**
   * The last ID that this generator issued, so that the generated IDs never decrease. IDs persisted by
   * a LocalS3 that computed the timestamp against an epoch expressed in seconds are far larger than the
   * ones generated now, and objects order their versions by ID; {@linkplain #ensureGreaterThan(long)}
   * keeps the new IDs above the loaded ones.
   */
  private long lastId = -1L;

  private static final IdUtils GENERATOR = new IdUtils(0, 0);

  /**
   * Snow flake ID generator.
   */
  public static IdUtils defaultGenerator() {
    return GENERATOR;
  }

  /**
   * Generate an UUID.
   * @return an UUID.
   */
  public static String nextUuid() {
    return UUID.randomUUID().toString();
  }

  public IdUtils(long datacenterId, long workerId) {
    if (datacenterId > MAX_DATACENTER_ID || datacenterId < 0) {
      throw new IllegalArgumentException(String.format("datacenterId can't be greater than %d or less than 0", MAX_DATACENTER_ID));
    }
    if (workerId > MAX_WORKER_ID || workerId < 0) {
      throw new IllegalArgumentException(String.format("datacenter Id can't be greater than %d or less than 0", MAX_WORKER_ID));
    }
    this.datacenterId = datacenterId;
    this.machineId = workerId;
  }

  public String nextStrId() {
    return String.valueOf(nextId());
  }

  /**
   * Generate an ID that is greater than every ID this generator issued before.
   *
   * @return the generated ID.
   */
  public synchronized long nextId() {
    // A clock correction, e.g. by NTP, must not fail the request that generates an ID; the IDs keep
    // following the last timestamp until the system clock passes it again.
    long currStmp = Math.max(getNewTimestamp(), lastStmp);

    if (currStmp == lastStmp) {
      sequence = (sequence + 1) & MAX_SEQUENCE;
      if (sequence == 0L) {
        // The sequence of this millisecond is exhausted; borrow from the next one instead of waiting.
        currStmp = lastStmp + 1;
      }
    } else {
      sequence = 0L;
    }

    lastStmp = currStmp;

    long id = (currStmp - S4_EPOCH) << TIMESTMP_SHIFT
        | datacenterId << DATACENTER_ID_SHIFT
        | machineId << SEQUENCE_SHIFT
        | sequence;
    return lastId = Math.max(id, lastId + 1);
  }

  /**
   * Keep the generated IDs above {@code id}, which the IDs loaded from a data path are seeded with.
   *
   * @param id an ID that the generated ones must follow.
   */
  public synchronized void ensureGreaterThan(long id) {
    lastId = Math.max(lastId, id);
  }

  /**
   * The current timestamp in milliseconds. Overridden by tests that set the clock.
   *
   * @return the current timestamp.
   */
  protected long getNewTimestamp() {
    return System.currentTimeMillis();
  }

}
