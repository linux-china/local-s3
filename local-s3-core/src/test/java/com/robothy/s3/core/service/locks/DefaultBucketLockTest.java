package com.robothy.s3.core.service.locks;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import org.junit.jupiter.api.Test;

class DefaultBucketLockTest {

  @Test
  void locksBucketsWithinOneService() throws Exception {
    BucketLock bucketLock = BucketLock.create();
    Lock writeLock = bucketLock.writeLock("my-bucket");
    writeLock.lock();
    try {
      assertFalse(tryLockInAnotherThread(bucketLock.writeLock("my-bucket")));
      assertFalse(tryLockInAnotherThread(bucketLock.readLock("my-bucket")));
    } finally {
      writeLock.unlock();
    }

    Lock readLock = bucketLock.readLock("my-bucket");
    readLock.lock();
    try {
      assertTrue(tryLockInAnotherThread(bucketLock.readLock("my-bucket")), "Readers share a bucket.");
      assertFalse(tryLockInAnotherThread(bucketLock.writeLock("my-bucket")));
    } finally {
      readLock.unlock();
    }
  }

  @Test
  void servicesDontShareLocks() throws Exception {
    BucketLock first = BucketLock.create();
    BucketLock second = BucketLock.create();
    Lock writeLock = first.writeLock("my-bucket");
    writeLock.lock();
    try {
      assertTrue(tryLockInAnotherThread(second.writeLock("my-bucket")));
    } finally {
      writeLock.unlock();
    }
  }

  @Test
  void takesBoundedMemory() {
    BucketLock bucketLock = BucketLock.create();
    assertSame(bucketLock.readLock("my-bucket"), bucketLock.readLock("my-bucket"));

    Set<Lock> locks = Collections.newSetFromMap(new IdentityHashMap<>());
    for (int i = 0; i < 10_000; i++) {
      locks.add(bucketLock.writeLock("bucket-" + i));
    }
    assertTrue(locks.size() <= DefaultBucketLock.DEFAULT_STRIPES, locks.size() + " locks");

    assertDoesNotThrow(() -> bucketLock.readLock(null));
  }

  private static boolean tryLockInAnotherThread(Lock lock) throws Exception {
    return CompletableFuture.supplyAsync(() -> {
      boolean acquired = lock.tryLock();
      if (acquired) {
        lock.unlock();
      }
      return acquired;
    }).get(5, TimeUnit.SECONDS);
  }

}
