package com.robothy.s3.core.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.core.exception.TotalSizeExceedException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HeapContentTest {

  private static byte[] bytes(int length) {
    byte[] bytes = new byte[length];
    for (int i = 0; i < length; i++) {
      bytes[i] = (byte) i;
    }
    return bytes;
  }

  /**
   * Write {@code content} in pieces of {@code piece} bytes, so that the pieces straddle the chunks.
   */
  private static HeapContent receive(HeapContent.Writer writer, byte[] content, int piece) {
    for (int offset = 0; offset < content.length; offset += piece) {
      writer.write(ByteBuffer.wrap(content, offset, Math.min(piece, content.length - offset)));
    }
    return writer.finish();
  }

  private static byte[] readAll(InputStream in) throws IOException {
    try (in) {
      return in.readAllBytes();
    }
  }

  @Test
  void reservesTheExpectedLengthWhenTheWriterIsCreated() {
    InMemoryStorage storage = new InMemoryStorage(100, 16);
    HeapContent.Writer writer = storage.newHeapContentWriter(80).orElseThrow();

    assertThrows(TotalSizeExceedException.class, () -> storage.put(new byte[21]),
        "The reserved length is taken before a byte is received.");
    assertThrows(TotalSizeExceedException.class, () -> storage.newHeapContentWriter(21),
        "A body that doesn't fit is refused before it is received.");

    writer.discard();
    assertDoesNotThrow(() -> storage.put(new byte[100]), "Discarding gives the reserved space back.");
  }

  @Test
  void theStorageTakesOverWhatWasReceivedForItWithoutReservingItAgain() throws IOException {
    InMemoryStorage storage = new InMemoryStorage(100, 16);
    byte[] content = bytes(60);
    HeapContent received = receive(storage.newHeapContentWriter(60).orElseThrow(), content, 7);
    assertEquals(60, received.length());
    assertArrayEquals(content, readAll(received.newInputStream()));

    Long id = storage.put(received);
    received.release();

    assertArrayEquals(content, storage.getBytes(id));
    assertArrayEquals(Arrays.copyOfRange(content, 10, 50), readAll(storage.getInputStream(id, 10, 40)));
    assertDoesNotThrow(() -> storage.put(new byte[40]), "The content takes its space once.");
    assertThrows(TotalSizeExceedException.class, () -> storage.put(new byte[1]),
        "Releasing content that the storage took over gives nothing back.");

    storage.delete(id);
    assertDoesNotThrow(() -> storage.put(new byte[60]), "Deleting the content gives its space back.");
  }

  @Test
  void releasingContentThatWasntStoredGivesItsSpaceBack() {
    InMemoryStorage storage = new InMemoryStorage(100, 16);
    HeapContent received = receive(storage.newHeapContentWriter(100).orElseThrow(), bytes(100), 33);
    assertThrows(TotalSizeExceedException.class, () -> storage.put(new byte[1]));

    received.release();
    received.release();
    assertDoesNotThrow(() -> storage.put(new byte[100]), "Released once, however often it is released.");
  }

  @Test
  void fewerBytesThanExpectedGiveTheRestBackAndMoreAreReservedAsTheyCome() throws IOException {
    InMemoryStorage storage = new InMemoryStorage(100, 16);
    byte[] shorter = bytes(20);
    HeapContent received = receive(storage.newHeapContentWriter(50).orElseThrow(), shorter, 9);
    assertArrayEquals(shorter, readAll(received.newInputStream()));
    assertDoesNotThrow(() -> storage.put(new byte[80]), "The 30 bytes that weren't received are given back.");
    received.release();

    InMemoryStorage other = new InMemoryStorage(100, 16);
    byte[] longer = bytes(90);
    HeapContent more = receive(other.newHeapContentWriter(10).orElseThrow(), longer, 11);
    assertArrayEquals(longer, readAll(more.newInputStream()));
    assertDoesNotThrow(() -> other.put(new byte[10]));
    assertThrows(TotalSizeExceedException.class, () -> other.put(new byte[1]));

    HeapContent.Writer tooMuch = other.newHeapContentWriter(0).orElseThrow();
    assertThrows(TotalSizeExceedException.class, () -> tooMuch.write(ByteBuffer.wrap(new byte[1])),
        "Bytes beyond the budget are refused.");
  }

  @Test
  void anotherStorageCopiesTheContent() throws IOException {
    InMemoryStorage receivedFor = new InMemoryStorage(100, 16);
    InMemoryStorage other = new InMemoryStorage(100, 16);
    byte[] content = bytes(70);
    HeapContent received = receive(receivedFor.newHeapContentWriter(70).orElseThrow(), content, 70);

    Long id = other.put(received);
    assertArrayEquals(content, other.getBytes(id));
    assertThrows(TotalSizeExceedException.class, () -> other.put(new byte[31]), "The copy takes space of its own.");
    assertThrows(TotalSizeExceedException.class, () -> receivedFor.put(new byte[31]),
        "The received content keeps its space until it is released.");

    received.release();
    assertDoesNotThrow(() -> receivedFor.put(new byte[100]));
  }

  @Test
  void aLayeredStorageReceivesForItsFrontend() {
    Storage front = new InMemoryStorage(100, 16);
    Storage layered = Storage.createLayered(front, Storage.createInMemory());
    HeapContent received = receive(layered.newHeapContentWriter(60).orElseThrow(), bytes(60), 13);

    Long id = layered.put(received);
    received.release();
    assertTrue(front.isExist(id));
    assertDoesNotThrow(() -> front.put(new byte[40]));
    assertThrows(TotalSizeExceedException.class, () -> front.put(new byte[1]));
  }

  @Test
  void aStorageOnDiskDoesntReceiveIntoTheHeap(@TempDir Path directory) {
    assertTrue(Storage.createPersistent(directory).newHeapContentWriter(10).isEmpty());
  }

}
