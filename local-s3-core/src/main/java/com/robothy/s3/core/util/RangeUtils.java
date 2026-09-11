package com.robothy.s3.core.util;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Utility for applying a resolved byte range to an {@link InputStream}.
 */
public class RangeUtils {

  /**
   * Skip {@code start} bytes in {@code stream} and return a new {@link InputStream} that yields
   * at most {@code length} bytes from that position. The data is read lazily, so the range is never
   * buffered in memory. Closing the returned stream closes the original stream.
   */
  public static InputStream applyRange(InputStream stream, long start, long length) {
    try {
      long remaining = start;
      while (remaining > 0) {
        long skipped = stream.skip(remaining);
        if (skipped <= 0) {
          break;
        }
        remaining -= skipped;
      }
      return new BoundedInputStream(stream, length);
    } catch (IOException e) {
      try {
        stream.close();
      } catch (IOException closeException) {
        e.addSuppressed(closeException);
      }
      throw new IllegalStateException(e);
    }
  }

  /**
   * An {@link InputStream} that reads at most a fixed number of bytes from the wrapped stream.
   */
  private static final class BoundedInputStream extends FilterInputStream {

    private long remaining;

    private BoundedInputStream(InputStream in, long limit) {
      super(in);
      this.remaining = limit;
    }

    @Override
    public int read() throws IOException {
      if (remaining <= 0) {
        return -1;
      }
      int b = super.read();
      if (b != -1) {
        remaining--;
      }
      return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      if (len == 0) {
        return 0;
      }
      if (remaining <= 0) {
        return -1;
      }
      int n = super.read(b, off, (int) Math.min(len, remaining));
      if (n > 0) {
        remaining -= n;
      }
      return n;
    }

    @Override
    public long skip(long n) throws IOException {
      long skipped = super.skip(Math.min(n, remaining));
      remaining -= skipped;
      return skipped;
    }

    @Override
    public int available() throws IOException {
      return (int) Math.min(super.available(), remaining);
    }

    @Override
    public boolean markSupported() {
      return false;
    }
  }

}
