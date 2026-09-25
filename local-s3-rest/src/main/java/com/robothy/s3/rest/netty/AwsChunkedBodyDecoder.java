package com.robothy.s3.rest.netty;

import com.robothy.s3.core.exception.S3ErrorCode;
import io.netty.buffer.ByteBuf;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Decodes an {@code aws-chunked} body as it is received, a piece at a time, so that the file that a large body is
 * buffered in holds the decoded content, which a storage can take over like the body of any other upload, rather than
 * the encoded body, which would have to be decoded and written a second time.
 *
 * <p>The body is a sequence of chunks, and a trailer after the last one, which is empty (lines are CRLF):
 *
 * <pre>
 * [hex size][;chunk-signature=[signature]]
 * [data]
 * ...
 * 0[;chunk-signature=[signature]]
 * [trailing header lines, e.g. x-amz-checksum-crc32:AAAAAA==]
 * [empty line]
 * </pre>
 *
 * <p>The signature of each chunk is verified once its data is received, and the trailer once the body is complete,
 * see {@linkplain ChunkSignatures}. A body that is malformed or incomplete is rejected with {@code IncompleteBody}, one
 * whose signatures don't match with {@code SignatureDoesNotMatch}; see {@linkplain RequestBodyRejection}.
 *
 * <p>Not thread-safe: the pieces of a body are decoded one at a time, in order.
 */
final class AwsChunkedBodyDecoder {

  /**
   * The longest line of chunk metadata, e.g. {@code <hex size>;chunk-signature=<64 hex digits>} or a trailing header.
   */
  static final int MAX_LINE_LENGTH = 8 * 1024;

  private enum State { CHUNK_HEADER, DATA, DATA_END, TRAILER, DONE }

  private final ChunkSignatures signatures;

  /**
   * The length of the decoded body, from {@code x-amz-decoded-content-length}; negative if it isn't known.
   */
  private final long expectedLength;

  /**
   * The hash of the data of the current chunk; {@code null} if the signatures aren't verified.
   */
  private final MessageDigest digest;

  private final byte[] line = new byte[MAX_LINE_LENGTH + 2];

  private int lineLength;

  private State state = State.CHUNK_HEADER;

  private long remainingInChunk;

  private String chunkSignature;

  private long decodedLength;

  private final List<String> trailerLines = new ArrayList<>();

  /**
   * Create a decoder of a body.
   *
   * @param signatures verifies the signatures of the chunks of the body.
   * @param expectedLength the length of the decoded body; negative if it isn't known.
   */
  AwsChunkedBodyDecoder(ChunkSignatures signatures, long expectedLength) {
    this.signatures = Objects.requireNonNull(signatures);
    this.expectedLength = expectedLength;
    this.digest = signatures.verifies() ? sha256() : null;
  }

  /**
   * Decode the next piece of the body.
   *
   * @param encoded the piece, whose readable bytes are consumed.
   * @param decoded receives the decoded data of the piece, as retained slices of {@code encoded}, which the caller
   *     releases.
   * @throws RequestBodyRejection if the body is malformed, or a chunk doesn't have the signature it carries.
   */
  void decode(ByteBuf encoded, List<ByteBuf> decoded) {
    while (encoded.isReadable()) {
      switch (state) {
        case CHUNK_HEADER -> {
          String header = readLine(encoded);
          if (header != null) {
            startChunk(header);
          }
        }
        case DATA -> {
          int count = (int) Math.min(remainingInChunk, encoded.readableBytes());
          ByteBuf data = encoded.readRetainedSlice(count);
          decoded.add(data);
          if (digest != null) {
            for (ByteBuffer buffer : data.nioBuffers()) {
              digest.update(buffer);
            }
          }
          remainingInChunk -= count;
          decodedLength += count;
          if (remainingInChunk == 0) {
            verifyChunk();
            state = State.DATA_END;
          }
        }
        case DATA_END -> {
          String end = readLine(encoded);
          if (end != null) {
            if (!end.isEmpty()) {
              throw malformed();
            }
            state = State.CHUNK_HEADER;
          }
        }
        case TRAILER -> {
          String trailer = readLine(encoded);
          if (trailer != null) {
            if (trailer.isEmpty()) {
              state = State.DONE;
            } else {
              trailerLines.add(trailer);
            }
          }
        }
        case DONE -> {
          // Tolerate line breaks after the end of the body, but nothing else.
          byte b = encoded.readByte();
          if (b != '\r' && b != '\n') {
            throw malformed();
          }
        }
      }
    }
  }

