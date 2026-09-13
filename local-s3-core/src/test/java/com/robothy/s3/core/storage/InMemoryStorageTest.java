package com.robothy.s3.core.storage;

import static org.junit.jupiter.api.Assertions.*;
import com.robothy.s3.core.exception.TotalSizeExceedException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InMemoryStorageTest {

  @Test
  void test() {
    int _2KB = 2 * 1024;
    InMemoryStorage storage = new InMemoryStorage(_2KB);
    assertThrows(TotalSizeExceedException.class, () -> storage.put(new byte[_2KB + 1]));
    Long id = storage.put(new byte[_2KB]);
    assertNotNull(id);
  }

  @Test
  void callersCannotChangeTheStoredData() throws IOException {
    InMemoryStorage storage = new InMemoryStorage();
    byte[] data = "Hello".getBytes();
    Long id = storage.put(data);
    data[0] = 'J';
    storage.getBytes(id)[0] = 'C';

    try (InputStream in = storage.getInputStream(id)) {
      assertArrayEquals("Hello".getBytes(), in.readAllBytes());
    }
  }

  @Test
  void overwritingAnObjectReplacesItsSize() {
    int _1KB = 1024;
    InMemoryStorage storage = new InMemoryStorage(2 * _1KB);
    Long id = storage.put(new byte[_1KB]);
    storage.put(id, new ByteArrayInputStream(new byte[_1KB]));
    assertDoesNotThrow(() -> storage.put(new byte[_1KB]));

    storage.delete(id);
    assertThrows(IllegalArgumentException.class, () -> storage.delete(id));
    assertThrows(IllegalArgumentException.class, () -> storage.getInputStream(id));
  }

  /**
   * The content of an object is kept in chunks, so that an object may be larger than an array; the chunks are
   * exercised with a chunk size of a few bytes.
   */
  @Test
  void storesAndReadsContentAcrossChunks() throws IOException {
    InMemoryStorage storage = new InMemoryStorage(Long.MAX_VALUE, 4);
    byte[] content = "Hello LocalS3!".getBytes();

    Long fromBytes = storage.put(content);
    Long fromStream = storage.put(new ByteArrayInputStream(content));
    // A stream that reports nothing available and returns a byte at a time.
    Long fromSlowStream = storage.put(new InputStream() {
      private int index;

      @Override
      public int read() {
        return index < content.length ? content[index++] : -1;
      }
    });

    for (Long id : new Long[] {fromBytes, fromStream, fromSlowStream}) {
      assertEquals(content.length, storage.size(id));
      assertArrayEquals(content, storage.getBytes(id));
      try (InputStream in = storage.getInputStream(id)) {
        assertArrayEquals(content, in.readAllBytes());
      }
      try (InputStream in = storage.getInputStream(id, 3, 9)) {
        assertArrayEquals("lo LocalS".getBytes(), in.readAllBytes());
      }
      try (InputStream in = storage.getInputStream(id, 4, 4)) {
        assertArrayEquals("o Lo".getBytes(), in.readAllBytes(), "A region that is exactly a chunk.");
      }
      try (InputStream in = storage.getInputStream(id, content.length, 0)) {
        assertEquals(-1, in.read());
      }
      try (InputStream in = storage.getInputStream(id)) {
        assertEquals(6, in.skip(6));
        assertEquals('L', in.read());
        assertEquals(content.length - 7, in.available());
      }
    }
    assertThrows(IllegalArgumentException.class, () -> storage.getInputStream(fromBytes, 10, 5));
  }

  @Test
  void storesEmptyContent() throws IOException {
    InMemoryStorage storage = new InMemoryStorage();
    Long id = storage.put(new ByteArrayInputStream(new byte[0]));
    assertEquals(0, storage.size(id));
    assertArrayEquals(new byte[0], storage.getBytes(id));
    try (InputStream in = storage.getInputStream(id)) {
      assertEquals(-1, in.read());
    }
  }

  @Test
  void limitsTheTotalSizeOfAStream() {
    InMemoryStorage storage = new InMemoryStorage(10, 4);
    assertThrows(TotalSizeExceedException.class, () -> storage.put(new ByteArrayInputStream(new byte[11])));
    assertDoesNotThrow(() -> storage.put(new ByteArrayInputStream(new byte[10])));
  }

  @Test
  void copiesAFile(@TempDir Path directory) throws IOException {
    InMemoryStorage storage = new InMemoryStorage();
    Path file = Files.write(directory.resolve("body"), "Hello".getBytes());
    Long id = storage.put(file);
    assertArrayEquals("Hello".getBytes(), storage.getBytes(id));
    assertTrue(Files.exists(file), "The file is left to the caller.");
  }

}
