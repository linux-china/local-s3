package com.robothy.s3.rest.utils;

import com.robothy.s3.core.exception.S3ErrorCode;
import com.robothy.s3.core.exception.LocalS3RequestException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Skips V4 style signing metadata from input streams.
 * <p>The original stream looks like this (newlines are CRLF):</p>
 *
 * <pre>
 * 5;chunk-signature=7ece820edcf094ce1ef6d643c8db60b67913e28831d9b0430efd2b56a9deec5e
 * 12345
 * 0;chunk-signature=ee2c094d7162170fcac17d2c76073cd834b0488bfe52e89e48599b8115c7ffa2
 * </pre>
 *
 * <p>The format of each chunk of data is:</p>
 *
 * <pre>
 * [hex-encoded-number-of-bytes-in-chunk];chunk-signature=[sha256-signature][crlf]
 * [payload-bytes-of-this-chunk][crlf]
 * </pre>
 *
 * @see
 * <a href="http://docs.aws.amazon.com/AWSJavaSDK/latest/javadoc/com/amazonaws/auth/AwsChunkedEncodingInputStream.html">
 *     AwsChunkedEncodingInputStream</a>
 */
public class AwsChunkedDecodingInputStream extends InputStream implements TrailingHeaders {

  /**
   * That's the max chunk buffer size used in the AWS implementation.
   */
  private static final int MAX_CHUNK_SIZE = 256 * 1024;

  private static final byte[] CRLF = "\r\n".getBytes(StandardCharsets.UTF_8);

  private static final byte[] DELIMITER = ";".getBytes(StandardCharsets.UTF_8);

  private final InputStream source;

  private int remainingInChunk = 0;

  private final ByteBuffer byteBuffer = ByteBuffer.allocate(MAX_CHUNK_SIZE);

  private final Map<String, String> trailingHeaders = new LinkedHashMap<>();

  /**
   * Whether the last chunk, and the trailer that follows it, were read.
   */
  private boolean finished;

  /**
   * Constructs a new {@link AwsChunkedDecodingInputStream}.
   *
   * @param source The {@link InputStream} to wrap.
   */
  AwsChunkedDecodingInputStream(final InputStream source) {
    this.source = source;
  }

  @Override
  public int read() throws IOException {
    if (finished) {
      return -1;
    }
    if (remainingInChunk == 0) {
      final byte[] hexLengthBytes = readUntil(DELIMITER);
      if (hexLengthBytes == null) {
        finished = true;
        return -1;
      }

      remainingInChunk = parseChunkSize(new String(hexLengthBytes, StandardCharsets.UTF_8));

      if (remainingInChunk == 0) {
        readTrailer();
        return -1;
      }

      readUntil(CRLF);
    }

    remainingInChunk--;

    return source.read();
  }

  /**
   * Read the rest of the last chunk, i.e. its signature, and the trailing headers that follow it up to the empty line
   * that ends the body.
   */
  private void readTrailer() throws IOException {
    finished = true;
    if (readUntil(CRLF) == null) {
      return;
    }
    byte[] line;
    while ((line = readUntil(CRLF)) != null && line.length > 0) {
      TrailingHeaders.parse(trailingHeaders, new String(line, StandardCharsets.UTF_8));
    }
  }

  @Override
  public Map<String, String> trailingHeaders() {
    return Collections.unmodifiableMap(trailingHeaders);
  }

  /**
   * Parse the hexadecimal size of a chunk of an {@code aws-chunked} body.
   *
   * @param chunkSize the size as it precedes the chunk, possibly surrounded by whitespace.
   * @return the size of the chunk.
   * @throws LocalS3RequestException {@code IncompleteBody} if the size isn't a hexadecimal size.
   */
  static int parseChunkSize(String chunkSize) {
    try {
      int size = Integer.parseInt(chunkSize.trim(), 16);
      if (size >= 0) {
        return size;
      }
    } catch (NumberFormatException e) {
      // Rejected below.
    }
    throw new LocalS3RequestException(S3ErrorCode.IncompleteBody, "The aws-chunked request body is malformed.");
  }

  @Override
  public void close() throws IOException {
    source.close();
  }

  /**
   * Reads this stream until the byte sequence was found.
   *
   * @param endSequence The byte sequence to look for in the stream. The source stream is read
   *     until the last bytes read are equal to this sequence.
   *
   * @return The bytes read <em>before</em> the end sequence started.
   */
  private byte[] readUntil(final byte[] endSequence) throws IOException {
    byteBuffer.clear();
    while (!endsWith(byteBuffer.asReadOnlyBuffer(), endSequence)) {
      final int c = source.read();
      if (c < 0) {
        return null;
      }

      final byte unsigned = (byte) (c & 0xFF);
      byteBuffer.put(unsigned);
    }

    final byte[] result = new byte[byteBuffer.position() - endSequence.length];
    byteBuffer.rewind();
    byteBuffer.get(result);
    return result;
  }

  private boolean endsWith(final ByteBuffer buffer, final byte[] endSequence) {
    final int pos = buffer.position();
    if (pos >= endSequence.length) {
      for (int i = 0; i < endSequence.length; i++) {
        if (buffer.get(pos - endSequence.length + i) != endSequence[i]) {
          return false;
        }
      }

      return true;
    }

    return false;
  }
}
