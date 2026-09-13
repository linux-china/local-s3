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
    assertEquals(streams, in.getStreams());
    in.close();
    assertEquals(2, closed.get());
    assertEquals(-1, in.read());
    assertTrue(in.available() == 0);
  }

  private static CompositeInputStream parts(String... parts) {
    return new CompositeInputStream(java.util.Arrays.stream(parts)
        .map(part -> (InputStream) new ByteArrayInputStream(part.getBytes()))
        .toList());
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
