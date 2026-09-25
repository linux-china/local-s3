package com.robothy.s3.rest.netty;

import java.util.List;

/**
 * Verifies the signatures of an {@code aws-chunked} body while it is received, chunk by chunk, see
 * {@linkplain AwsChunkedBodyDecoder}. The signature of a chunk is chained to the one of the chunk before it, so an
 * instance verifies the chunks of one body, in order.
 */
public interface ChunkSignatures {

  /**
   * Verifies nothing, e.g. for an unsigned {@code aws-chunked} body, or for a service that doesn't verify signatures.
   */
  ChunkSignatures UNVERIFIED = new ChunkSignatures() {
    @Override
    public boolean verifies() {
      return false;
    }

    @Override
    public boolean verifyChunk(String signature, byte[] sha256) {
      return true;
    }

    @Override
    public boolean verifyTrailer(List<String> lines) {
      return true;
    }
  };

  /**
   * Whether the signatures are verified at all, i.e. whether the hashes of the chunks are needed.
   *
   * @return {@code false} if every chunk is accepted.
   */
  default boolean verifies() {
    return true;
  }

  /**
   * Verify the signature of the next chunk.
   *
   * @param signature the {@code chunk-signature} of the chunk; {@code null} if it carries none.
   * @param sha256 the SHA-256 hash of the data of the chunk.
   * @return whether the signature is valid.
   */
  boolean verifyChunk(String signature, byte[] sha256);

  /**
   * Verify the trailer that follows the last chunk, which is signed with {@code x-amz-trailer-signature} if the body
   * has a trailer.
   *
   * @param lines the lines of the trailer, e.g. {@code x-amz-checksum-crc32:AAAAAA==}; empty if there is none.
   * @return whether the trailer is valid.
   */
  boolean verifyTrailer(List<String> lines);

}
