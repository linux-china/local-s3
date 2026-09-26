package com.robothy.s3.core.service.locks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ServiceLockTest {

  @Test
  void anExclusiveOperationMayRunSharedOperations() {
    ServiceLock lock = new ServiceLock();
    assertEquals("nested", lock.exclusive(() -> lock.exclusive(() -> lock.shared(() -> "nested"))));
    assertThrows(IllegalStateException.class, () -> lock.shared(() -> lock.exclusive(() -> "never")));
    // The lock is released after the failure.
    assertEquals("after", lock.exclusive(() -> "after"));
  }

  /**
   * No shared operation runs alongside an exclusive one, whichever counter the threads use.
   */
  @Test
  void sharedAndExclusiveOperationsExcludeEachOther() throws Exception {
    ServiceLock lock = new ServiceLock(4);
    AtomicInteger shared = new AtomicInteger();
    AtomicInteger violations = new AtomicInteger();
    AtomicInteger exclusiveRuns = new AtomicInteger();
    int threads = 8;
    ExecutorService executor = Executors.newFixedThreadPool(threads);
    try {
      List<Future<?>> futures = new ArrayList<>();
      for (int t = 0; t < threads; t++) {
        boolean exclusiveThread = t == 0;
        futures.add(executor.submit(() -> {
          for (int i = 0; i < 2_000; i++) {
            if (exclusiveThread && i % 10 == 0) {
              lock.exclusive(() -> {
                if (shared.get() != 0) {
                  violations.incrementAndGet();
                }
                exclusiveRuns.incrementAndGet();
                return null;
              });
            } else {
              lock.shared(() -> {
                shared.incrementAndGet();
                Thread.onSpinWait();
                return shared.decrementAndGet();
              });
            }
          }
        }));
      }
      for (Future<?> future : futures) {
        future.get(30, TimeUnit.SECONDS);
      }
    } finally {
      executor.shutdownNow();
    }
    assertEquals(0, violations.get());
    assertEquals(200, exclusiveRuns.get());
  }

}