  /**
   * Complete the body, once all of it was decoded.
   *
   * @return the trailing headers of the body by their lower case names, e.g. {@code x-amz-checksum-crc32}.
   * @throws RequestBodyRejection if the body is incomplete, has another length than {@code x-amz-decoded-content-length},
   *     or its trailer doesn't have the signature it carries.
   */
  Map<String, String> finish() {
    // The empty line that ends the trailer is optional at the end of the body.
    boolean complete = state == State.DONE || state == State.TRAILER && lineLength == 0;
    if (!complete) {
      throw new RequestBodyRejection(S3ErrorCode.IncompleteBody, "The aws-chunked request body is incomplete.");
    }
    if (expectedLength >= 0 && decodedLength != expectedLength) {
      throw new RequestBodyRejection(S3ErrorCode.IncompleteBody, "The aws-chunked request body has "
          + decodedLength + " bytes, but x-amz-decoded-content-length is " + expectedLength + ".");
    }
    if (!signatures.verifyTrailer(Collections.unmodifiableList(trailerLines))) {
      throw signatureMismatch();
    }
    Map<String, String> headers = new LinkedHashMap<>();
    for (String trailer : trailerLines) {
      int separator = trailer.indexOf(':');
      if (separator > 0) {
        headers.put(trailer.substring(0, separator).trim().toLowerCase(Locale.ROOT),
            trailer.substring(separator + 1).trim());
      }
    }
    return headers;
  }

  private void startChunk(String header) {
    String[] parts = header.split(";");
    long size;
    try {
      size = Long.parseLong(parts[0].trim(), 16);
    } catch (NumberFormatException e) {
      throw malformed();
    }
    if (size < 0) {
      throw malformed();
    }
    chunkSignature = null;
    for (int i = 1; i < parts.length; i++) {
      String[] extension = parts[i].split("=", 2);
      if (extension.length == 2 && "chunk-signature".equalsIgnoreCase(extension[0].trim())) {
        chunkSignature = extension[1].trim();
      }
    }
    remainingInChunk = size;
    if (size == 0) {
      verifyChunk();
      state = State.TRAILER;
    } else {
      state = State.DATA;
    }
  }

  private void verifyChunk() {
    if (digest != null && !signatures.verifyChunk(chunkSignature, digest.digest())) {
      throw signatureMismatch();
    }
  }

  /**
   * Read a line up to its CRLF, which may span pieces of the body.
   *
   * @return the line without its CRLF; {@code null} if the piece ends before the line does.
   */
  private String readLine(ByteBuf encoded) {
    while (encoded.isReadable()) {
      byte b = encoded.readByte();
      if (b == '\n' && lineLength > 0 && line[lineLength - 1] == '\r') {
        String result = new String(line, 0, lineLength - 1, StandardCharsets.UTF_8);
        lineLength = 0;
        return result;
      }
      if (lineLength == line.length) {
        throw malformed();
      }
      line[lineLength++] = b;
    }
    return null;
  }

  private static RequestBodyRejection malformed() {
    return new RequestBodyRejection(S3ErrorCode.IncompleteBody, "The aws-chunked request body is malformed.");
  }

  private static RequestBodyRejection signatureMismatch() {
    return new RequestBodyRejection(S3ErrorCode.SignatureDoesNotMatch,
        S3ErrorCode.SignatureDoesNotMatch.description());
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("Unable to calculate a SHA-256 digest.", e);
    }
  }

}
