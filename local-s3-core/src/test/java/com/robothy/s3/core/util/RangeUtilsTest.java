package com.robothy.s3.core.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class RangeUtilsTest {

  @Test
  void applyRangeSkipsPrefixAndReturnsRequestedLength() throws IOException {
    InputStream stream = new ByteArrayInputStream("Hello, World!".getBytes(StandardCharsets.UTF_8));
    byte[] bytes = RangeUtils.applyRange(stream, 7, 5).readAllBytes();
    assertArrayEquals("World".getBytes(StandardCharsets.UTF_8), bytes);
  }

  @Test
  void applyRangeHandlesNonProgressingSkip() throws IOException {
    InputStream stream = new ByteArrayInputStream("abcdef".getBytes(StandardCharsets.UTF_8)) {
      @Override
      public long skip(long n) {
        return 0;
      }
    };
    byte[] bytes = RangeUtils.applyRange(stream, 3, 2).readAllBytes();
    assertArrayEquals("ab".getBytes(StandardCharsets.UTF_8), bytes);
  }

  @Test
  void applyRangeWrapsSkipIoException() {
    InputStream stream = new InputStream() {
      @Override
      public int read() {
        return 0;
      }

      @Override
      public long skip(long n) throws IOException {
        throw new IOException("boom");
      }
    };
    assertThrows(IllegalStateException.class, () -> RangeUtils.applyRange(stream, 1, 1));
  }

  @Test
  void applyRangeReadsLazily() throws IOException {
    InputStream stream = new InputStream() {
      @Override
      public int read() throws IOException {
        throw new IOException("boom");
      }
    };
    InputStream ranged = RangeUtils.applyRange(stream, 0, 1);
    assertThrows(IOException.class, ranged::read);
  }

  @Test
  void applyRangeStopsAtLengthAndClosesOriginal() throws IOException {
    AtomicBoolean closed = new AtomicBoolean();
    InputStream stream = new ByteArrayInputStream("Hello, World!".getBytes(StandardCharsets.UTF_8)) {
      @Override
      public void close() throws IOException {
        closed.set(true);
        super.close();
      }
    };
    try (InputStream ranged = RangeUtils.applyRange(stream, 7, 5)) {
      assertEquals('W', ranged.read());
      assertArrayEquals("orld".getBytes(StandardCharsets.UTF_8), ranged.readAllBytes());
      assertEquals(-1, ranged.read());
    }
    assertTrue(closed.get());
  }
}