package com.robothy.s3.rest.handler;

import com.robothy.s3.rest.netty.RequestBodies;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;

/**
 * The bytes of a request body, read by index, so that the payload of a request can be verified in place, whether its
 * {@code ByteBuf} holds the body or the body is only in a file, see {@linkplain RequestBodies#fileOnly}.
 *
 * <p>The indexes are {@code long}s, relative to the start of the body, from {@code 0} to {@linkplain #length()}.
 */
abstract sealed class PayloadBytes implements AutoCloseable {

  /**
   * The bytes of a body.
   *
   * @param body the body of a request; {@code null} for an empty one.
   * @return the bytes, which the caller closes.
   */
  static PayloadBytes of(ByteBuf body) {
    if (body == null) {
      return new BufferBytes(Unpooled.EMPTY_BUFFER);
    }
    return RequestBodies.fileOnly(body)
        .<PayloadBytes>map(file -> new FileBytes(file, RequestBodies.length(body)))
        .orElseGet(() -> new BufferBytes(body));
  }

  abstract long length();

  abstract byte getByte(long index);

  /**
   * Find a byte.
   *
   * @return the index of the first {@code value} from {@code from} (inclusive) to {@code to} (exclusive); {@code -1}
   *     if there is none.
   */
  abstract long indexOf(long from, long to, byte value);

  abstract String toString(long index, int count, Charset charset);

  /**
   * Update a digest with the bytes of a region, without copying them if they are in memory.
   */
  abstract void digest(MessageDigest digest, long index, long count);

  @Override
  public void close() {
  }

  /**
   * The readable bytes of a {@code ByteBuf}, which are never more than {@linkplain Integer#MAX_VALUE}.
   */
  static final class BufferBytes extends PayloadBytes {

    private final ByteBuf buffer;

    private final int start;

    BufferBytes(ByteBuf buffer) {
      this.buffer = buffer;
      this.start = buffer.readerIndex();
    }

    @Override
    long length() {
      return buffer.readableBytes();
    }

    @Override
    byte getByte(long index) {
      return buffer.getByte(start + Math.toIntExact(index));
    }

    @Override
    long indexOf(long from, long to, byte value) {
      int found = buffer.indexOf(start + Math.toIntExact(from), start + Math.toIntExact(to), value);
      return found < 0 ? -1 : found - start;
    }

    @Override
    String toString(long index, int count, Charset charset) {
      return buffer.toString(start + Math.toIntExact(index), count, charset);
    }

    @Override
    void digest(MessageDigest digest, long index, long count) {
      if (count > 0) {
        for (ByteBuffer nioBuffer : buffer.nioBuffers(start + Math.toIntExact(index), Math.toIntExact(count))) {
          digest.update(nioBuffer);
        }
      }
    }
  }

  /**
   * The bytes of a file, read through a window that holds the bytes around the last one read, so that the small reads
   * of a chunk header don't read the file byte by byte.
   */
  static final class FileBytes extends PayloadBytes {

    private static final int WINDOW_SIZE = 64 * 1024;

    private static final int DIGEST_BUFFER_SIZE = 1024 * 1024;

    private final Path file;

    private final long length;

    private final FileChannel channel;

    private final ByteBuffer window = ByteBuffer.allocate(WINDOW_SIZE);

    /**
     * The index of the first byte of the window in the file.
     */
    private long windowStart;

    private ByteBuffer digestBuffer;

    FileBytes(Path file, long length) {
      this.file = file;
      this.length = length;
      try {
        this.channel = FileChannel.open(file, StandardOpenOption.READ);
      } catch (IOException e) {
        throw new UncheckedIOException("Failed to open the request body file " + file + ".", e);
      }
      window.limit(0);
    }

    @Override
    long length() {
      return length;
    }

    @Override
    byte getByte(long index) {
      checkIndex(index, 1);
      loadWindow(index);
      return window.get(Math.toIntExact(index - windowStart));
    }

    @Override
    long indexOf(long from, long to, byte value) {
      long end = Math.min(to, length);
      for (long index = Math.max(from, 0); index < end; ) {
        loadWindow(index);
        byte[] bytes = window.array();
        int offset = Math.toIntExact(index - windowStart);
        int limit = (int) Math.min(window.limit(), end - windowStart);
        for (int i = offset; i < limit; i++) {
          if (bytes[i] == value) {
            return windowStart + i;
          }
        }
        index = windowStart + limit;
      }
      return -1;
    }

    @Override
    String toString(long index, int count, Charset charset) {
      checkIndex(index, count);
      byte[] bytes = new byte[count];
      readFully(ByteBuffer.wrap(bytes), index);
      return new String(bytes, charset);
    }

    @Override
    void digest(MessageDigest digest, long index, long count) {
      checkIndex(index, count);
      if (digestBuffer == null) {
        digestBuffer = ByteBuffer.allocate(DIGEST_BUFFER_SIZE);
      }
      long position = index;
      long remaining = count;
      while (remaining > 0) {
        digestBuffer.clear().limit((int) Math.min(DIGEST_BUFFER_SIZE, remaining));
        readFully(digestBuffer, position);
        digestBuffer.flip();
        position += digestBuffer.remaining();
        remaining -= digestBuffer.remaining();
        digest.update(digestBuffer);
      }
    }

    @Override
    public void close() {
      try {
        channel.close();
      } catch (IOException e) {
        throw new UncheckedIOException("Failed to close the request body file " + file + ".", e);
      }
    }

    /**
     * Make the window hold the byte at {@code index}, which is within the file.
     */
    private void loadWindow(long index) {
      if (index >= windowStart && index < windowStart + window.limit()) {
        return;
      }
      window.clear().limit((int) Math.min(WINDOW_SIZE, length - index));
      readFully(window, index);
      window.flip();
      windowStart = index;
    }

    private void readFully(ByteBuffer buffer, long position) {
      try {
        long at = position;
        while (buffer.hasRemaining()) {
          int read = channel.read(buffer, at);
          if (read < 0) {
            throw new IOException("The request body file " + file + " ended before " + length + " bytes.");
          }
          at += read;
        }
      } catch (IOException e) {
        throw new UncheckedIOException("Failed to read the request body file " + file + ".", e);
      }
    }

    private void checkIndex(long index, long count) {
      if (index < 0 || count < 0 || index > length - count) {
        throw new IndexOutOfBoundsException("index: " + index + ", count: " + count + ", length: " + length);
      }
    }
  }

}
