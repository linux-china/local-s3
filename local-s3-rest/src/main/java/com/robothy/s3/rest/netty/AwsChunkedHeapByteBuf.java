package com.robothy.s3.rest.netty;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;
import java.util.List;
import java.util.Map;

/**
 * An {@code aws-chunked} body that was buffered on the heap and decoded once it was complete: the decoded data, as
 * slices of the body that was received, with its trailing headers, see {@linkplain RequestBodies#awsChunkedTrailer}.
 */
final class AwsChunkedHeapByteBuf extends CompositeByteBuf {

  private final Map<String, String> awsChunkedTrailer;

  /**
   * @param decoded the decoded data, which the buffer takes over.
   * @param awsChunkedTrailer the trailing headers of the body.
   */
  AwsChunkedHeapByteBuf(List<ByteBuf> decoded, Map<String, String> awsChunkedTrailer) {
    super(UnpooledByteBufAllocator.DEFAULT, false, Integer.MAX_VALUE, decoded);
    this.awsChunkedTrailer = awsChunkedTrailer;
  }

  Map<String, String> awsChunkedTrailer() {
    return awsChunkedTrailer;
  }

}
