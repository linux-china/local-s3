package com.robothy.s3.core.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class StorageTest {

  @Test
  void testCreate() throws IOException {
    Path storage = Files.createTempDirectory("storage");
    storage.toFile().deleteOnExit();
    assertInstanceOf(InMemoryStorage.class, Storage.createInMemory());
    assertInstanceOf(InMemoryStorage.class, Storage.createInMemory(100));
    assertInstanceOf(LocalFileSystemStorage.class, Storage.createPersistent(storage));
    assertInstanceOf(LayeredStorage.class, Storage.createLayered(Storage.createInMemory(), Storage.createInMemory()));
    assertInstanceOf(CopyOnAccessStorage.class, Storage.createCopyOnAccess(Storage.createInMemory()));
  }

  @ParameterizedTest
  @MethodSource("testCases")
  void test(Storage storage) throws IOException {
    Long helloId = storage.put("Hello".getBytes());
    assertArrayEquals("Hello".getBytes(), storage.getBytes(helloId));
    assertEquals(helloId, storage.put(helloId, "Hi".getBytes()));
    assertArrayEquals("Hi".getBytes(), storage.getBytes(helloId));

    try(InputStream in = storage.getInputStream(helloId)) {
      assertArrayEquals("Hi".getBytes(), in.readAllBytes());
    }

    Long hiId = storage.put(new ByteArrayInputStream("嗨嗨害".getBytes(StandardCharsets.UTF_8)));
    assertEquals("嗨嗨害", new String(storage.getBytes(hiId), StandardCharsets.UTF_8));

    assertThrows(IllegalArgumentException.class, () -> storage.getBytes(666L));
    assertThrows(IllegalArgumentException.class, () -> storage.getInputStream(666L));
    assertEquals(helloId, storage.delete(helloId));
    assertEquals(helloId, storage.delete(helloId), "Deleting missing content does nothing.");
    assertThrows(IllegalArgumentException.class, () -> storage.getBytes(helloId));
  }

  @ParameterizedTest
  @MethodSource("testCases")
  void putClosesTheStream(Storage storage) {
    AtomicBoolean closed = new AtomicBoolean();
    storage.put(new ByteArrayInputStream("Hello".getBytes()) {
      @Override
      public void close() {
        closed.set(true);
      }
    });
    assertTrue(closed.get());

    AtomicBoolean failedClosed = new AtomicBoolean();
    assertThrows(UncheckedIOException.class, () -> storage.put(new InputStream() {
      @Override
      public int read() throws IOException {
        throw new IOException("Broken stream.");
      }

      @Override
      public void close() {
        failedClosed.set(true);
      }
    }));
    assertTrue(failedClosed.get(), "The stream is closed even if the put fails.");
  }

  static Stream<Arguments> testCases() throws IOException {
    Path storage = Files.createTempDirectory("storage");
    return Stream.of(
        arguments(Storage.createPersistent(storage)),
        arguments(Storage.createPersistent(storage)),
        arguments(Storage.createInMemory(), Storage.createInMemory()),
        arguments(Storage.createCopyOnAccess(Storage.createInMemory())),
        arguments(Storage.createLayered(Storage.createInMemory(), Storage.createInMemory()))
    );
  }

}