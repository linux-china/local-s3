package com.robothy.s3.core.service.manager;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.robothy.s3.core.model.internal.LocalS3Metadata;
import com.robothy.s3.core.model.request.GetObjectOptions;
import com.robothy.s3.core.model.request.PutObjectOptions;
import com.robothy.s3.core.service.ObjectService;
import com.robothy.s3.core.storage.Storage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InitialDataCacheTest {

  private static final String BUCKET = "my-bucket";

  @Test
  void dropsTheLeastRecentlyUsedDataPathsToMakeRoomForACopy() {
    InitialDataCache cache = new InitialDataCache(16, 10);
    Storage baseA = storageWith(4);
    Storage baseB = storageWith(4);
    Storage baseC = storageWith(4);
    InitialDataCache.CacheValue a = cache.computeIfAbsent("/a", key -> value(cache, baseA));
    InitialDataCache.CacheValue b = cache.computeIfAbsent("/b", key -> value(cache, baseB));
    InitialDataCache.CacheValue c = cache.computeIfAbsent("/c", key -> value(cache, baseC));

    read(a, 1L);
    read(b, 1L);
    assertEquals(8, cache.usedBytes());
    // "/a" is used again, so "/b" is the least recently used path.
    cache.computeIfAbsent("/a", key -> value(cache, baseA));

    read(c, 1L);

    assertEquals(8, cache.usedBytes());
    assertEquals(4, a.copies().copiedBytes());
    assertEquals(0, b.copies().copiedBytes(), "The least recently used path is dropped.");
    assertEquals(4, c.copies().copiedBytes());
    assertEquals(2, cache.size());
  }

  @Test
  void readsAnObjectLargerThanTheBudgetFromTheDisk() {
    InitialDataCache cache = new InitialDataCache(16, 10);
    InitialDataCache.CacheValue small = cache.computeIfAbsent("/small", key -> value(cache, storageWith(4)));
    InitialDataCache.CacheValue large = cache.computeIfAbsent("/large", key -> value(cache, storageWith(11)));
    read(small, 1L);

    assertEquals(11, read(large, 1L).length);

    assertEquals(0, large.copies().copiedBytes());
    assertEquals(4, small.copies().copiedBytes(), "Nothing is dropped for an object that never fits.");
  }

  @Test
  void neverDropsTheCopiesOfTheRequester() {
    InitialDataCache cache = new InitialDataCache(16, 10);
    Storage base = Storage.createInMemory();
    base.put(1L, new byte[6]);
    base.put(2L, new byte[6]);
    InitialDataCache.CacheValue value = cache.computeIfAbsent("/a", key -> value(cache, base));

    read(value, 1L);
    read(value, 2L);

    assertEquals(6, value.copies().copiedBytes(), "The second object is read from the base.");
    assertEquals(1, cache.size());
  }

  @Test
  void clearingAndShrinkingReleaseTheBytes() {
    InitialDataCache cache = new InitialDataCache(16, 100);
    InitialDataCache.CacheValue a = cache.computeIfAbsent("/a", key -> value(cache, storageWith(10)));
    InitialDataCache.CacheValue b = cache.computeIfAbsent("/b", key -> value(cache, storageWith(10)));
    read(a, 1L);
    read(b, 1L);

    cache.setLimits(16, 15);
    assertEquals(10, cache.usedBytes());
    assertEquals(0, a.copies().copiedBytes(), "The least recently used path is dropped beyond the new limit.");

    cache.setLimits(1, 15);
    assertEquals(1, cache.size());

    cache.clear();
    assertEquals(0, cache.usedBytes());
    assertEquals(0, cache.size());
  }

  @Test
  void loadsAPathOnceForConcurrentCallers() throws Exception {
    InitialDataCache cache = new InitialDataCache(16, 100);
    AtomicInteger loads = new AtomicInteger();
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(4);
    try {
      List<Future<InitialDataCache.CacheValue>> values = executor.invokeAll(List.of(
          () -> load(cache, loads, release), () -> load(cache, loads, release),
          () -> load(cache, loads, release), () -> {
            release.countDown();
            return load(cache, loads, release);
          }), 10, TimeUnit.SECONDS);
      InitialDataCache.CacheValue first = values.get(0).get();
      for (Future<InitialDataCache.CacheValue> value : values) {
        assertSame(first, value.get());
      }
    } finally {
      executor.shutdownNow();
    }
    assertEquals(1, loads.get());
  }

  @Test
  void aFailedLoadIsNotCached() {
    InitialDataCache cache = new InitialDataCache(16, 100);
    assertThrows(IllegalStateException.class, () -> cache.computeIfAbsent("/a", key -> {
      throw new IllegalStateException("broken");
    }));
    assertEquals(0, cache.size());
    InitialDataCache.CacheValue value = cache.computeIfAbsent("/a", key -> value(cache, storageWith(1)));
    assertSame(value, cache.computeIfAbsent("/a", key -> {
      throw new AssertionError("The path was loaded again.");
    }));
  }

  @Test
  void everyManagerGetsItsOwnCopyOfTheMetadata(@TempDir Path dataPath) throws IOException {
    LocalS3Manager persistent = LocalS3Manager.createFileSystemS3Manager(dataPath);
    persistent.bucketService().createBucket(BUCKET);
    persistent.objectService().putObject(BUCKET, "a.txt", PutObjectOptions.builder()
        .content(new ByteArrayInputStream("Hello".getBytes())).size(5).build());
    InitialDataCache cache = new InitialDataCache(16, 100);

    InMemoryLocalS3Manager first = new InMemoryLocalS3Manager(dataPath, true, cache);
    InMemoryLocalS3Manager second = new InMemoryLocalS3Manager(dataPath, true, cache);
    first.objectService().deleteObject(BUCKET, "a.txt");

    assertNotSame(first.objectService().localS3Metadata(), second.objectService().localS3Metadata());
    try (InputStream in = second.objectService().getObject(BUCKET, "a.txt", GetObjectOptions.builder().build())
        .getContent()) {
      assertArrayEquals("Hello".getBytes(), in.readAllBytes());
    }
    assertEquals(5, cache.usedBytes());
  }

  /**
   * A manager whose data path has objects beyond the budget reads them from the disk.
   */
  @Test
  void aManagerReadsTheObjectsBeyondTheBudgetFromTheDisk(@TempDir Path dataPath) throws IOException {
    LocalS3Manager persistent = LocalS3Manager.createFileSystemS3Manager(dataPath);
    persistent.bucketService().createBucket(BUCKET);
    for (int i = 0; i < 5; i++) {
      persistent.objectService().putObject(BUCKET, "key" + i, PutObjectOptions.builder()
          .content(new ByteArrayInputStream(new byte[1000])).size(1000).build());
    }
    InitialDataCache cache = new InitialDataCache(16, 2500);

    ObjectService objectService = new InMemoryLocalS3Manager(dataPath, true, cache).objectService();
    for (int i = 0; i < 5; i++) {
      String key = "key" + i;
      try (InputStream in = objectService.getObject(BUCKET, key, GetObjectOptions.builder().build()).getContent()) {
        assertEquals(1000, in.readAllBytes().length);
      }
    }
    assertTrue(cache.usedBytes() <= 2500);
    assertEquals(2000, cache.usedBytes());
  }

  @Test
  void parsesSizes() {
    assertEquals(512, InitialDataCache.parseBytes("512"));
    assertEquals(2048, InitialDataCache.parseBytes("2k"));
    assertEquals(512L * 1024 * 1024, InitialDataCache.parseBytes(" 512M "));
    assertEquals(3L * 1024 * 1024 * 1024, InitialDataCache.parseBytes("3g"));
    assertThrows(IllegalArgumentException.class, () -> InitialDataCache.parseBytes("-1"));
    assertThrows(IllegalArgumentException.class, () -> InitialDataCache.parseBytes("lots"));
    assertThrows(IllegalArgumentException.class, () -> new InitialDataCache(0, 1));
    assertThrows(IllegalArgumentException.class, () -> new InitialDataCache(1, -1));
  }

  private static InitialDataCache.CacheValue load(InitialDataCache cache, AtomicInteger loads, CountDownLatch release) {
    return cache.computeIfAbsent("/a", key -> {
      loads.incrementAndGet();
      try {
        release.await(5, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return value(cache, storageWith(1));
    });
  }

  private static Storage storageWith(int size) {
    Storage storage = Storage.createInMemory();
    storage.put(1L, new byte[size]);
    return storage;
  }

  private static InitialDataCache.CacheValue value(InitialDataCache cache, Storage base) {
    return new InitialDataCache.CacheValue(new LocalS3Metadata(), Storage.createCopyOnAccess(base, cache));
  }

  private static byte[] read(InitialDataCache.CacheValue value, Long id) {
    try (InputStream in = value.storage().getInputStream(id)) {
      return in.readAllBytes();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

}
