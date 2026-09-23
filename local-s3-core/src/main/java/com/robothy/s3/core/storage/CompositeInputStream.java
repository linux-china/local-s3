package com.robothy.s3.core.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Objects;

/**
 * The content of an object that is stored in several parts, e.g. an object completed from a multipart upload, read
 * as one stream: the content of the parts is read one after another.
 *
 * <p>A part is opened when it is read, and closed once it is exhausted, so that reading a whole object holds one
 * file open rather than one per part: an object of hundreds of parts, which Spark or DuckDB read many of at a time,
 * would otherwise run the process out of file descriptors. So that a part that isn't open yet is still there when it
 * is read, the content of the parts is {@linkplain Storage#retain(java.util.Collection) retained} while the object is
 * resolved, i.e. under the lock of its bucket, and the retention is released when this stream is closed. An overwrite
 * or a deletion that deletes the parts meanwhile doesn't cut the content short: their content is deleted once the
 * last reader of it is done.
 *
 * <p>Besides the ordinary stream API, the parts are exposed, so that a transport can transfer each of them the way
 * it supports, e.g. a {@linkplain FileRegionInputStream} without copying it through the Java heap. A caller that
 * {@linkplain #openPart(int) opens} a part owns and closes its stream, and must not read this stream anymore; it
 * closes this stream as well, which releases the retention of the content.
 */
public final class CompositeInputStream extends InputStream {

  /**
   * A part of the content, which is opened when it is read.
   */
  @FunctionalInterface
  public interface Part {

    /**
     * Open the content of this part. Called once per part.
     *
     * @return the content of the part, which the caller closes.
     */
    InputStream open();

    /**
     * Close the content of this part if it holds it open and it wasn't opened, e.g. a part of a stream that was
     * created over open streams. Does nothing for a part that opens its content when it is read.
     */
    default void discard() {
    }
  }

  private final List<Part> parts;

  private final ContentRetention retention;

  /**
   * The index of the part that {@linkplain #current} holds, or of the next part to open.
   */
  private int index;

  /**
   * The open stream of the part at {@linkplain #index}; {@code null} if it isn't open, or was exhausted.
   */
  private InputStream current;

  private boolean closed;

  /**
   * Create a stream that reads {@code streams} one after another. The streams are open already, so the content needs
   * no retention; this stream owns them and closes them.
   *
   * @param streams the open streams of the parts, in the order of the content.
   */
  public CompositeInputStream(List<InputStream> streams) {
    this(streams.stream().map(OpenPart::new).map(Part.class::cast).toList(), ContentRetention.NONE);
  }

  /**
   * Create a stream that reads the parts one after another, opening each of them when it is read.
   *
   * @param parts the parts, in the order of the content.
   * @param retention the retention of the content of the parts, released when this stream is closed.
   */
  public CompositeInputStream(List<Part> parts, ContentRetention retention) {
    this.parts = List.copyOf(parts);
    this.retention = Objects.requireNonNull(retention);
  }

  /**
   * The number of parts of the content.
   *
   * @return the number of parts.
   */
  public int partCount() {
    return parts.size();
  }

  /**
   * Open a part of the content, so that a transport can transfer it the way it supports. The caller owns the
   * returned stream and closes it, and must not read this stream anymore; it closes this stream as well, once it is
   * done with every part it opened.
   *
   * @param partIndex the index of the part, counted from 0 in the order of the content.
   * @return the content of the part.
   */
  public InputStream openPart(int partIndex) {
    return parts.get(partIndex).open();
  }

  @Override
  public int read() throws IOException {
    while (true) {
      InputStream stream = currentStream();
      if (stream == null) {
        return -1;
      }
      int value = stream.read();
      if (value >= 0) {
        return value;
      }
      advance();
    }
  }

  @Override
  public int read(byte[] bytes, int offset, int length) throws IOException {
    Objects.checkFromIndexSize(offset, length, bytes.length);
    if (length == 0) {
      return 0;
    }
    while (true) {
      InputStream stream = currentStream();
      if (stream == null) {
        return -1;
      }
      int read = stream.read(bytes, offset, length);
      if (read > 0) {
        return read;
      }
      if (read < 0) {
        advance();
      }
    }
  }

  @Override
  public long skip(long count) throws IOException {
    long skipped = 0;
    while (skipped < count) {
      InputStream stream = currentStream();
      if (stream == null) {
        break;
      }
      long step = stream.skip(count - skipped);
      if (step > 0) {
        skipped += step;
      } else if (stream.read() < 0) {
        // skip() may skip nothing before the end of a stream; a read tells whether the stream is exhausted.
        advance();
      } else {
        skipped++;
      }
    }
    return skipped;
  }

  @Override
  public int available() throws IOException {
    InputStream stream = currentStream();
    return stream == null ? 0 : stream.available();
  }

  /**
   * Close the part that is open, if any, and release the retention of the content of the parts. The parts that
   * {@linkplain #openPart(int)} handed out are owned by their caller and aren't closed here.
   */
  @Override
  public void close() throws IOException {
    if (closed) {
      return;
    }
    closed = true;
    IOException failure = null;
    try {
      if (current != null) {
        current.close();
      }
    } catch (IOException e) {
      failure = e;
    } finally {
      current = null;
      index = parts.size();
    }
    // Streams that were handed to this one but never read are closed as well.
    for (Part part : parts) {
      try {
        part.discard();
      } catch (RuntimeException e) {
        if (failure == null) {
          failure = new IOException("Failed to close a part of the content.", e);
        } else {
          failure.addSuppressed(e);
        }
      }
    }
    retention.close();
    if (failure != null) {
      throw failure;
    }
  }

  /**
   * The stream of the part that is being read, opening it if it isn't open yet; {@code null} at the end of the
   * content.
   */
  private InputStream currentStream() {
    if (current == null && index < parts.size()) {
      current = parts.get(index).open();
    }
    return current;
  }

  /**
   * Close the part that is exhausted and go on with the next one.
   */
  private void advance() throws IOException {
    InputStream exhausted = current;
    current = null;
    index++;
    if (exhausted != null) {
      exhausted.close();
    }
  }

  /**
   * A part whose content is open already, which is handed out or closed once.
   */
  private static final class OpenPart implements Part {

    private InputStream stream;

    private OpenPart(InputStream stream) {
      this.stream = Objects.requireNonNull(stream);
    }

    @Override
    public InputStream open() {
      InputStream open = stream;
      if (open == null) {
        throw new IllegalStateException("The part was opened already.");
      }
      stream = null;
      return open;
    }

    @Override
    public void discard() {
      InputStream open = stream;
      stream = null;
      if (open != null) {
        try {
          open.close();
        } catch (IOException e) {
          throw new UncheckedIOException("Failed to close a part of the content.", e);
        }
      }
    }
  }

}
