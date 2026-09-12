package com.robothy.s3.core.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.apache.commons.io.FileUtils;
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
    FileUtils.deleteDirectory(directory.toFile());
  }

  @Test
  void deletesLeftoverTempFilesOnCreation() throws IOException {
    Path leftover = Files.writeString(directory.resolve(".42." + "0000" + ".tmp"), "partial");
    Path object = Files.writeString(directory.resolve("42"), "complete");

    Storage storage = Storage.createPersistent(directory);

    assertFalse(Files.exists(leftover));
    assertArrayEquals("complete".getBytes(), storage.getBytes(42L));
    assertEquals(List.of(object), listFiles());
  }

  @Test
  void overwritesWithoutLeavingTempFiles() throws IOException {
    Storage storage = Storage.createPersistent(directory);
    Long id = storage.put("Hello".getBytes());
    storage.put(id, new ByteArrayInputStream("Hi".getBytes()));

    assertArrayEquals("Hi".getBytes(), storage.getBytes(id));
    assertEquals(List.of(directory.resolve(String.valueOf(id))), listFiles());
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
    assertEquals(List.of(directory.resolve(String.valueOf(id))), listFiles());
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

  private List<Path> listFiles() throws IOException {
    try (Stream<Path> files = Files.list(directory)) {
      return files.toList();
    }
  }

}
