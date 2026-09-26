package com.robothy.s3.core.service.locks;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * The lock of a whole LocalS3 service: held shared by every operation of a bucket, and exclusively by an operation
 * that concerns every bucket at once, e.g. a reset of the service.
 *
 * <p>Exclusive operations are rare, so the shared side is made cheap: a thread counts itself in one of several
 * counters, padded apart from each other, rather than in the single state of a read-write lock that every request
 * would update. An exclusive operation raises a flag, then waits for the counters to drain; a shared operation that
 * sees the flag steps back and waits for the exclusive one to finish.
 *
 * <p>Both sides are reentrant: an operation of a bucket nested in another one doesn't wait for an exclusive operation
 * that waits for the outer one, and an exclusive operation may run operations of buckets.
 */
public final class ServiceLock {

  /**
   * The distance between two counters, in longs, so that each counter sits on its own cache lines.
   */
  private static final int STRIDE = 16;

  private static final long MAX_PARK_NANOS = TimeUnit.MILLISECONDS.toNanos(1);

  private final int stripes;

  private final AtomicLongArray sharedHolders;

  /**
   * Serializes the exclusive operations, and parks the shared operations that wait for one.
   */
  private final ReentrantLock exclusiveLock = new ReentrantLock();

  /**
   * Whether an exclusive operation runs or waits for the shared operations; only set while holding
   * {@linkplain #exclusiveLock}.
   */
  private volatile boolean exclusive;

  private final ThreadLocal<Holder> holders = ThreadLocal.withInitial(this::newHolder);

  public ServiceLock() {
    this(Runtime.getRuntime().availableProcessors() * 2);
  }

  ServiceLock(int parallelism) {
    this.stripes = Math.min(64, Integer.highestOneBit(Math.max(1, parallelism - 1)) << 1);
    this.sharedHolders = new AtomicLongArray(stripes * STRIDE);
  }

  /**
   * Run an operation of a bucket, once no exclusive operation runs.
   */
  public <T> T shared(Supplier<T> operation) {
    Holder holder = holders.get();
    if (holder.depth == 0) {
      acquireShared(holder);
    }
    holder.depth++;
    try {
      return operation.get();
    } finally {
      if (--holder.depth == 0 && holder.counted) {
        holder.counted = false;
        sharedHolders.getAndDecrement(holder.slot);
      }
    }
  }

  /**
   * Run an operation that concerns every bucket, once no operation of a bucket runs, and while none starts.
   *
   * @throws IllegalStateException if the current thread runs an operation of a bucket, which would wait for itself.
   */
  public <T> T exclusive(Supplier<T> operation) {
    if (holders.get().depth > 0) {
      throw new IllegalStateException("An exclusive operation can't run within an operation of a bucket.");
    }
    if (exclusiveLock.isHeldByCurrentThread()) {
      return operation.get();
    }
    exclusiveLock.lock();
    try {
      exclusive = true;
      try {
        awaitNoSharedHolders();
        return operation.get();
      } finally {
        exclusive = false;
      }
    } finally {
      exclusiveLock.unlock();
    }
  }

  private void acquireShared(Holder holder) {
    if (exclusiveLock.isHeldByCurrentThread()) {
      // Within an exclusive operation, which no operation of a bucket runs alongside.
      return;
    }
    for (; ; ) {
      // The counter is incremented before the flag is read, and the flag is raised before the counters are read, so
      // that either this thread sees the flag, or the exclusive operation sees this thread.
      sharedHolders.getAndIncrement(holder.slot);
      if (!exclusive) {
        holder.counted = true;
        return;
      }
      sharedHolders.getAndDecrement(holder.slot);
      // Wait for the exclusive operation, which holds the lock while the flag is raised.
      exclusiveLock.lock();
      exclusiveLock.unlock();
    }
  }

  private void awaitNoSharedHolders() {
    boolean interrupted = false;
    for (int stripe = 0; stripe < stripes; stripe++) {
      int slot = stripe * STRIDE;
      long parkNanos = 0;
      while (sharedHolders.get(slot) != 0) {
        if (parkNanos == 0) {
          Thread.onSpinWait();
          parkNanos = 1_000;
          continue;
        }
        LockSupport.parkNanos(this, parkNanos);
        parkNanos = Math.min(parkNanos * 2, MAX_PARK_NANOS);
        // An interrupt would end every park at once; keep it for the caller.
        interrupted |= Thread.interrupted();
      }
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  private Holder newHolder() {
    // Spread the threads over the counters.
    long id = Thread.currentThread().threadId();
    int hash = (int) (id ^ (id >>> 32)) * 0x9E3779B9;
    return new Holder((hash >>> 16 & (stripes - 1)) * STRIDE);
  }

  /**
   * The shared operations that the current thread runs.
   */
  private static final class Holder {

    final int slot;

    int depth;

    /**
     * Whether the outermost shared operation counted itself, which it doesn't within an exclusive operation.
     */
    boolean counted;

    Holder(int slot) {
      this.slot = slot;
    }
  }

}
