package com.robothy.s3.core.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.h2.mvstore.MVMap;
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
  void openingADataDirectoryWithAnotherPersistencePolicyIsRejected(@TempDir Path dataPath) {
    try (LocalS3Store durable = LocalS3Store.persistent(dataPath, PersistencePolicy.DURABLE)) {
      assertTrue(durable.commitsEveryChange());

      IllegalStateException e = assertThrows(IllegalStateException.class,
          () -> LocalS3Store.persistent(dataPath, PersistencePolicy.FAST));
      assertTrue(e.getMessage().contains("DURABLE") && e.getMessage().contains("FAST"), e.getMessage());

      // The rejected call took no hold of the store, and the same policy still shares it.
      try (LocalS3Store shared = LocalS3Store.persistent(dataPath, PersistencePolicy.DURABLE)) {
        assertSame(durable, shared);
      }
      assertFalse(durable.store().isClosed());
    }

    try (LocalS3Store fast = LocalS3Store.persistent(dataPath, PersistencePolicy.FAST)) {
      assertFalse(fast.commitsEveryChange(), "The policy is free again once the store is closed.");
    }
  }

  /**
   * A reader of a data directory takes the store whatever policy it was opened with: the policy says when writes
   * reach the disk, which is nothing to a reader.
   */
  @Test
  void readingADataDirectoryIgnoresThePersistencePolicy(@TempDir Path dataPath) {
    try (LocalS3Store fast = LocalS3Store.persistent(dataPath, PersistencePolicy.FAST);
         LocalS3Store reader = LocalS3Store.readOnly(dataPath)) {
      assertSame(fast, reader);
    }
  }

  /**
   * A commit appends a chunk rather than replacing what it supersedes, so a store that is written a lot leaves a file
   * far larger than the data in it. Closing it compacts the file, so a data directory rests at the size of what it
   * holds.
   */
  @Test
  void compactsTheFileWhenTheLastHolderClosesIt(@TempDir Path dataPath) throws IOException {
    Path file = dataPath.resolve(LocalS3Store.FILE_NAME);
    long whileWriting;
    try (LocalS3Store store = LocalS3Store.persistent(dataPath)) {
      MVMap<String, String> map = store.store().openMap("objects/b");
      // One key, written again and again: every commit leaves a chunk, and only the last one holds live data.
      for (int i = 0; i < 2_000; i++) {
        map.put("the-key", "value-" + i);
        store.store().commit();
      }
      whileWriting = Files.size(file);
    }

    long atRest = Files.size(file);
    assertTrue(whileWriting > 8 * atRest,
        "The file was " + whileWriting + " bytes while writing and " + atRest + " at rest: it wasn't compacted.");

    // What it holds is still there.
    try (LocalS3Store reopened = LocalS3Store.persistent(dataPath)) {
      assertEquals("value-1999", reopened.store().openMap("objects/b").get("the-key"));
    }
  }

  /**
   * A service that runs for long, e.g. in an IDE, doesn't wait for its store to be closed to get back the room that
   * superseded chunks take.
   */
  @Test
  void compactsAWastefulFileWhileTheStoreIsOpen(@TempDir Path dataPath) throws IOException {
    Path file = dataPath.resolve(LocalS3Store.FILE_NAME);
    try (LocalS3Store store = LocalS3Store.persistent(dataPath)) {
      MVMap<String, String> map = store.store().openMap("objects/b");
      assertFalse(store.compactIfWasteful(), "A small file is left alone.");

      int retentionTime = store.store().getRetentionTime();
      for (int i = 0; i < 3_000; i++) {
        map.put("the-key", "value-" + i);
        store.store().commit();
      }
      long whileWriting = Files.size(file);
      assertTrue(whileWriting >= LocalS3Store.COMPACTION_MIN_FILE_SIZE, whileWriting + " bytes");

      assertTrue(store.compactIfWasteful());
      long compacted = Files.size(file);
      assertTrue(whileWriting > 8 * compacted,
          "The file was " + whileWriting + " bytes while writing and " + compacted + " once compacted.");
      assertEquals(retentionTime, store.store().getRetentionTime(), "Compacting keeps the retention time.");
      assertFalse(store.compactIfWasteful(), "A compacted file is left alone.");

      // The store is still written and read as before.
      map.put("the-key", "after");
      store.store().commit();
    }

    try (LocalS3Store reopened = LocalS3Store.persistent(dataPath)) {
      assertEquals("after", reopened.store().openMap("objects/b").get("the-key"));
    }
  }

  @Test
  void aStoreThatIsNotWrittenIsNotCompacted(@TempDir Path dataPath) {
    LocalS3Store.persistent(dataPath).close();
    try (LocalS3Store inMemory = LocalS3Store.inMemory(); LocalS3Store readOnly = LocalS3Store.readOnly(dataPath)) {
      assertFalse(inMemory.compactIfWasteful());
      assertFalse(readOnly.compactIfWasteful());
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
