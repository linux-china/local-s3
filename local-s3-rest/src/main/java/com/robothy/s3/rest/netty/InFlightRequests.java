package com.robothy.s3.rest.netty;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Counts the requests of all connections that are being handled, from the moment a request is handed to the executor
 * until its response is written, so that a server that shuts down can wait for the responses in flight before it
 * closes the connections.
 */
public final class InFlightRequests {

  /**
   * A tracker that counts nothing, for handlers that aren't part of a server.
   */
  static final InFlightRequests NONE = new InFlightRequests();

  private final ReentrantLock lock = new ReentrantLock();

  private final Condition idle = lock.newCondition();

  private int count;

  void begin() {
    lock.lock();
    try {
      count++;
    } finally {
      lock.unlock();
    }
  }

  void end() {
    lock.lock();
    try {
      if (count > 0 && --count == 0) {
        idle.signalAll();
      }
    } finally {
      lock.unlock();
    }
  }

  /**
   * The number of requests in flight.
   *
   * @return the number of requests whose responses aren't written yet.
   */
  public int count() {
    lock.lock();
    try {
      return count;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Wait until no request is in flight.
   *
   * @param timeout the max time to wait.
   * @param unit the unit of {@code timeout}.
   * @return {@code true} if no request is in flight; {@code false} if the time elapsed first.
   * @throws InterruptedException if the current thread is interrupted while it waits.
   */
  public boolean awaitIdle(long timeout, TimeUnit unit) throws InterruptedException {
    long remaining = unit.toNanos(timeout);
    lock.lock();
    try {
      while (count > 0) {
        if (remaining <= 0) {
          return false;
        }
        remaining = idle.awaitNanos(remaining);
      }
      return true;
    } finally {
      lock.unlock();
    }
  }

}
