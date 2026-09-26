package com.robothy.s3.rest.netty;

import com.robothy.s3.core.storage.HeapContent;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Access to how {@linkplain LocalS3HttpRequestDecoder} buffered the body of a request.
 *
 * <p>A body is a {@code ByteBuf}, which holds at most {@linkplain Integer#MAX_VALUE} bytes. A larger body, e.g. of a
 * {@code PutObject} of up to 5 GiB, is only kept in a file, and its {@code ByteBuf} has no readable bytes; see
 * {@linkplain #fileOnly}. So a body is read through {@linkplain #inputStream} and measured with {@linkplain #length},
 * which read both kinds of body, rather than through the {@code ByteBuf}.
 */
public final class RequestBodies {

  private RequestBodies() {
  }

  /**
   * The temporary file that a large request body was buffered in. The file holds exactly the bytes of the body, and a
   * handler may hand it over to a storage that takes it over, e.g. renames it, instead of copying the body. The body
   * stays readable while the request is handled, unless it is only in the file, see {@linkplain #fileOnly}, and the file
   * is deleted when the body is released, unless it was taken over.
   *
   * @param body the body of a request.
   * @return the file of the body; empty if the body was buffered on the heap, or was read already.
   */
  public static Optional<Path> file(ByteBuf body) {
    if (body instanceof FileBodyByteBuf fileBody) {
      return Optional.of(fileBody.file());
    }
    return body instanceof MappedFileByteBuf mapped
        && mapped.readerIndex() == 0 && mapped.readableBytes() == mapped.capacity()
        ? Optional.of(mapped.file())
        : Optional.empty();
  }

  /**
   * The content that a large request body of an {@code IN_MEMORY} service was received into, in the heap rather than in
   * a file. Like a {@linkplain #file file}, a handler may hand it over to the storage it was received for, which takes it
   * over instead of copying the body; the body stays readable, and releasing it gives the space of the content back
   * unless the storage took it over.
   *
   * @param body the body of a request.
   * @return the content of the body; empty if the body wasn't received into the heap, or was read already.
   */
  public static Optional<HeapContent> heapContent(ByteBuf body) {
    return body instanceof HeapBodyByteBuf heap
        && heap.readerIndex() == 0 && heap.readableBytes() == heap.capacity()
        ? Optional.of(heap.content())
        : Optional.empty();
  }

  /**
   * The trailing headers of an {@code aws-chunked} body that was decoded while it was received, into the file it is
   * buffered in, or into the heap. Such a body, and its {@linkplain #file file}, hold the decoded content rather than the chunks that
   * were received, and the signatures of its chunks were verified already.
   *
   * @param body the body of a request.
   * @return the trailing headers by their lower case names, e.g. {@code x-amz-checksum-crc32}; empty if the body holds
   *     the bytes as they were received.
   */
  public static Optional<Map<String, String>> awsChunkedTrailer(ByteBuf body) {
    if (body instanceof FileBodyByteBuf fileBody) {
      return Optional.ofNullable(fileBody.awsChunkedTrailer());
    }
    if (body instanceof HeapBodyByteBuf heap) {
      return Optional.ofNullable(heap.awsChunkedTrailer());
    }
    return body instanceof MappedFileByteBuf mapped ? Optional.ofNullable(mapped.awsChunkedTrailer())
        : Optional.empty();
  }

  /**
   * The file of a body that is too large to be held by a {@code ByteBuf}, i.e. larger than
   * {@linkplain Integer#MAX_VALUE} bytes. Such a body has no readable bytes: its content is only in the file.
   *
   * @param body the body of a request.
   * @return the file that holds the body; empty if the {@code ByteBuf} holds the body.
   */
  public static Optional<Path> fileOnly(ByteBuf body) {
    return body instanceof FileBodyByteBuf fileBody ? Optional.of(fileBody.file()) : Optional.empty();
  }

  /**
   * The length of a body, whether the {@code ByteBuf} holds it or it is only in a file.
   *
   * @param body the body of a request; {@code null} for none.
   * @return the number of readable bytes of the body.
   */
  public static long length(ByteBuf body) {
    if (body == null) {
      return 0;
    }
    return body instanceof FileBodyByteBuf fileBody ? fileBody.length() : body.readableBytes();
  }

  /**
   * A stream of a body, whether the {@code ByteBuf} holds it or it is only in a file. Reading a body that the
   * {@code ByteBuf} holds consumes its readable bytes, like a {@linkplain ByteBufInputStream}. A body that is only in a
   * file is opened on the first read, which must precede the handover of the file to a storage, and is closed at its
   * end.
   *
   * @param body the body of a request.
   * @return a stream of the readable bytes of the body.
   */
  public static InputStream inputStream(ByteBuf body) {
    Objects.requireNonNull(body, "body");
    if (body instanceof FileBodyByteBuf fileBody) {
      return new LazyFileInputStream(fileBody.file());
    }
    return new ByteBufInputStream(body);
  }

  /**
   * Create a body of the content of a file that is only read from the file, like the body of a request that is too
   * large to be held by a {@code ByteBuf}; for tests of the code that reads bodies.
   *
   * @param file the file, which holds exactly the body, and is deleted when the body is released.
   * @return the body.
   * @throws IOException if the size of the file can't be read.
   */
  public static ByteBuf fileBody(Path file) throws IOException {
    return new FileBodyByteBuf(file, Files.size(file));
  }

  /**
   * A stream of a file that is opened on the first read, and closed once its end is read. It is buffered, since some
   * readers of a body read it byte by byte, e.g. {@linkplain com.robothy.s3.rest.utils.AwsChunkedDecodingInputStream}.
   */
  private static final class LazyFileInputStream extends InputStream {

    private static final int BUFFER_SIZE = 256 * 1024;

    private final Path file;

    private InputStream in;

    private boolean closed;

    LazyFileInputStream(Path file) {
      this.file = file;
    }

    @Override
    public int read() throws IOException {
      if (closed) {
        return -1;
      }
      int read = open().read();
      if (read < 0) {
        close();
      }
      return read;
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
      Objects.checkFromIndexSize(offset, length, bytes.length);
      if (closed) {
        return -1;
      }
      if (length == 0) {
        return 0;
      }
      int read = open().read(bytes, offset, length);
      if (read < 0) {
        close();
      }
      return read;
    }

    @Override
    public long skip(long count) throws IOException {
      if (closed || count <= 0) {
        return 0;
      }
      return open().skip(count);
    }

    private InputStream open() throws IOException {
      if (in == null) {
        in = new BufferedInputStream(Files.newInputStream(file), BUFFER_SIZE);
      }
      return in;
    }

    @Override
    public void close() throws IOException {
      closed = true;
      if (in != null) {
        InputStream open = in;
        in = null;
        open.close();
      }
    }
  }

}
