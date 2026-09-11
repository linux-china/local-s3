package com.robothy.s3.core.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
    assertThrows(IOException.class, () -> storage.put(id, broken));

    assertArrayEquals("Hello".getBytes(), storage.getBytes(id));
    assertEquals(List.of(directory.resolve(String.valueOf(id))), listFiles());
  }

  private List<Path> listFiles() throws IOException {
    try (Stream<Path> files = Files.list(directory)) {
      return files.toList();
    }
  }

}
