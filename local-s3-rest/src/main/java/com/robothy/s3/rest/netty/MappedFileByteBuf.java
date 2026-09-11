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
 * <p>Releasing the buffer unmaps the file right away, rather than when the buffer is garbage collected,
 * which matters when the temporary directory is a {@code tmpfs}. The buffer must not be used afterwards.
 */
final class MappedFileByteBuf extends UnpooledDirectByteBuf {

  private static final Logger log = LoggerFactory.getLogger(MappedFileByteBuf.class);

  private final MappedByteBuffer mapped;

  /**
   * The file to delete on release; {@code null} if it was deleted while mapped.
   */
  private final Path file;

  /**
   * Map the first {@code size} bytes of {@code file} and delete the file, unless the platform doesn't
   * allow deleting a mapped file; then the file is deleted when the buffer is released.
   *
   * @param channel a channel of {@code file}, opened for reading and writing.
   * @param file the temporary file, owned by the returned buffer.
   * @param size number of bytes to map.
   * @return a buffer whose readable bytes are the mapped bytes.
   * @throws IOException if the file cannot be mapped.
   */
  static MappedFileByteBuf map(FileChannel channel, Path file, long size) throws IOException {
    MappedByteBuffer mapped = channel.map(FileChannel.MapMode.READ_WRITE, 0, size);
    Path undeleted = file;
    try {
      Files.delete(file);
      undeleted = null;
    } catch (IOException e) {
      // Windows doesn't delete a file while it is mapped.
    }
    return new MappedFileByteBuf(mapped, undeleted);
  }

  private MappedFileByteBuf(MappedByteBuffer mapped, Path file) {
    super(UnpooledByteBufAllocator.DEFAULT, mapped, mapped.capacity());
    this.mapped = mapped;
    this.file = file;
  }

  @Override
  protected void deallocate() {
    super.deallocate();
    freeDirect(mapped);
    if (file != null) {
      try {
        Files.deleteIfExists(file);
      } catch (IOException e) {
        log.warn("Failed to delete temporary request body file {}.", file, e);
        file.toFile().deleteOnExit();
      }
    }
  }

}
