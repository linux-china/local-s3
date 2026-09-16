package com.robothy.s3.core.storage;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalS3StoreTest {

  @Test
  void theHoldersOfADataDirectoryShareOneStore(@TempDir Path dataPath) {
    try (LocalS3Store first = LocalS3Store.persistent(dataPath);
         LocalS3Store second = LocalS3Store.persistent(dataPath)) {
      assertSame(first, second);
      // The store stays open while the other holder has it.
      second.close();
      assertFalse(first.store().isClosed());
    }
  }

  @Test
  void aReadOnlyHolderSharesTheWritableStoreOfADataDirectory(@TempDir Path dataPath) {
    try (LocalS3Store writable = LocalS3Store.persistent(dataPath);
         LocalS3Store readOnly = LocalS3Store.readOnly(dataPath)) {
      assertSame(writable, readOnly);
      assertFalse(readOnly.isReadOnly());
    }
  }

  @Test
  void openingADataDirectoryForWritingWhileItIsOpenReadOnlyIsRejected(@TempDir Path dataPath) {
    // A store to read: readOnly() answers an in-memory one for a directory that holds no store yet.
    LocalS3Store.persistent(dataPath).close();

    try (LocalS3Store readOnly = LocalS3Store.readOnly(dataPath)) {
      assertTrue(readOnly.isReadOnly());

      IllegalStateException e = assertThrows(IllegalStateException.class,
          () -> LocalS3Store.persistent(dataPath));
      assertTrue(e.getMessage().contains(LocalS3Store.FILE_NAME), e.getMessage());

      // The rejected call took no hold of the store, so the read-only holder still closes it.
      assertFalse(readOnly.store().isClosed());
    }

    // Once the read-only holder has closed it, the directory opens for writing again.
    try (LocalS3Store writable = LocalS3Store.persistent(dataPath)) {
      assertFalse(writable.isReadOnly());
    }
  }

  @Test
  void aDataDirectoryWithoutAStoreIsReadAsAnEmptyInMemoryStore(@TempDir Path dataPath) {
    try (LocalS3Store readOnly = LocalS3Store.readOnly(dataPath);
         LocalS3Store another = LocalS3Store.readOnly(dataPath)) {
      // Nothing to share: each caller gets an in-memory store of its own, and neither locks the directory.
      assertNotSame(readOnly, another);
      assertFalse(readOnly.isReadOnly());
    }

    try (LocalS3Store writable = LocalS3Store.persistent(dataPath)) {
      assertFalse(writable.isReadOnly());
    }
  }

}
