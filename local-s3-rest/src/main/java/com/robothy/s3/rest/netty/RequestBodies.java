package com.robothy.s3.rest.netty;

import io.netty.buffer.ByteBuf;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Access to how {@linkplain LocalS3HttpRequestDecoder} buffered the body of a request.
 */
public final class RequestBodies {

  private RequestBodies() {
  }

  /**
   * The temporary file that a large request body was buffered in. The file holds exactly the bytes of the body, and a
   * handler may hand it over to a storage that takes it over, e.g. renames it, instead of copying the body. The body
   * stays readable while the request is handled, and the file is deleted when the body is released, unless it was
   * taken over.
   *
   * @param body the body of a request.
   * @return the file of the body; empty if the body was buffered on the heap.
   */
  public static Optional<Path> file(ByteBuf body) {
    return body instanceof MappedFileByteBuf mapped
        && mapped.readerIndex() == 0 && mapped.readableBytes() == mapped.capacity()
        ? Optional.of(mapped.file())
        : Optional.empty();
  }

}
