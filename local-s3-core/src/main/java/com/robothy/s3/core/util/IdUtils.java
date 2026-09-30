package com.robothy.s3.core.util;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

public class IdUtils {

  /**
   * The epoch of the generated IDs, 2022-02-26T02:28:33Z, in milliseconds. It used to be expressed in
   * seconds, which left the timestamp of an ID nearly as large as the milliseconds since 1970: shifted
   * by {@linkplain #TIMESTAMP_SHIFT} bits, such an ID overflowed to a negative number in 2039.
   */
  private final static long S3_EPOCH = 1645837713000L;


  private final static long SEQUENCE_ID_BITS = 12;
  private final static long WORKER_ID_BITS = 5;
  private final static long DATACENTER_BITS = 5;


  private final static long MAX_SEQUENCE = ~(-1L << SEQUENCE_ID_BITS);
  private final static long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS);
  private final static long MAX_DATACENTER_ID = ~(-1L << DATACENTER_BITS);


  private final static long SEQUENCE_SHIFT = SEQUENCE_ID_BITS;
  private final static long DATACENTER_ID_SHIFT = SEQUENCE_ID_BITS + WORKER_ID_BITS;
  private final static long TIMESTAMP_SHIFT = DATACENTER_ID_SHIFT + DATACENTER_BITS;

  /**
   * The datacenter and worker bits of the IDs of this generator.
   */
  private final long nodeBits;

  /**
   * The last ID that this generator issued, so that the generated IDs never decrease: its timestamp is a logical clock,
   * which never moves backwards, even when the system clock does. IDs persisted by a LocalS3 that computed the
   * timestamp against an epoch expressed in seconds are far larger than the ones generated now, and objects order their
   * versions by ID; {@linkplain #ensureGreaterThan(long)} keeps the new IDs above the loaded ones.
   *
   * <p>Updated by compare-and-set rather than under a lock: the generator is shared by every LocalS3 service of the
   * JVM, since services that share a data directory must not issue the same ID, and it is called by every write.
   */
  private final AtomicLong lastId = new AtomicLong(-1L);

  private static final IdUtils GENERATOR = new IdUtils(0, 0);

  /**
   * Snow flake ID generator, shared by the LocalS3 services of the JVM.
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
      throw new IllegalArgumentException(String.format("workerId can't be greater than %d or less than 0", MAX_WORKER_ID));
    }
    this.nodeBits = datacenterId << DATACENTER_ID_SHIFT | workerId << SEQUENCE_SHIFT;
  }

  public String nextStrId() {
    return String.valueOf(nextId());
  }

  /**
   * Generate an ID that is greater than every ID this generator issued before.
   *
   * @return the generated ID.
   */
  public long nextId() {
    // A clock correction, e.g. by NTP, must not fail the request that generates an ID; the IDs keep following the
    // last one until the system clock passes it again.
    long fromClock = (getNewTimestamp() - S3_EPOCH) << TIMESTAMP_SHIFT | nodeBits;
    while (true) {
      long last = lastId.get();
      long id = Math.max(fromClock, successor(last));
      if (lastId.compareAndSet(last, id)) {
        return id;
      }
    }
  }

  /**
   * The least ID that follows {@code id}: the next sequence number of its millisecond, or, once the sequence of the
   * millisecond is exhausted, the first one of the next millisecond, borrowed from it rather than waited for.
   */
  private long successor(long id) {
    if ((id & MAX_SEQUENCE) != MAX_SEQUENCE) {
      return id + 1;
    }
    return ((id >> TIMESTAMP_SHIFT) + 1) << TIMESTAMP_SHIFT | nodeBits;
  }

  /**
   * Keep the generated IDs above {@code id}, which the IDs loaded from a data path are seeded with.
   *
   * @param id an ID that the generated ones must follow.
   */
  public void ensureGreaterThan(long id) {
    lastId.accumulateAndGet(id, Math::max);
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
