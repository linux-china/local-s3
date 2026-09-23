package com.robothy.s3.core.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CompositeInputStreamTest {

  @Test
  void readsThePartsOneAfterAnother() throws IOException {
    try (CompositeInputStream in = parts("Hello", "", "Local", "S3!")) {
      assertEquals('H', in.read());
      byte[] buffer = new byte[64];
      assertEquals(4, in.read(buffer, 0, buffer.length), "A read doesn't span parts.");
      assertArrayEquals("LocalS3!".getBytes(), in.readAllBytes());
      assertEquals(-1, in.read());
      assertEquals(-1, in.read(buffer, 0, buffer.length));
      assertEquals(0, in.read(buffer, 0, 0));
    }
  }

  @Test
  void skipsAcrossParts() throws IOException {
    try (CompositeInputStream in = parts("Hello", "", "Local", "S3!")) {
      assertEquals(7, in.skip(7));
      assertEquals('c', in.read());
      assertEquals(5, in.skip(100));
      assertEquals(-1, in.read());
    }
  }

  @Test
  void closesAllParts() throws IOException {
    AtomicInteger closed = new AtomicInteger();
    List<InputStream> streams = List.of(closing("a", closed), closing("b", closed));
    CompositeInputStream in = new CompositeInputStream(streams);
    assertEquals(2, in.partCount());
    in.close();
    assertEquals(2, closed.get());
    assertEquals(-1, in.read());
    assertTrue(in.available() == 0);
  }

  /**
   * A part is opened when it is read, and closed once it is exhausted, so that the whole content is read with one
   * part open at a time.
   */
  @Test
  void opensOnePartAtATime() throws IOException {
    AtomicInteger open = new AtomicInteger();
    AtomicInteger maxOpen = new AtomicInteger();
    try (CompositeInputStream in = new CompositeInputStream(List.<CompositeInputStream.Part>of(
        counting("Hello", open, maxOpen), counting("Local", open, maxOpen), counting("S3!", open, maxOpen)),
        ContentRetention.NONE)) {
      assertEquals(0, open.get(), "No part is opened before the content is read.");
      assertArrayEquals("HelloLocalS3!".getBytes(), in.readAllBytes());
      assertEquals(1, maxOpen.get(), "One part is open at a time.");
      assertEquals(0, open.get(), "Every part that was read is closed.");
    }
  }

  @Test
  void releasesTheRetentionOfTheContentWhenItIsClosed() throws IOException {
    AtomicInteger released = new AtomicInteger();
    CompositeInputStream in = new CompositeInputStream(
        List.<CompositeInputStream.Part>of(() -> new ByteArrayInputStream("a".getBytes()),
            () -> new ByteArrayInputStream("b".getBytes())),
        released::incrementAndGet);
    in.close();
    in.close();
    assertEquals(1, released.get(), "The retention is released once.");
  }

  /**
   * A part that a transport takes over is closed by it, not by the stream.
   */
  @Test
  void handsOutThePartsOfTheContent() throws IOException {
    AtomicInteger closed = new AtomicInteger();
    CompositeInputStream in = new CompositeInputStream(List.of(closing("a", closed), closing("b", closed)));
    assertArrayEquals("a".getBytes(), in.openPart(0).readAllBytes());
    assertArrayEquals("b".getBytes(), in.openPart(1).readAllBytes());
    in.close();
    assertEquals(0, closed.get());
  }

  private static CompositeInputStream parts(String... parts) {
    return new CompositeInputStream(java.util.Arrays.stream(parts)
        .map(part -> (InputStream) new ByteArrayInputStream(part.getBytes()))
        .toList());
  }

  private static CompositeInputStream.Part counting(String content, AtomicInteger open, AtomicInteger maxOpen) {
    return () -> {
      maxOpen.accumulateAndGet(open.incrementAndGet(), Math::max);
      return new ByteArrayInputStream(content.getBytes()) {
        @Override
        public void close() {
          open.decrementAndGet();
        }
      };
    };
  }

  private static InputStream closing(String content, AtomicInteger closed) {
    return new ByteArrayInputStream(content.getBytes()) {
      @Override
      public void close() {
        closed.incrementAndGet();
      }
    };
  }

}
