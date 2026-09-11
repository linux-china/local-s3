package com.robothy.s3.core.storage;

import static org.junit.jupiter.api.Assertions.*;
import com.robothy.s3.core.exception.TotalSizeExceedException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

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

}