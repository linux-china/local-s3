package com.robothy.s3.rest;

import com.robothy.s3.core.model.answers.LifecycleActionAns;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies the lifecycle configurations of a service on a schedule; see
 * {@code docs/semantics.md#lifecycle-configuration}.
 */
final class LifecycleSchedule {

  private static final Logger log = LoggerFactory.getLogger(LifecycleSchedule.class);

  /**
   * How long stopping waits for a run in progress. A run is never interrupted: an interrupt closes the file channel of
   * the store that a run writes to, which would fail every later request of the service.
   */
  static final Duration STOP_TIMEOUT = Duration.ofMinutes(1);

  private final ScheduledExecutorService executor;

  private LifecycleSchedule(ScheduledExecutorService executor) {
    this.executor = executor;
  }

  /**
   * Apply the configurations now, and then every {@code interval} after a run ends.
   *
   * @param interval the time between two runs, positive.
   * @param apply applies the configurations at the current time, and answers the actions taken.
   * @return the schedule, to stop with the service.
   */
  static LifecycleSchedule start(Duration interval, Supplier<List<LifecycleActionAns>> apply) {
    // A daemon thread, like the other threads of a service, so that a service that isn't closed doesn't keep the JVM
    // alive.
    ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
        Thread.ofPlatform().name("locals3-lifecycle").daemon(true).factory());
    executor.scheduleWithFixedDelay(() -> run(apply), 0, saturatedNanos(interval), TimeUnit.NANOSECONDS);
    log.info("LocalS3 applies the lifecycle configurations of the buckets every {}.", interval);
    return new LifecycleSchedule(executor);
  }

  private static void run(Supplier<List<LifecycleActionAns>> apply) {
    try {
      List<LifecycleActionAns> actions = apply.get();
      if (!actions.isEmpty()) {
        log.info("Applied the lifecycle configurations of the buckets; actions taken: {}.", actions.size());
      }
    } catch (RuntimeException e) {
      // An exception would end the schedule; the next run may well succeed, e.g. after a reset.
      log.warn("Failed to apply the lifecycle configurations of the buckets; trying again at the next run.", e);
    }
  }

  private static long saturatedNanos(Duration interval) {
    try {
      return interval.toNanos();
    } catch (ArithmeticException e) {
      return Long.MAX_VALUE;
    }
  }

  /**
   * Cancel the later runs, and wait up to {@linkplain #STOP_TIMEOUT} for a run in progress, so that the store isn't
   * closed under it.
   */
  void stop() {
    executor.shutdown();
    try {
      if (!executor.awaitTermination(STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
        log.warn("A run of the lifecycle configurations didn't end within {} s of stopping LocalS3.",
            STOP_TIMEOUT.toSeconds());
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

}
