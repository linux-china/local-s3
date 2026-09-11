package com.robothy.s3.core.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class TransactionalStorageTest {

  private final Storage delegate = Storage.createInMemory();

  private final TransactionalStorage storage = new TransactionalStorage(delegate);

  @Test
  void deletesImmediatelyOutsideTransaction() {
    Long id = storage.put("Hello".getBytes());
    storage.delete(id);
    assertFalse(delegate.isExist(id));
    assertThrows(IllegalArgumentException.class, () -> storage.delete(id));
  }

  @Test
  void commitDeletesTheDeletedObjects() {
    Long existing = storage.put("Hello".getBytes());

    assertTrue(storage.begin());
    Long written = storage.put("Hi".getBytes());
    storage.delete(existing);
    assertTrue(storage.isExist(existing), "Deletion is deferred until commit.");
    assertThrows(IllegalArgumentException.class, () -> storage.delete(666L));
    storage.commit();

    assertFalse(delegate.isExist(existing));
    assertArrayEquals("Hi".getBytes(), delegate.getBytes(written));
  }

  @Test
  void rollbackKeepsTheDeletedObjectsAndDeletesTheWrittenOnes() {
    Long existing = storage.put("Hello".getBytes());

    assertTrue(storage.begin());
    Long written = storage.put("Hi".getBytes());
    storage.put(existing, "Hello again".getBytes());
    storage.delete(existing);
    storage.rollback();

    assertFalse(delegate.isExist(written));
    assertArrayEquals("Hello again".getBytes(), delegate.getBytes(existing),
        "Overwritten objects existed before the transaction and are kept.");
  }

  @Test
  void onlyTheOutermostCallerOwnsTheTransaction() {
    assertTrue(storage.begin());
    assertFalse(storage.begin());
    storage.commit();
    assertThrows(IllegalStateException.class, storage::commit);
    assertThrows(IllegalStateException.class, storage::rollback);
  }

}
