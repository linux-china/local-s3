package com.robothy.s3.core.storage;

import com.robothy.s3.core.exception.TotalSizeExceedException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@linkplain Storage} implementation based on Java Heap.
 *
 * <p>The content of an object is kept in chunks of at most {@linkplain #DEFAULT_CHUNK_SIZE} bytes rather than in a
 * single array, so that an object may be larger than the largest array of the JVM, i.e. about 2 GiB, and storing a
 * large object doesn't need a contiguous block of heap as large as the object.
 *
 * <p>The total size of the content is limited. The space of the content is reserved atomically before it is stored,
 * chunk by chunk while a stream is read, and released if storing fails, so that concurrent uploads never store more than
 * the limit together.
 */
class InMemoryStorage implements Storage {

  /**
   * The max size of a chunk of the content of an object.
   */
  static final int DEFAULT_CHUNK_SIZE = 16 * 1024 * 1024;

  /**
   * The size of the first buffer that a stream is read into when the stream doesn't tell how much it holds.
   */
  private static final int INITIAL_BUFFER_SIZE = 8192;

  private final Map<Long, Content> store = new ConcurrentHashMap<>();

  /**
   * The content that readers hold, and the deletions that wait for them. A stream of this storage keeps reading the
   * chunks it was opened on, but content that a reader hasn't opened yet has to stay in the store.
   */
  private final DeferredDeletions deletions = new DeferredDeletions(this::deleteNow);

  /**
   * The bytes of the stored content, and of the content being stored, which is reserved before it is stored.
   */
  private final AtomicLong totalSize = new AtomicLong(0);

  private final long maxTotalSize;

  private final int chunkSize;

  /**
   * Create an {@linkplain InMemoryStorage} instance with total size limitation.
   *
   * @param maxTotalSize max total size.
   */
  InMemoryStorage(long maxTotalSize) {
    this(maxTotalSize, DEFAULT_CHUNK_SIZE);
  }

  /**
   * Create an {@linkplain InMemoryStorage} instance without total size limitation.
   */
  InMemoryStorage() {
    this(Long.MAX_VALUE, DEFAULT_CHUNK_SIZE);
  }

  /**
   * Create an {@linkplain InMemoryStorage} instance. For tests, which exercise the chunk boundaries with small chunks.
   *
   * @param maxTotalSize max total size.
   * @param chunkSize max size of a chunk of the content of an object.
   */
  InMemoryStorage(long maxTotalSize, int chunkSize) {
    if (chunkSize <= 0) {
      throw new IllegalArgumentException("chunkSize must be positive.");
    }
    this.maxTotalSize = maxTotalSize;
    this.chunkSize = chunkSize;
  }

  @Override
  public Long put(Long id, byte[] data) {
    reserve(data.length);
    try {
      // Copy the data, since the caller may change the array afterwards.
      List<byte[]> chunks = new ArrayList<>();
      for (int offset = 0; offset < data.length; offset += chunkSize) {
        chunks.add(Arrays.copyOfRange(data, offset, Math.min(data.length, offset + chunkSize)));
      }
      return putContent(id, new Content(chunks.toArray(byte[][]::new), data.length));
    } catch (RuntimeException | Error e) {
      release(data.length);
      throw e;
    }
  }

  @Override
  public Long put(Long id, InputStream data) {
    // The bytes read so far, which are reserved.
    long length = 0;
    try {
      List<byte[]> chunks = new ArrayList<>();
      byte[] chunk;
      while ((chunk = readChunk(data)) != null) {
        reserve(chunk.length);
        length += chunk.length;
        chunks.add(chunk);
      }
      return putContent(id, new Content(chunks.toArray(byte[][]::new), length));
    } catch (IOException e) {
      release(length);
      throw new UncheckedIOException("Failed to store object " + id + ".", e);
    } catch (RuntimeException | Error e) {
      release(length);
      throw e;
    }
  }

  /**
   * A writer of content that this storage takes over when it is stored, reserved in its budget.
   *
   * @throws TotalSizeExceedException if the expected length doesn't fit the budget.
   */
  @Override
  public Optional<HeapContent.Writer> newHeapContentWriter(long expectedLength) {
    return Optional.of(new HeapContent.Writer(this, expectedLength, chunkSize));
  }

  /**
   * Store content received into the heap: taken over, with the space reserved for it, if it was received for this
   * storage; otherwise copied.
   */
  @Override
  public Long put(Long id, HeapContent content) {
    byte[][] chunks = content.takeOver(this);
    if (chunks == null) {
      return put(id, content.newInputStream());
    }
    return putContent(id, new Content(chunks, content.length()));
  }

  /**
   * Read the next chunk of a stream. The buffer starts at the size that the stream reports to be available, and
   * grows up to the chunk size while the stream holds more, so that a small object doesn't take a whole chunk.
   *
   * @return the chunk, exactly as long as the bytes read; {@code null} at the end of the stream.
   */
  private byte[] readChunk(InputStream in) throws IOException {
    int capacity = (int) Math.min(chunkSize, Math.max(INITIAL_BUFFER_SIZE, in.available()));
    byte[] buffer = new byte[capacity];
    int filled = 0;
    while (true) {
      int read = in.read(buffer, filled, buffer.length - filled);
      if (read < 0) {
        break;
      }
      filled += read;
      if (filled == buffer.length) {
        if (buffer.length == chunkSize) {
          break;
        }
        buffer = Arrays.copyOf(buffer, (int) Math.min(chunkSize, (long) buffer.length * 2));
      }
    }
    if (filled == 0) {
      return null;
    }
    return filled == buffer.length ? buffer : Arrays.copyOf(buffer, filled);
  }

  /**
   * Store content whose space is reserved. The space of the content it replaces, if any, is released.
   */
  private Long putContent(Long id, Content content) {
    Content previous = store.put(id, content);
    if (previous != null) {
      release(previous.length());
    }
    return id;
  }

  @Override
  public byte[] getBytes(Long id) {
    Content content = getContent(id);
    if (content.length() > Integer.MAX_VALUE - 8) {
      throw new IllegalStateException("Object id='" + id + "' is too large to be read into an array.");
    }
    // Copy the data, since the caller may change the returned array.
    byte[] bytes = new byte[(int) content.length()];
    int offset = 0;
    for (byte[] chunk : content.chunks()) {
      System.arraycopy(chunk, 0, bytes, offset, chunk.length);
      offset += chunk.length;
    }
    return bytes;
  }

  @Override
  public InputStream getInputStream(Long id) {
    Content content = getContent(id);
    // The stream never changes the chunks, so the stored data needn't be copied.
    return new ChunksInputStream(content.chunks(), 0, content.length());
  }

  @Override
  public InputStream getInputStream(Long id, long position, long length) {
    Content content = getContent(id);
    if (position < 0 || length < 0 || position > content.length() || length > content.length() - position) {
      throw new IllegalArgumentException("Invalid region of object id='" + id + "': position=" + position
          + ", length=" + length + ".");
    }
    return new ChunksInputStream(content.chunks(), position, length);
  }

  @Override
  public long size(Long id) {
    return getContent(id).length();
  }

  @Override
  public Optional<ContentRetention> retain(Collection<Long> ids) {
    return Optional.of(deletions.retain(ids));
  }

  @Override
  public Long delete(Long id) {
    if (!store.containsKey(id)) {
      throw notExist(id);
    }
    // Content that a reader still has to open is deleted once it released it.
    return deletions.defer(id) ? id : deleteNow(id);
  }

  private Long deleteNow(Long id) {
    Content removed = store.remove(id);
    if (removed == null) {
      throw notExist(id);
    }
    release(removed.length());
    return id;
  }

  @Override
  public boolean isExist(Long id) {
    return store.containsKey(id);
  }

  private Content getContent(Long id) {
    Content content = store.get(id);
    if (content == null) {
      throw notExist(id);
    }
    return content;
  }

  private static IllegalArgumentException notExist(Long id) {
    return new IllegalArgumentException("Object id='" + id + "' not exists.");
  }

  /**
   * Reserve space for content to be stored, unless the total size would exceed the limit. The check and the reservation
   * are one atomic step, so that concurrent reservations can't exceed the limit together.
   *
   * @param bytes the number of bytes to reserve.
   * @throws TotalSizeExceedException if the total size would exceed the limit; nothing is reserved then.
   */
  void reserve(long bytes) {
    while (true) {
      long current = totalSize.get();
      if (bytes > maxTotalSize - current) {
        throw new TotalSizeExceedException(maxTotalSize, current + bytes);
      }
      if (totalSize.compareAndSet(current, current + bytes)) {
        return;
      }
    }
  }

  /**
   * Release space that {@linkplain #reserve reserved}, e.g. of content that is deleted or failed to be stored.
   */
  void release(long bytes) {
    totalSize.addAndGet(-bytes);
  }

  /**
   * The content of an object.
   *
   * @param chunks the chunks, none of them empty.
   * @param length the total number of bytes of the chunks.
   */
  private record Content(byte[][] chunks, long length) {
  }

  /**
   * A stream of a region of the content of an object.
   */
  static final class ChunksInputStream extends InputStream {

    private final byte[][] chunks;

    private int chunkIndex;

    private int offsetInChunk;

    private long remaining;

    ChunksInputStream(byte[][] chunks, long position, long length) {
      this.chunks = chunks;
      this.remaining = length;
      long skip = position;
      while (chunkIndex < chunks.length && skip >= chunks[chunkIndex].length) {
        skip -= chunks[chunkIndex].length;
        chunkIndex++;
      }
      this.offsetInChunk = (int) skip;
    }

    @Override
    public int read() {
      if (remaining == 0) {
        return -1;
      }
      int value = Byte.toUnsignedInt(chunks[chunkIndex][offsetInChunk]);
      advance(1);
      return value;
    }

    @Override
    public int read(byte[] bytes, int offset, int length) {
      Objects.checkFromIndexSize(offset, length, bytes.length);
      if (length == 0) {
        return 0;
      }
      if (remaining == 0) {
        return -1;
      }
      int count = (int) Math.min(Math.min(length, remaining), chunks[chunkIndex].length - offsetInChunk);
      System.arraycopy(chunks[chunkIndex], offsetInChunk, bytes, offset, count);
      advance(count);
      return count;
    }

    @Override
    public long skip(long count) {
      long skipped = Math.min(Math.max(count, 0), remaining);
      long left = skipped;
      while (left > 0) {
        int step = (int) Math.min(left, chunks[chunkIndex].length - offsetInChunk);
        advance(step);
        left -= step;
      }
      return skipped;
    }

    @Override
    public int available() {
      return (int) Math.min(remaining, Integer.MAX_VALUE);
    }

    private void advance(int count) {
      remaining -= count;
      offsetInChunk += count;
      if (offsetInChunk == chunks[chunkIndex].length) {
        chunkIndex++;
        offsetInChunk = 0;
      }
    }
  }

}
