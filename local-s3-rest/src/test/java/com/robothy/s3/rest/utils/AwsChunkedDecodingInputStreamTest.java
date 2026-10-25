package com.robothy.s3.rest.utils;

import com.robothy.s3.core.exception.LocalS3RequestException;
import com.robothy.s3.core.exception.S3ErrorCode;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class AwsChunkedDecodingInputStreamTest {

  @Test
  void testSimpleChunk() throws IOException {
    // Single chunk followed by 0 chunk
    String chunkedData = "3;chunk-signature=abcd\r\nabc\r\n0;chunk-signature=zzzz\r\n";
    try (AwsChunkedDecodingInputStream in =
             new AwsChunkedDecodingInputStream(
                 new ByteArrayInputStream(chunkedData.getBytes()))) {
      StringBuilder sb = new StringBuilder();
      int c;
      while ((c = in.read()) != -1) {
        sb.append((char) c);
      }
      assertEquals("abc", sb.toString());
    }
  }

  @Test
  void testMultipleChunks() throws IOException {
    // Two chunks, then 0 chunk
    String chunkedData = "3;chunk-signature=abcd\r\nabc\r\n2;chunk-signature=efgh\r\nxy\r\n0;chunk-signature=zzzz\r\n";
    try (AwsChunkedDecodingInputStream in =
             new AwsChunkedDecodingInputStream(
                 new ByteArrayInputStream(chunkedData.getBytes()))) {
      StringBuilder sb = new StringBuilder();
      int c;
      while ((c = in.read()) != -1) {
        sb.append((char) c);
      }
      assertEquals("abcxy", sb.toString());
    }
  }

  @Test
  void testZeroChunk() throws IOException {
    // Immediate zero chunk
    String chunkedData = "0;chunk-signature=abcd\r\n";
    try (AwsChunkedDecodingInputStream in =
             new AwsChunkedDecodingInputStream(
                 new ByteArrayInputStream(chunkedData.getBytes()))) {
      assertEquals(-1, in.read());
    }
  }

  @Test
  void testNoTrailingCrLf() throws IOException {
    // Missing final CRLF after size line
    String chunkedData = "3;chunk-signature=abcd\r\nabc\r\n0;chunk-signature=zzzz";
    try (AwsChunkedDecodingInputStream in =
             new AwsChunkedDecodingInputStream(
                 new ByteArrayInputStream(chunkedData.getBytes()))) {
      StringBuilder sb = new StringBuilder();
      int c;
      while ((c = in.read()) != -1) {
        sb.append((char) c);
      }
      assertEquals("abc", sb.toString());
    }
  }

  /**
   * A line of chunk metadata longer than the decoder buffers is a malformed body, like AwsChunkedBodyDecoder rejects it.
   */
  @Test
  void rejectsATooLongLine() throws IOException {
    String chunkedData = "3;chunk-signature=abcd\r\nabc\r\n0;chunk-signature=zzzz\r\n"
        + "x".repeat(AwsChunkedDecodingInputStream.MAX_LINE_LENGTH + 10);
    try (AwsChunkedDecodingInputStream in =
             new AwsChunkedDecodingInputStream(new ByteArrayInputStream(chunkedData.getBytes()))) {
      LocalS3RequestException e = assertThrows(LocalS3RequestException.class, in::readAllBytes);
      assertEquals(S3ErrorCode.IncompleteBody, e.getS3ErrorCode());
    }
  }

  /**
   * A chunk size beyond an int is parsed, like AwsChunkedBodyDecoder parses it.
   */
  @Test
  void parsesChunkSizesBeyondAnInt() {
    assertEquals(0x100000000L, AwsChunkedDecodingInputStream.parseChunkSize("100000000"));
  }

  /**
   * The checksum that the AWS SDK sends in the trailer, after the last chunk, is kept once the body is read.
   */
  @Test
  void keepsTheTrailingHeaders() throws IOException {
    String chunkedData = "3;chunk-signature=abcd\r\nabc\r\n0;chunk-signature=zzzz\r\n"
        + "x-amz-checksum-crc32:NSRBwg==\r\nx-amz-trailer-signature:1234\r\n\r\n";
    try (AwsChunkedDecodingInputStream in =
             new AwsChunkedDecodingInputStream(new ByteArrayInputStream(chunkedData.getBytes()))) {
      assertTrue(in.trailingHeaders().isEmpty());
      assertEquals("abc", new String(in.readAllBytes()));
      assertEquals("NSRBwg==", in.trailingHeaders().get("x-amz-checksum-crc32"));
      assertEquals("1234", in.trailingHeaders().get("x-amz-trailer-signature"));
      assertEquals(-1, in.read());
    }
  }

}
