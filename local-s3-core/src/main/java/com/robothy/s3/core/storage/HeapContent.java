package com.robothy.s3.core.storage;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Content received into the heap, e.g. a large request body of an {@code IN_MEMORY} service, whose space is reserved
 * in the budget of the in-memory storage that it is received for. That storage takes the content over when it is
 * stored, rather than copying it, so that storing a large upload neither writes it to a temporary file nor holds it
 * twice. Another storage, e.g. the one of a service that was reset meanwhile, copies it like any other stream.
 *
 * <p>The content is created by a {@linkplain Writer}, see {@linkplain Storage#newHeapContentWriter(long)}. Whoever holds
 * it {@linkplain #release() releases} it when done, which gives its space back unless a storage took it over.
 */
public final class HeapContent {

  private final InMemoryStorage owner;

  private final byte[][] chunks;

  private final long length;

  /**
   * Whether the content was taken over by its storage, or released; its space is given back or kept exactly once.
   */
  private final AtomicBoolean claimed = new AtomicBoolean();

  private HeapContent(InMemoryStorage owner, byte[][] chunks, long length) {
    this.owner = owner;
    this.chunks = chunks;
    this.length = length;
  }

  /**
   * The number of bytes of the content.
   *
   * @return the length.
   */
  public long length() {
    return length;
  }

  /**
   * A stream of the content. The content is never changed, so the stream reads it in place, also after a storage took
   * it over.
   *
   * @return a new stream of the content.
   */
  public InputStream newInputStream() {
    return new InMemoryStorage.ChunksInputStream(chunks, 0, length);
  }

  /**
   * Read-only views of the chunks of the content, in order, e.g. to read it as a request body in place.
   *
   * @return the views.
   */
  public List<ByteBuffer> buffers() {
    List<ByteBuffer> buffers = new ArrayList<>(chunks.length);
    for (byte[] chunk : chunks) {
      buffers.add(ByteBuffer.wrap(chunk).asReadOnlyBuffer());
    }
    return buffers;
  }

  /**
   * Give the space of the content back to the storage it was reserved in, unless the storage took the content over.
   * Idempotent.
   */
  public void release() {
    if (claimed.compareAndSet(false, true)) {
      owner.release(length);
    }
  }

  /**
   * Take the content over, with its reserved space, if it was reserved in {@code storage} and is neither taken over nor
   * released yet.
   *
   * @return the chunks of the content; {@code null} if {@code storage} has to copy it.
   */
  byte[][] takeOver(InMemoryStorage storage) {
    return storage == owner && claimed.compareAndSet(false, true) ? chunks : null;
  }

  /**
   * Receives content into chunks of the heap. The expected length is reserved when the writer is created, so that a
   * body that can't fit is refused before it is received, and concurrent writers never hold more than the budget
   * together; more bytes than expected are reserved as they come. A writer is used by one thread at a time.
   */
  public static final class Writer {

    private static final int MIN_EXTRA_CHUNK_SIZE = 8192;

    private final InMemoryStorage owner;

    private final int chunkSize;

    private final List<byte[]> chunks = new ArrayList<>();

    /**
     * The bytes reserved in the budget of the owner, which the allocated chunks never exceed.
     */
    private long reserved;

    /**
     * The bytes that the allocated chunks hold.
     */
    private long allocated;

    private byte[] current;

    private int position;

    private long written;

    private boolean done;

    Writer(InMemoryStorage owner, long expectedLength, int chunkSize) {
      if (expectedLength < 0) {
        throw new IllegalArgumentException("expectedLength must not be negative.");
      }
      this.owner = Objects.requireNonNull(owner);
      this.chunkSize = chunkSize;
      owner.reserve(expectedLength);
      this.reserved = expectedLength;
    }

    /**
     * Append the remaining bytes of a buffer, and consume them.
     *
     * @param source the bytes.
     * @throws com.robothy.s3.core.exception.TotalSizeExceedException if more bytes than expected don't fit the budget.
     */
    public void write(ByteBuffer source) {
      if (done) {
        throw new IllegalStateException("The heap content is finished or discarded.");
      }
      while (source.hasRemaining()) {
        if (current == null || position == current.length) {
          allocate(source.remaining());
        }
        int count = Math.min(source.remaining(), current.length - position);
        source.get(current, position, count);
        position += count;
        written += count;
      }
    }

    private void allocate(int wanted) {
      long size = Math.min(chunkSize, reserved - allocated);
      if (size <= 0) {
        // More bytes than expected: reserve them as they come.
        size = Math.min(chunkSize, Math.max(MIN_EXTRA_CHUNK_SIZE, wanted));
        owner.reserve(size);
        reserved += size;
      }
      current = new byte[(int) size];
      position = 0;
      allocated += size;
      chunks.add(current);
    }

    /**
     * Finish the content: the space reserved beyond the bytes written is given back.
     *
     * @return the content, which the caller owns.
     */
    public HeapContent finish() {
      if (done) {
        throw new IllegalStateException("The heap content is finished or discarded.");
      }
      done = true;
      if (current != null && position < current.length) {
        // Fewer bytes than expected; the chunks of a content hold exactly its bytes. A chunk is only allocated for a
        // byte to write, so the last one isn't empty.
        chunks.set(chunks.size() - 1, Arrays.copyOf(current, position));
      }
      owner.release(reserved - written);
      byte[][] content = chunks.toArray(byte[][]::new);
      chunks.clear();
      current = null;
      return new HeapContent(owner, content, written);
    }

    /**
     * Give the content up, and its reserved space back. Idempotent, and a no-op once finished.
     */
    public void discard() {
      if (done) {
        return;
      }
      done = true;
      chunks.clear();
      current = null;
      owner.release(reserved);
    }

  }

}
