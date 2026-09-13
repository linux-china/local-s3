package com.robothy.s3.core.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/**
 * A bounded input stream over a region of a file. Besides the ordinary stream API, it exposes the
 * open channel and its region so transports that support file-region transfer can avoid copying the
 * content through Java heap.
 *
 * <p>The stream owns the channel. A caller that transfers the channel to another owner must ensure
 * that the channel is closed after the transfer.
 */
public final class FileRegionInputStream extends InputStream {

  private final FileChannel channel;

  private final long position;

  private final long count;

  private long remaining;

  private final ByteBuffer singleByte = ByteBuffer.allocate(1);

  private FileRegionInputStream(FileChannel channel, long position, long count) throws IOException {
    this.channel = channel;
    this.position = position;
    this.count = count;
    this.remaining = count;
    channel.position(position);
  }

  static FileRegionInputStream open(Path path) throws IOException {
    FileChannel channel = FileChannel.open(path, StandardOpenOption.READ);
    try {
      return new FileRegionInputStream(channel, 0, channel.size());
    } catch (Throwable e) {
      closeAfterFailure(channel, e);
      throw e;
    }
  }

  static FileRegionInputStream open(Path path, long position, long count) throws IOException {
    FileChannel channel = FileChannel.open(path, StandardOpenOption.READ);
    try {
      long size = channel.size();
      if (position < 0 || count < 0 || position > size || count > size - position) {
        throw new IllegalArgumentException("Invalid file region: position=" + position + ", count=" + count + ".");
      }
      return new FileRegionInputStream(channel, position, count);
    } catch (Throwable e) {
      closeAfterFailure(channel, e);
      throw e;
    }
  }

  /**
   * The open channel that backs this stream.
   */
  public FileChannel getChannel() {
    return channel;
  }

  /**
   * The absolute position of this region in the file.
   */
  public long getPosition() {
    return position;
  }

  /**
   * The number of bytes in this region.
   */
  public long getCount() {
    return count;
  }

  @Override
  public int read() throws IOException {
    if (remaining == 0) {
      return -1;
    }

    singleByte.clear();
    int read = channel.read(singleByte);
    if (read < 0) {
      remaining = 0;
      return -1;
    }
    remaining--;
    return Byte.toUnsignedInt(singleByte.get(0));
  }

  @Override
  public int read(byte[] bytes, int offset, int length) throws IOException {
    Objects.checkFromIndexSize(offset, length, bytes.length);
    if (length == 0) {
      return 0;
    }
    if (remaining == 0) {
      return -1;
    }

    int read = channel.read(ByteBuffer.wrap(bytes, offset, (int) Math.min(length, remaining)));
    if (read < 0) {
      remaining = 0;
      return -1;
    }
    remaining -= read;
    return read;
  }

  @Override
  public long skip(long count) throws IOException {
    long skipped = Math.min(Math.max(count, 0), remaining);
    channel.position(channel.position() + skipped);
    remaining -= skipped;
    return skipped;
  }

  @Override
  public int available() {
    return (int) Math.min(remaining, Integer.MAX_VALUE);
  }

  @Override
  public void close() throws IOException {
    channel.close();
  }

  private static void closeAfterFailure(FileChannel channel, Throwable cause) {
    try {
      channel.close();
    } catch (IOException e) {
      cause.addSuppressed(e);
    }
  }
}
