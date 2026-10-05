package com.robothy.s3.rest.netty;

import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.buffer.UnpooledDirectByteBuf;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A buffer memory-mapped from a temporary file, so that its content takes no Java heap.
 *
 * <p>The file is kept while the buffer is alive, so that a handler can hand it over to a storage, which may rename
 * it into place instead of writing its content again; see {@linkplain RequestBodies#file}. Releasing the buffer
 * unmaps the file right away, rather than when the buffer is garbage collected, which matters when the temporary
 * directory is a {@code tmpfs}, and deletes the file unless it was taken over. The buffer must not be used afterwards.
 */
final class MappedFileByteBuf extends UnpooledDirectByteBuf {

  private static final Logger log = LoggerFactory.getLogger(MappedFileByteBuf.class);

  /**
   * The arena that the mapping belongs to; closing it unmaps the file.
   */
  private final Arena arena;

  /**
   * The file that the buffer is mapped from, which is deleted on release if it still exists.
   */
  private final Path file;

  /**
   * Map the first {@code size} bytes of {@code file}.
   *
   * @param channel a channel of {@code file}, opened for reading and writing.
   * @param file the temporary file, owned by the returned buffer.
   * @param size number of bytes to map.
   * @return a buffer whose readable bytes are the mapped bytes.
   * @throws IOException if the file cannot be mapped.
   */
  static MappedFileByteBuf map(FileChannel channel, Path file, long size) throws IOException {
    // Shared, as the buffer is released on another thread than the one that maps it, e.g. an event loop.
    Arena arena = Arena.ofShared();
    try {
      return new MappedFileByteBuf(channel.map(FileChannel.MapMode.READ_WRITE, 0, size, arena).asByteBuffer(), arena,
          file);
    } catch (IOException | RuntimeException e) {
      arena.close();
      throw e;
    }
  }

  private MappedFileByteBuf(ByteBuffer mapped, Arena arena, Path file) {
    super(UnpooledByteBufAllocator.DEFAULT, mapped, mapped.capacity());
    this.arena = arena;
    this.file = file;
  }

  /**
   * The file that the buffer is mapped from. It holds exactly the readable bytes of the buffer, unless it was taken
   * over, e.g. renamed, in which case it doesn't exist anymore.
   *
   * @return the file.
   */
  Path file() {
    return file;
  }

  /**
   * The trailing headers of the {@code aws-chunked} body that was decoded into the file, which then holds the decoded
   * content; {@code null} if the file holds the body as it was received.
   */
  private volatile Map<String, String> awsChunkedTrailer;

  Map<String, String> awsChunkedTrailer() {
    return awsChunkedTrailer;
  }

  void awsChunkedTrailer(Map<String, String> trailer) {
    this.awsChunkedTrailer = trailer;
  }

  /**
   * Leave the mapping to {@linkplain #unmap()}: the arena owns it, and Netty can only free the buffers it allocated.
   */
  @Override
  protected void freeDirect(ByteBuffer buffer) {
  }

  @Override
  protected void deallocate() {
    super.deallocate();
    unmap();
    try {
      Files.deleteIfExists(file);
    } catch (IOException e) {
      log.warn("Failed to delete temporary request body file {}.", file, e);
      file.toFile().deleteOnExit();
    }
  }

  /**
   * Unmap the file right away; accessing the buffer afterwards throws instead of reading unmapped memory. Closing fails
   * only while another thread accesses the buffer, which a released buffer must not be; the file then stays mapped until
   * the process exits, which must not fail the release of the request body either.
   */
  private void unmap() {
    try {
      arena.close();
    } catch (RuntimeException e) {
      log.warn("Failed to unmap a request body buffer, it is unmapped when garbage collected.", e);
    }
  }

}
