package com.robothy.s3.core.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PathUtilsTest {

  @TempDir
  Path directory;

  /**
   * Windows refuses to replace or delete a file while it is open, e.g. while a response is written from the
   * stream of an object, and reports it as an {@linkplain AccessDeniedException}.
   */
  @Test
  void repeatsAnOperationThatFailsWhileTheFileIsOpen() throws IOException {
    AtomicInteger attempts = new AtomicInteger();

    PathUtils.retryingWhileInUse(() -> {
      if (attempts.incrementAndGet() < 3) {
        throw new AccessDeniedException("the file is used by another process");
      }
    });

    assertEquals(3, attempts.get());
  }

  @Test
  void reportsTheFailureOfAnOperationWhoseFileStaysOpen() {
    AtomicInteger attempts = new AtomicInteger();

    AccessDeniedException failure = assertThrows(AccessDeniedException.class,
        () -> PathUtils.retryingWhileInUse(() -> {
          attempts.incrementAndGet();
          throw new AccessDeniedException("the file is used by another process");
        }));

    assertEquals("the file is used by another process", failure.getFile());
    assertTrue(attempts.get() > 1, "The operation should have been repeated, but ran " + attempts.get() + " time.");
  }

  @Test
  void doesNotRepeatAnOperationOnAMissingFile() {
    AtomicInteger attempts = new AtomicInteger();

    assertThrows(NoSuchFileException.class, () -> PathUtils.retryingWhileInUse(() -> {
      attempts.incrementAndGet();
      throw new NoSuchFileException("missing");
    }));

    assertEquals(1, attempts.get());
  }

  @Test
  void movesAndDeletesFiles() throws IOException {
    Path source = Files.writeString(directory.resolve("source"), "Robothy");
    Path target = Files.writeString(directory.resolve("target"), "replaced");

    PathUtils.moveAtomically(source, target);
    assertFalse(Files.exists(source));
    assertArrayEquals("Robothy".getBytes(), Files.readAllBytes(target));

    PathUtils.delete(target);
    assertFalse(Files.exists(target));
    assertThrows(NoSuchFileException.class, () -> PathUtils.delete(target));
  }

}
