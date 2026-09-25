package com.robothy.s3.rest.netty;

import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.buffer.UnpooledHeapByteBuf;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The body of a request that is too large to be memory-mapped, i.e. larger than {@linkplain Integer#MAX_VALUE} bytes,
 * which is the most a {@linkplain java.nio.MappedByteBuffer}, or any {@linkplain io.netty.buffer.ByteBuf}, can hold.
 *
 * <p>The content is only in the file: the buffer itself has no readable bytes, and stands for the body where a request
 * carries a {@code ByteBuf}. It is read through {@linkplain RequestBodies#inputStream} and
 * {@linkplain RequestBodies#length}, and handed over to a storage through {@linkplain RequestBodies#file}, like the
 * file of a {@linkplain MappedFileByteBuf}. Releasing the buffer deletes the file, unless it was taken over.
 */
final class FileBodyByteBuf extends UnpooledHeapByteBuf {

  private static final Logger log = LoggerFactory.getLogger(FileBodyByteBuf.class);

  private final Path file;

  private final long length;

  /**
   * Create a body of the content of a file.
   *
   * @param file the file, which holds exactly the body, and is owned by the returned buffer.
   * @param length the number of bytes of the file.
   */
  FileBodyByteBuf(Path file, long length) {
    super(UnpooledByteBufAllocator.DEFAULT, 0, 0);
    if (length < 0) {
      throw new IllegalArgumentException("length must not be negative.");
    }
    this.file = file;
    this.length = length;
  }

  /**
   * The file that holds the body, unless it was taken over, e.g. renamed, in which case it doesn't exist anymore.
   *
   * @return the file.
   */
  Path file() {
    return file;
  }

  /**
   * The length of the body.
   *
   * @return the number of bytes of the body.
   */
  long length() {
    return length;
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

  @Override
  protected void deallocate() {
    super.deallocate();
    try {
      Files.deleteIfExists(file);
    } catch (IOException e) {
      log.warn("Failed to delete temporary request body file {}.", file, e);
      file.toFile().deleteOnExit();
    }
  }

}
