package com.robothy.s3.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.core.exception.BucketNotExistException;
import com.robothy.s3.core.service.locks.BucketLock;
import com.robothy.s3.core.storage.MetadataStore;
import com.robothy.s3.core.storage.Storage;
import com.robothy.s3.core.storage.TransactionalStorage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

class DefaultBucketGuardTest {

  private final RecordingStore store = new RecordingStore();

  private final TransactionalStorage storage = new TransactionalStorage(Storage.createInMemory());

  private final List<String> reloaded = new ArrayList<>();

  private final DefaultBucketGuard<String> guard = new DefaultBucketGuard<>(BucketLock.create(),
      bucketName -> "metadata of " + bucketName, store, storage, reloaded::add);

  /**
   * An exclusive operation waits for the operations of the buckets in progress, and the operations that start
   * meanwhile wait for it.
   */
  @Test
  void anExclusiveOperationWaitsForTheOperationsOfEveryBucket() throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(3);
    try {
      CountDownLatch reading = new CountDownLatch(1);
      CountDownLatch finishReading = new CountDownLatch(1);
      List<String> events = Collections.synchronizedList(new ArrayList<>());
      Future<?> read = executor.submit(() -> guard.read("a", () -> {
        reading.countDown();
        await(finishReading);
        events.add("read a");
        return null;
      }));
      assertTrue(reading.await(5, TimeUnit.SECONDS));

      CountDownLatch exclusiveRunning = new CountDownLatch(1);
      Future<?> exclusive = executor.submit(() -> guard.exclusive(() -> {
        exclusiveRunning.countDown();
        events.add("exclusive");
        return null;
      }));
      assertFalse(exclusiveRunning.await(200, TimeUnit.MILLISECONDS), "The exclusive operation waits for the read.");
      // A nested operation of the thread that is running doesn't wait for the waiting exclusive operation.
      assertEquals("nested", guard.read("b", () -> guard.write("c", () -> "nested")));

      finishReading.countDown();
      read.get(5, TimeUnit.SECONDS);
      exclusive.get(5, TimeUnit.SECONDS);
      assertEquals(List.of("read a", "exclusive"), events);
      assertEquals("after", executor.submit(() -> guard.write("b", () -> "after")).get(5, TimeUnit.SECONDS));
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void anExclusiveOperationCantRunWithinAnOperationOfABucket() {
    assertThrows(IllegalStateException.class,
        () -> guard.read("bucket", () -> guard.exclusive(() -> "never")));
    assertEquals("exclusive", guard.exclusive(() -> "exclusive"));
  }


  @Test
  void persistsAChangedBucketOnce() {
    String result = guard.change("bucket", BucketGuard.Change.UPDATE,
        () -> guard.change("bucket", BucketGuard.Change.UPDATE, () -> "done"));

    assertEquals("done", result);
    assertEquals(List.of("store bucket"), store.operations, "A nested change of the same bucket is persisted once.");

    guard.change("bucket", BucketGuard.Change.DELETE, () -> null);
    assertEquals(List.of("store bucket", "delete bucket"), store.operations);
  }

  @Test
  void deletesObjectsOnlyOnceTheBucketIsPersisted() {
    Long id = storage.put("Hello".getBytes());
    guard.change("bucket", BucketGuard.Change.UPDATE, () -> {
      storage.delete(id);
      assertTrue(storage.isExist(id), "The deletion waits for the bucket to be persisted.");
      return null;
    });
    assertFalse(storage.isExist(id));
  }

  @Test
  void rollsBackAFailedChange() {
    Long kept = storage.put("Kept".getBytes());
    List<Long> written = new ArrayList<>();
    IllegalStateException thrown = assertThrows(IllegalStateException.class,
        () -> guard.change("bucket", BucketGuard.Change.UPDATE, () -> {
          written.add(storage.put("Written".getBytes()));
          storage.delete(kept);
          throw new IllegalStateException("broken");
        }));

    assertEquals("broken", thrown.getMessage());
    assertFalse(storage.isExist(written.get(0)), "The objects written by the failed change are deleted.");
    assertTrue(storage.isExist(kept), "The objects deleted by the failed change are kept.");
    assertEquals(List.of("bucket"), reloaded, "The in-memory metadata is restored from the store.");
    assertEquals(List.of(), store.operations);
  }

  /**
   * A rejected request is rejected before the service changes the metadata, so the metadata isn't reloaded.
   */
  @Test
  void keepsTheMetadataOfARejectedChange() {
    assertThrows(BucketNotExistException.class, () -> guard.change("bucket", BucketGuard.Change.UPDATE, () -> {
      throw new BucketNotExistException("bucket");
    }));
    assertEquals(List.of(), reloaded);
  }

  @Test
  void rollsBackAChangeWhosePersistenceFails() {
    store.failing = true;
    List<Long> written = new ArrayList<>();
    assertThrows(IllegalStateException.class, () -> guard.change("bucket", BucketGuard.Change.UPDATE,
        () -> written.add(storage.put("Written".getBytes()))));

    assertFalse(storage.isExist(written.get(0)));
    assertEquals(List.of("bucket"), reloaded);

    // The failed change doesn't count as an outer change of the next one, which persists the bucket again.
    store.failing = false;
    guard.change("bucket", BucketGuard.Change.UPDATE, () -> null);
    assertEquals(List.of("store bucket"), store.operations);
  }

  @Test
  void aWriterWaitsForTheReaders() throws Exception {
    BucketGuard lockOnly = BucketGuard.inMemory();
    CountDownLatch reading = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<String> reader = executor.submit(() -> lockOnly.read("bucket", () -> {
        reading.countDown();
        await(release);
        return "read";
      }));
      assertTrue(reading.await(5, TimeUnit.SECONDS));
      assertEquals("nested read", lockOnly.read("bucket", () -> "nested read"), "Reads don't exclude each other.");

      Future<String> writer = executor.submit(() -> lockOnly.write("bucket", () -> "written"));
      assertThrows(TimeoutException.class, () -> writer.get(200, TimeUnit.MILLISECONDS));
      assertEquals("other bucket", lockOnly.write("another-bucket", () -> "other bucket"));

      release.countDown();
      assertEquals("read", reader.get(5, TimeUnit.SECONDS));
      assertEquals("written", writer.get(5, TimeUnit.SECONDS));
    } finally {
      release.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void aGuardWithoutAStoreOnlyLocks() {
    BucketGuard lockOnly = BucketGuard.inMemory();
    Object result = new Object();
    assertSame(result, lockOnly.change("bucket", BucketGuard.Change.CREATE, () -> result));
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static final class RecordingStore implements MetadataStore<String> {

    private final List<String> operations = new ArrayList<>();

    private final Map<String, String> stored = new ConcurrentHashMap<>();

    private boolean failing;

    @Override
    public String fetch(String bucketName) {
      return stored.get(bucketName);
    }

    @Override
    public boolean exists(String bucketName) {
      return stored.containsKey(bucketName);
    }

    @Override
    public String store(String bucketName, String metadata) {
      if (failing) {
        throw new IllegalStateException("The disk is full.");
      }
      operations.add("store " + bucketName);
      stored.put(bucketName, metadata);
      return bucketName;
    }

    @Override
    public void delete(String bucketName) {
      operations.add("delete " + bucketName);
      stored.remove(bucketName);
    }

    @Override
    public List<String> fetchAll() {
      return List.copyOf(stored.values());
    }
  }

}
