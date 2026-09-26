package com.robothy.s3.rest.netty;

import com.robothy.s3.core.storage.HeapContent;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;

/**
 * A request body that was received into the heap, in the chunks of a {@linkplain HeapContent} whose space is reserved
 * in the in-memory storage of the service, rather than into a temporary file. The storage takes the content over when
 * it is stored, see {@linkplain RequestBodies#heapContent}, so an upload is neither written to the disk nor held twice.
 *
 * <p>The buffer is a read-only view of the chunks, which a storage that took them over keeps reading. Releasing it gives
 * the reserved space back, unless the storage took the content over.
 */
final class HeapBodyByteBuf extends CompositeByteBuf {

  private final HeapContent content;

  /**
   * The trailing headers of the {@code aws-chunked} body that was decoded into the content, which then holds the
   * decoded content; {@code null} if it holds the body as it was received.
   */
  private final Map<String, String> awsChunkedTrailer;

  HeapBodyByteBuf(HeapContent content, Map<String, String> awsChunkedTrailer) {
    super(UnpooledByteBufAllocator.DEFAULT, false, Integer.MAX_VALUE, views(content));
    this.content = content;
    this.awsChunkedTrailer = awsChunkedTrailer;
  }

  private static ByteBuf[] views(HeapContent content) {
    List<ByteBuffer> buffers = content.buffers();
    ByteBuf[] views = new ByteBuf[buffers.size()];
    for (int i = 0; i < views.length; i++) {
      views[i] = Unpooled.wrappedBuffer(buffers.get(i));
    }
    return views;
  }

  HeapContent content() {
    return content;
  }

  Map<String, String> awsChunkedTrailer() {
    return awsChunkedTrailer;
  }

  @Override
  protected void deallocate() {
    super.deallocate();
    content.release();
  }

}
