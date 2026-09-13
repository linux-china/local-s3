package com.robothy.s3.rest.netty;

import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.buffer.UnpooledDirectByteBuf;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
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

  private final MappedByteBuffer mapped;

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
    return new MappedFileByteBuf(channel.map(FileChannel.MapMode.READ_WRITE, 0, size), file);
  }

  private MappedFileByteBuf(MappedByteBuffer mapped, Path file) {
    super(UnpooledByteBufAllocator.DEFAULT, mapped, mapped.capacity());
    this.mapped = mapped;
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

  @Override
  protected void deallocate() {
    super.deallocate();
    freeDirect(mapped);
    try {
      Files.deleteIfExists(file);
    } catch (IOException e) {
      log.warn("Failed to delete temporary request body file {}.", file, e);
      file.toFile().deleteOnExit();
    }
  }

}
