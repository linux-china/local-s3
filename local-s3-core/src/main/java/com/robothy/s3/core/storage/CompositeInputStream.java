package com.robothy.s3.core.storage;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;

/**
 * The content of an object that is stored in several parts, e.g. an object completed from a multipart upload, read
 * as one stream: the streams of the parts are read one after another.
 *
 * <p>The streams are opened by the caller before the stream is handed out, i.e. while the lock that the object was
 * resolved under is held, so that an overwrite or a deletion of the object that deletes its parts afterwards doesn't
 * cut the content short. Besides the ordinary stream API, the streams of the parts are exposed, so that a transport
 * can transfer each of them the way it supports, e.g. a {@linkplain FileRegionInputStream} without copying it through
 * the Java heap.
 *
 * <p>The stream owns the streams of its parts. A caller that takes them over with {@linkplain #getStreams()} closes
 * them instead of this stream.
 */
public final class CompositeInputStream extends InputStream {

  private final List<InputStream> streams;

  private int index;

  /**
   * Create a stream that reads {@code streams} one after another.
   *
   * @param streams the open streams of the parts, in the order of the content.
   */
  public CompositeInputStream(List<InputStream> streams) {
    this.streams = List.copyOf(streams);
  }

  /**
   * The streams of the parts, in the order of the content. A caller that reads the parts from them must not read
   * this stream.
   *
   * @return the streams of the parts.
   */
  public List<InputStream> getStreams() {
    return streams;
  }

  @Override
  public int read() throws IOException {
    while (index < streams.size()) {
      int value = streams.get(index).read();
      if (value >= 0) {
        return value;
      }
      index++;
    }
    return -1;
  }

  @Override
  public int read(byte[] bytes, int offset, int length) throws IOException {
    Objects.checkFromIndexSize(offset, length, bytes.length);
    if (length == 0) {
      return 0;
    }
    while (index < streams.size()) {
      int read = streams.get(index).read(bytes, offset, length);
      if (read > 0) {
        return read;
      }
      if (read < 0) {
        index++;
      }
    }
    return -1;
  }

  @Override
  public long skip(long count) throws IOException {
    long skipped = 0;
    while (skipped < count && index < streams.size()) {
      long step = streams.get(index).skip(count - skipped);
      if (step > 0) {
        skipped += step;
      } else if (streams.get(index).read() < 0) {
        // skip() may skip nothing before the end of a stream; a read tells whether the stream is exhausted.
        index++;
      } else {
        skipped++;
      }
    }
    return skipped;
  }

  @Override
  public int available() throws IOException {
    return index < streams.size() ? streams.get(index).available() : 0;
  }

  @Override
  public void close() throws IOException {
    IOException failure = null;
    for (InputStream stream : streams) {
      try {
        stream.close();
      } catch (IOException e) {
        if (failure == null) {
          failure = e;
        } else {
          failure.addSuppressed(e);
        }
      }
    }
    index = streams.size();
    if (failure != null) {
      throw failure;
    }
  }

}
