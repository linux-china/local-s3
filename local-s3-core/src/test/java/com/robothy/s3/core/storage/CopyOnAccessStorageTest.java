package com.robothy.s3.core.storage;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class CopyOnAccessStorageTest {

  @Test
  void test() throws IOException {
    Storage base = Storage.createInMemory();
    Storage storage = Storage.createCopyOnAccess(base);
    Long id1 = base.put("Hello".getBytes());
    assertArrayEquals("Hello".getBytes(), storage.getInputStream(id1).readAllBytes());
    assertArrayEquals("Hello".getBytes(), storage.getBytes(id1));
    base.delete(id1);
    assertFalse(base.isExist(id1));
    assertTrue(storage.isExist(id1));

    Long id2 = storage.put(new ByteArrayInputStream("World".getBytes()));
    assertFalse(base.isExist(id2));
    assertTrue(storage.isExist(id2));
    assertArrayEquals("World".getBytes(), storage.getBytes(id2));

    Long id3 = base.put("Robothy".getBytes());
    assertTrue(storage.isExist(id3));
    assertArrayEquals("Robothy".getBytes(), storage.getInputStream(id3).readAllBytes());
    base.delete(id3);
    assertFalse(base.isExist(id3));
    assertTrue(storage.isExist(id3));
  }

  @Test
  void readsAnObjectThatTheBudgetHasNoRoomForFromTheBase() throws IOException {
    Storage base = Storage.createInMemory();
    RecordingBudget budget = new RecordingBudget(4);
    CopyOnAccessStorage storage = Storage.createCopyOnAccess(base, budget);
    Long small = base.put("Hi".getBytes());
    Long large = base.put("Hello".getBytes());

    assertArrayEquals("Hello".getBytes(), storage.getInputStream(large).readAllBytes());
    assertArrayEquals("ell".getBytes(), storage.getInputStream(large, 1, 3).readAllBytes());
    assertArrayEquals("Hello".getBytes(), storage.getBytes(large));
    assertEquals(0, storage.copiedBytes(), "No copy is made beyond the budget.");

    assertArrayEquals("Hi".getBytes(), storage.getInputStream(small).readAllBytes());
    assertEquals(2, storage.copiedBytes());
    assertEquals(2, budget.used.get());

    base.delete(large);
    base.delete(small);
    assertThrows(IllegalArgumentException.class, () -> storage.getInputStream(large),
        "An object that isn't copied is read from the base.");
    assertArrayEquals("Hi".getBytes(), storage.getInputStream(small).readAllBytes(), "A copy survives the base.");
  }

  @Test
  void readsRangesOfCopies() throws IOException {
    Storage base = Storage.createInMemory();
    CopyOnAccessStorage storage = Storage.createCopyOnAccess(base, CopyBudget.UNLIMITED);
    Long id = base.put("Hello".getBytes());

    assertArrayEquals("ell".getBytes(), storage.getInputStream(id, 1, 3).readAllBytes());
    assertEquals(5, storage.copiedBytes(), "The object is copied on its first access.");
    base.delete(id);
    assertArrayEquals("llo".getBytes(), storage.getInputStream(id, 2, 3).readAllBytes());
    assertEquals(5, storage.size(id));
  }

  @Test
  void droppingCopiesReleasesTheBudget() throws IOException {
    Storage base = Storage.createInMemory();
    RecordingBudget budget = new RecordingBudget(100);
    CopyOnAccessStorage storage = Storage.createCopyOnAccess(base, budget);
    Long copied = base.put("Hello".getBytes());
    Long put = storage.put(new ByteArrayInputStream("World".getBytes()));
    InputStream opened = storage.getInputStream(copied);
    assertEquals(5, budget.used.get());

    storage.dropCopies();

    assertEquals(0, budget.used.get());
    assertEquals(0, storage.copiedBytes());
    assertArrayEquals("Hello".getBytes(), opened.readAllBytes(), "A stream opened on a copy keeps reading it.");
    assertArrayEquals("World".getBytes(), storage.getBytes(put), "An object that was put is not a copy.");
    assertArrayEquals("Hello".getBytes(), storage.getInputStream(copied).readAllBytes());
    assertEquals(5, budget.used.get(), "The object is copied again.");

    storage.delete(copied);
    assertEquals(0, budget.used.get(), "A deleted copy releases its bytes.");
  }

  @Test
  void copiesAnObjectOnceWhenItIsAccessedConcurrently() throws Exception {
    Storage base = Storage.createInMemory();
    RecordingBudget budget = new RecordingBudget(Long.MAX_VALUE);
    CopyOnAccessStorage storage = Storage.createCopyOnAccess(base, budget);
    Long id = base.put(new byte[1024]);

    ExecutorService executor = Executors.newFixedThreadPool(8);
    try {
      List<Future<byte[]>> reads = new ArrayList<>();
      for (int i = 0; i < 32; i++) {
        reads.add(executor.submit(() -> storage.getInputStream(id).readAllBytes()));
      }
      for (Future<byte[]> read : reads) {
        assertEquals(1024, read.get(10, TimeUnit.SECONDS).length);
      }
    } finally {
      executor.shutdownNow();
    }
    assertEquals(1, budget.reservations.get());
    assertEquals(1024, budget.used.get());
  }

  private static final class RecordingBudget implements CopyBudget {

    private final long max;

    private final AtomicLong used = new AtomicLong();

    private final AtomicInteger reservations = new AtomicInteger();

    RecordingBudget(long max) {
      this.max = max;
    }

    @Override
    public boolean reserve(CopyOnAccessStorage requester, long bytes) {
      if (used.get() + bytes > max) {
        return false;
      }
      reservations.incrementAndGet();
      used.addAndGet(bytes);
      return true;
    }

    @Override
    public void release(long bytes) {
      used.addAndGet(-bytes);
    }
  }

}
