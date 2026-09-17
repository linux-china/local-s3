package com.robothy.s3.core.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import com.robothy.s3.core.util.IdUtils;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import com.robothy.s3.core.TestFiles;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

class LocalFileSystemStorageTest {

  private Path directory;

  @BeforeEach
  void setUp() throws IOException {
    directory = Files.createTempDirectory("storage");
  }

  @AfterEach
  void tearDown() throws IOException {
    TestFiles.deleteDirectory(directory);
  }

  /**
   * Creating a storage doesn't delete the temporary files of its directory: they may be the writes in progress of
   * another service of the JVM over the same data directory. The files that a process which died left behind are deleted
   * by {@linkplain UnreferencedContentSweeper}, once no service of the JVM uses the directory.
   */
  @Test
  void keepsTheTempFilesOfAWriteInProgressOnCreation() throws Exception {
    Storage storage = Storage.createPersistent(directory);
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch proceed = new CountDownLatch(1);
    InputStream slow = new InputStream() {
      private int sent;

      @Override
      public int read() throws IOException {
        if (sent == 1) {
          started.countDown();
          try {
            proceed.await();
          } catch (InterruptedException e) {
            throw new IOException(e);
          }
        }
        return sent < 5 ? "Hello".charAt(sent++) : -1;
      }
    };
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<Long> put = executor.submit(() -> storage.put(42L, slow));
      assertTrue(started.await(10, TimeUnit.SECONDS));

      Storage.createPersistent(directory);
      proceed.countDown();

      assertEquals(42L, put.get(10, TimeUnit.SECONDS));
      assertArrayEquals("Hello".getBytes(), storage.getBytes(42L));
    } finally {
      proceed.countDown();
      executor.shutdownNow();
    }
    Path leftover = Files.writeString(directory.resolve(".43.0000.tmp"), "partial");
    Storage.createPersistent(directory);
    assertTrue(Files.exists(leftover));
  }

  @Test
  void movesTheObjectFilesOfTheFlatLayoutOnCreation() throws IOException {
    Path object = Files.writeString(directory.resolve("42"), "complete");

    Storage storage = Storage.createPersistent(directory);

    assertArrayEquals("complete".getBytes(), storage.getBytes(42L));
    assertEquals(List.of(objectPath(storage, 42L)), listFiles(), "The object file of the flat layout is moved.");
    assertFalse(Files.exists(object));
  }

  @Test
  void overwritesWithoutLeavingTempFiles() throws IOException {
    Storage storage = Storage.createPersistent(directory);
    Long id = storage.put("Hello".getBytes());
    storage.put(id, new ByteArrayInputStream("Hi".getBytes()));

    assertArrayEquals("Hi".getBytes(), storage.getBytes(id));
    assertEquals(List.of(objectPath(storage, id)), listFiles());
  }

  @Test
  void opensFileRegionsAtTheRequestedPosition() throws IOException {
    Storage storage = Storage.createPersistent(directory);
    Long id = storage.put("Hello".getBytes());

    try (InputStream content = storage.getInputStream(id, 1, 3)) {
      FileRegionInputStream fileRegion = assertInstanceOf(FileRegionInputStream.class, content);
      assertEquals(1, fileRegion.getPosition());
      assertEquals(3, fileRegion.getCount());
      assertArrayEquals("ell".getBytes(), fileRegion.readAllBytes());
      assertTrue(fileRegion.getChannel().isOpen());
    }
  }

  @Test
  void failedWriteKeepsPreviousContent() throws IOException {
    Storage storage = Storage.createPersistent(directory);
    Long id = storage.put("Hello".getBytes());

    // Fails for good after 3 bytes, like a reset connection. InputStream.read(byte[], int, int) swallows
    // an IOException thrown after the first byte of a read, so the stream must keep failing.
    InputStream broken = new InputStream() {
      private int count;

      @Override
      public int read() throws IOException {
        if (count++ >= 3) {
          throw new IOException("Connection reset.");
        }
        return 'x';
      }
    };
    // The failure reaches the caller as an UncheckedIOException that carries the IOException of the write.
    UncheckedIOException thrown = assertThrows(UncheckedIOException.class, () -> storage.put(id, broken));
    assertEquals("Connection reset.", thrown.getCause().getMessage());

    assertArrayEquals("Hello".getBytes(), storage.getBytes(id));
    assertEquals(List.of(objectPath(storage, id)), listFiles());
  }

  /**
   * GetObject returns the stream of an object, which the response is written from after the bucket lock is
   * released, so an object can be overwritten and deleted while it is read. POSIX keeps the content that a
   * reader opened; Windows refuses to replace or delete an open file, which {@linkplain
   * com.robothy.s3.core.util.PathUtils} works around by repeating the operation, so this test states the
   * POSIX semantics only.
   */
  @Test
  @EnabledOnOs({OS.LINUX, OS.MAC})
  void objectIsOverwrittenAndDeletedWhileItIsRead() throws IOException {
    Storage storage = Storage.createPersistent(directory);
    Long id = storage.put("Hello".getBytes());

    try (InputStream reading = storage.getInputStream(id)) {
      storage.put(id, new ByteArrayInputStream("Replaced".getBytes()));
      assertArrayEquals("Replaced".getBytes(), storage.getBytes(id));

      storage.delete(id);
      assertFalse(storage.isExist(id));

      // The reader keeps the content that it opened.
      assertArrayEquals("Hello".getBytes(), reading.readAllBytes());
    }

    assertEquals(List.of(), listFiles());
  }

  @Test
  void renamesAFileIntoPlace() throws IOException {
    Storage storage = Storage.createPersistent(directory);
    Path file = Files.writeString(directory.resolve(".body.tmp"), "Hello");

    Files.setLastModifiedTime(file, FileTime.from(Instant.now().minus(Duration.ofHours(1))));
    Instant beforePut = Instant.now().minusSeconds(2);

    Long id = storage.put(file);

    assertFalse(Files.exists(file), "The file is taken over.");
    // A file is last modified when it is stored, however long ago it was written, so that the sweep of unreferenced
    // content tells a file stored after the store was opened from one left behind before.
    assertFalse(Files.getLastModifiedTime(objectPath(storage, id)).toInstant().isBefore(beforePut));
    assertArrayEquals("Hello".getBytes(), storage.getBytes(id));
    assertEquals(5, storage.size(id));
    assertEquals(List.of(objectPath(storage, id)), listFiles());
  }

  @Test
  void copiesAFileThatCannotBeRenamed() throws IOException {
    Storage storage = Storage.createPersistent(directory);
    Path missing = directory.resolve("missing");
    assertThrows(UncheckedIOException.class, () -> storage.put(missing));
    assertEquals(List.of(), listFiles(), "Nothing is left behind.");
  }

  @Test
  void sizeOfAMissingObjectIsReported() {
    Storage storage = Storage.createPersistent(directory);
    assertThrows(IllegalArgumentException.class, () -> storage.size(42L));
  }

  /**
   * The object files are spread over two levels of subdirectories, named by a hash of the ID. The layout decides where
   * the objects that are already stored are found, so it must never change.
   */
  @Test
  void storesObjectsInTwoLevelsOfSubdirectories() throws IOException {
    LocalFileSystemStorage storage = new LocalFileSystemStorage(directory);
    assertEquals(directory.resolve("b4").resolve("56").resolve("1"), storage.objectPath(1L));
    assertEquals(directory.resolve("81").resolve("08").resolve("42"), storage.objectPath(42L));
    assertEquals(directory.resolve("9c").resolve("49").resolve("1234567890123456789"),
        storage.objectPath(1234567890123456789L));

    storage.put(42L, "Hello".getBytes());
    assertEquals(List.of(directory.resolve("81/08/42")), listFiles());
  }

  /**
   * The IDs of the generator are time ordered, and those generated in a burst differ only in their low bits, which
   * the subdirectories spread evenly.
   */
  @Test
  void spreadsTheIdsOfTheGeneratorEvenly() {
    LocalFileSystemStorage storage = new LocalFileSystemStorage(directory);
    int count = 100_000;
    Map<Path, Integer> perLeaf = new HashMap<>();
    Set<Path> firstLevel = new HashSet<>();
    for (int i = 0; i < count; i++) {
      Path leaf = storage.objectPath(IdUtils.defaultGenerator().nextId()).getParent();
      perLeaf.merge(leaf, 1, Integer::sum);
      firstLevel.add(leaf.getParent());
    }
    assertEquals(256, firstLevel.size(), "Every first level subdirectory is used.");
    int max = perLeaf.values().stream().mapToInt(Integer::intValue).max().orElseThrow();
    // 65536 leaves share 100000 IDs, about 1.5 each: placed at random, about 51300 leaves are used, and none holds
    // more than about 8. A hash of the time bits alone would put thousands of IDs in one.
    assertTrue(max <= 16, "At most " + max + " IDs share a subdirectory.");
    assertTrue(perLeaf.size() > 49_000, perLeaf.size() + " subdirectories are used.");
  }

  /**
   * A writable storage moves the object files of the flat layout of a LocalS3 before 2.5 into their subdirectories,
   * and leaves every other entry of the directory alone.
   */
  @Test
  void movesTheObjectFilesOfTheFlatLayout() throws IOException {
    Files.writeString(directory.resolve("42"), "flat");
    Files.writeString(directory.resolve("notes.txt"), "not an object");
    Files.writeString(directory.resolve("99999999999999999999"), "too large to be an ID");
    Files.createDirectories(directory.resolve(".request-bodies"));

    LocalFileSystemStorage storage = new LocalFileSystemStorage(directory);

    assertArrayEquals("flat".getBytes(), storage.getBytes(42L));
    assertTrue(Files.isRegularFile(storage.objectPath(42L)));
    assertFalse(Files.exists(directory.resolve("42")));
    assertTrue(Files.exists(directory.resolve("notes.txt")));
    assertTrue(Files.exists(directory.resolve("99999999999999999999")));
    assertTrue(Files.isDirectory(directory.resolve(".request-bodies")));
  }

  /**
   * An object file of the flat layout that appears after the storage was created, e.g. one that a process which died
   * during the move left, is still found, and replaced or deleted with the object.
   */
  @Test
  void findsReplacesAndDeletesAnObjectFileOfTheFlatLayout() throws IOException {
    LocalFileSystemStorage storage = new LocalFileSystemStorage(directory);
    Path flat = Files.writeString(directory.resolve("42"), "flat");
    assertTrue(storage.isExist(42L));
    assertEquals(4, storage.size(42L));

    storage.put(42L, "replaced".getBytes());
    assertFalse(Files.exists(flat), "The object is kept once.");
    assertArrayEquals("replaced".getBytes(), storage.getBytes(42L));

    Files.writeString(directory.resolve("43"), "flat");
    storage.delete(43L);
    assertFalse(storage.isExist(43L));
  }

  /**
   * A read-only storage, e.g. over the initial data of an {@code IN_MEMORY} service, which may be files under version
   * control, neither moves nor deletes anything.
   */
  @Test
  void aReadOnlyStorageReadsBothLayoutsWithoutChangingTheDirectory() throws IOException {
    new LocalFileSystemStorage(directory).put(1L, "sharded".getBytes());
    Path flat = Files.writeString(directory.resolve("42"), "flat");
    Path leftover = Files.writeString(directory.resolve(".7.0000.tmp"), "partial");

    Storage readOnly = Storage.createReadOnlyPersistent(directory);

    assertArrayEquals("sharded".getBytes(), readOnly.getBytes(1L));
    assertArrayEquals("flat".getBytes(), readOnly.getBytes(42L));
    try (InputStream in = readOnly.getInputStream(42L, 1, 2)) {
      assertArrayEquals("la".getBytes(), in.readAllBytes());
    }
    assertTrue(Files.exists(flat), "The flat layout isn't changed.");
    assertTrue(Files.exists(leftover), "The temporary files aren't deleted.");
    assertThrows(UnsupportedOperationException.class, () -> readOnly.put("new".getBytes()));
    assertThrows(UnsupportedOperationException.class, () -> readOnly.delete(42L));
    assertThrows(IllegalArgumentException.class, () -> readOnly.delete(404L), "A missing object is reported as such.");

    Path missing = directory.resolve("missing");
    Storage overMissing = Storage.createReadOnlyPersistent(missing);
    assertFalse(overMissing.isExist(1L));
    assertFalse(Files.exists(missing));
  }

  private static Path objectPath(Storage storage, Long id) {
    return ((LocalFileSystemStorage) storage).objectPath(id);
  }

  private List<Path> listFiles() throws IOException {
    try (Stream<Path> files = Files.walk(directory)) {
      return files.filter(Files::isRegularFile).toList();
    }
  }

}
