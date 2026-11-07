package com.robothy.s3.core.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
    }, true);

    assertEquals(3, attempts.get());
  }

  @Test
  void reportsTheFailureOfAnOperationWhoseFileStaysOpen() {
    AtomicInteger attempts = new AtomicInteger();

    AccessDeniedException failure = assertThrows(AccessDeniedException.class,
        () -> PathUtils.retryingWhileInUse(() -> {
          attempts.incrementAndGet();
          throw new AccessDeniedException("the file is used by another process");
        }, true));

    assertEquals("the file is used by another process", failure.getFile());
    assertTrue(attempts.get() > 1, "The operation should have been repeated, but ran " + attempts.get() + " time.");
  }

  @Test
  void doesNotRepeatAnOperationOnAMissingFile() {
    AtomicInteger attempts = new AtomicInteger();

    assertThrows(NoSuchFileException.class, () -> PathUtils.retryingWhileInUse(() -> {
      attempts.incrementAndGet();
      throw new NoSuchFileException("missing");
    }, true));

    assertEquals(1, attempts.get());
  }

  @Test
  void repeatsAnOperationThatFailsWithASharingViolation() throws IOException {
    AtomicInteger attempts = new AtomicInteger();

    PathUtils.retryingWhileInUse(() -> {
      if (attempts.incrementAndGet() < 2) {
        throw new FileSystemException("the file is used by another process");
      }
    }, true);

    assertEquals(2, attempts.get());
  }

  @Test
  void doesNotRepeatAnOperationThatFailsDeterministically() {
    AtomicInteger attempts = new AtomicInteger();

    assertThrows(DirectoryNotEmptyException.class, () -> PathUtils.retryingWhileInUse(() -> {
      attempts.incrementAndGet();
      throw new DirectoryNotEmptyException("directory");
    }, true));

    assertEquals(1, attempts.get());
  }

  /**
   * Only Windows fails an operation because a file is open, so a denied access elsewhere is reported at once.
   */
  @Test
  void doesNotRepeatAnOperationOutsideWindows() {
    AtomicInteger attempts = new AtomicInteger();

    assertThrows(AccessDeniedException.class, () -> PathUtils.retryingWhileInUse(() -> {
      attempts.incrementAndGet();
      throw new AccessDeniedException("file");
    }, false));

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

  @Test
  void createsMissingDirectoriesAndAcceptsExistingOnes() {
    Path nested = directory.resolve("a").resolve("b");

    PathUtils.createDirectoryIfNotExist(nested);
    assertTrue(Files.isDirectory(nested));

    PathUtils.createDirectoryIfNotExist(nested);
    assertTrue(Files.isDirectory(nested));
  }

  @Test
  void createsTheSameDirectoryConcurrently() throws Exception {
    int threads = 8;
    ExecutorService executor = Executors.newFixedThreadPool(threads);
    try {
      for (int round = 0; round < 20; round++) {
        Path target = directory.resolve("round-" + round).resolve("data");
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
          futures.add(executor.submit(() -> {
            start.await();
            PathUtils.createDirectoryIfNotExist(target);
            return null;
          }));
        }
        start.countDown();
        for (Future<?> future : futures) {
          future.get();
        }
        assertTrue(Files.isDirectory(target));
      }
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void reportsAFileThatBlocksTheDirectory() throws IOException {
    Path file = Files.writeString(directory.resolve("file"), "Robothy");

    UncheckedIOException e =
        assertThrows(UncheckedIOException.class, () -> PathUtils.createDirectoryIfNotExist(file));
    assertTrue(e.getCause() instanceof FileAlreadyExistsException);
    assertTrue(e.getMessage().contains(file.toAbsolutePath().toString()));
  }

}
