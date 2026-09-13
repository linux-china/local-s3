package com.robothy.s3.core.storage.s3vectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TransactionalVectorStorageTest {

  private final VectorStorage delegate = VectorStorage.createInMemory();

  private final TransactionalVectorStorage storage = new TransactionalVectorStorage(delegate);

  @Test
  void deletesImmediatelyOutsideTransaction() {
    Long id = storage.putVectorData(new float[] {1.0f});
    assertTrue(storage.deleteVectorData(id));
    assertFalse(delegate.vectorDataExists(id));
    assertFalse(storage.deleteVectorData(id));
  }

  @Test
  void commitDeletesTheDeletedVectors() {
    Long existing = storage.putVectorData(new float[] {1.0f});

    assertTrue(storage.begin());
    Long written = storage.putVectorData(new float[] {2.0f});
    assertTrue(storage.deleteVectorData(existing));
    assertTrue(storage.vectorDataExists(existing), "Deletion is deferred until commit.");
    assertFalse(storage.deleteVectorData(666L), "A vector that doesn't exist isn't deleted.");
    storage.commit();

    assertFalse(delegate.vectorDataExists(existing));
    assertArrayEquals(new float[] {2.0f}, delegate.getVectorData(written));
  }

  @Test
  void rollbackKeepsTheDeletedAndTheWrittenVectors() {
    Long existing = storage.putVectorData(new float[] {1.0f});

    assertTrue(storage.begin());
    Long written = storage.putVectorData(new float[] {2.0f});
    storage.deleteVectorData(existing);
    storage.rollback();

    assertTrue(delegate.vectorDataExists(existing));
    assertTrue(delegate.vectorDataExists(written),
        "The in-memory metadata of a vector bucket isn't reloaded, so it may still reference the written vector.");
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
