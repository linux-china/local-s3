package com.robothy.s3.rest.netty;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.robothy.s3.core.exception.S3ErrorCode;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class AwsChunkedBodyDecoderTest {

  private static final byte[] CONTENT = randomBytes(70_000);

  /**
   * An unsigned body with a trailer, e.g. {@code STREAMING-UNSIGNED-PAYLOAD-TRAILER}, decoded however it is split.
   */
  @Test
  void decodesAnUnsignedBodyWithATrailerWhereverItIsSplit() {
    byte[] encoded = encode(CONTENT, 65_536, null, "x-amz-checksum-crc32:AAAAAA==\r\n");
    for (int piece : new int[] {1, 7, 100, 65_540, encoded.length}) {
      AwsChunkedBodyDecoder decoder = new AwsChunkedBodyDecoder(ChunkSignatures.UNVERIFIED, CONTENT.length);
      assertArrayEquals(CONTENT, decode(decoder, encoded, piece), "pieces of " + piece + " bytes");
      assertEquals(Map.of("x-amz-checksum-crc32", "AAAAAA=="), decoder.finish());
    }
  }

  /**
   * A signed body without a trailer: each chunk is verified once its data is received, with the hash of its data.
   */
  @Test
  void verifiesTheSignatureOfEachChunk() {
    List<String> verified = new ArrayList<>();
    ChunkSignatures signatures = new ChunkSignatures() {
      @Override
      public boolean verifyChunk(String signature, byte[] sha256) {
        verified.add(signature);
        return signature.equals(HexFormat.of().formatHex(sha256));
      }

      @Override
      public boolean verifyTrailer(List<String> lines) {
        return lines.isEmpty();
      }
    };
    byte[] encoded = encode(CONTENT, 30_000, "sha256", null);
    AwsChunkedBodyDecoder decoder = new AwsChunkedBodyDecoder(signatures, CONTENT.length);
    assertArrayEquals(CONTENT, decode(decoder, encoded, 4096));
    assertEquals(Map.of(), decoder.finish());
    assertEquals(4, verified.size(), "3 chunks of data and the final chunk.");

    byte[] tampered = encoded.clone();
    tampered[encoded.length - 200] ^= 1;
    RequestBodyRejection rejection = assertThrows(RequestBodyRejection.class,
        () -> decode(new AwsChunkedBodyDecoder(signatures, CONTENT.length), tampered, 4096));
    assertEquals(S3ErrorCode.SignatureDoesNotMatch, rejection.errorCode());
  }

  @Test
  void rejectsAnIncompleteOrMalformedBody() {
    byte[] encoded = encode(CONTENT, 65_536, null, null);

    AwsChunkedBodyDecoder truncated = new AwsChunkedBodyDecoder(ChunkSignatures.UNVERIFIED, -1);
    decode(truncated, Arrays.copyOf(encoded, encoded.length - 100), 1000);
    assertIncompleteBody(assertThrows(RequestBodyRejection.class, truncated::finish));

    AwsChunkedBodyDecoder wrongLength = new AwsChunkedBodyDecoder(ChunkSignatures.UNVERIFIED, CONTENT.length + 1);
    decode(wrongLength, encoded, 1000);
    assertIncompleteBody(assertThrows(RequestBodyRejection.class, wrongLength::finish));

    assertIncompleteBody(assertThrows(RequestBodyRejection.class, () -> decode(
        new AwsChunkedBodyDecoder(ChunkSignatures.UNVERIFIED, -1), ascii("zz\r\n"), 10)));
    assertIncompleteBody(assertThrows(RequestBodyRejection.class, () -> decode(
        new AwsChunkedBodyDecoder(ChunkSignatures.UNVERIFIED, -1), ascii("3\r\nabcX\r\n0\r\n\r\n"), 10)));
    assertIncompleteBody(assertThrows(RequestBodyRejection.class, () -> decode(
        new AwsChunkedBodyDecoder(ChunkSignatures.UNVERIFIED, -1), new byte[AwsChunkedBodyDecoder.MAX_LINE_LENGTH + 10],
        100)));
  }

  /**
   * The final CRLF of a body without a trailer may be missing.
   */
  @Test
  void toleratesAMissingFinalLineBreak() {
    AwsChunkedBodyDecoder decoder = new AwsChunkedBodyDecoder(ChunkSignatures.UNVERIFIED, 3);
    assertArrayEquals(ascii("abc"), decode(decoder, ascii("3\r\nabc\r\n0\r\n"), 2));
    assertEquals(Map.of(), decoder.finish());
  }

  private static void assertIncompleteBody(RequestBodyRejection rejection) {
    assertEquals(S3ErrorCode.IncompleteBody, rejection.errorCode());
  }

  private static byte[] decode(AwsChunkedBodyDecoder decoder, byte[] encoded, int pieceSize) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (int from = 0; from < encoded.length; from += pieceSize) {
      ByteBuf piece = Unpooled.copiedBuffer(encoded, from, Math.min(pieceSize, encoded.length - from));
      List<ByteBuf> decoded = new ArrayList<>();
      try {
        decoder.decode(piece, decoded);
        for (ByteBuf data : decoded) {
          byte[] bytes = new byte[data.readableBytes()];
          data.readBytes(bytes);
          out.writeBytes(bytes);
        }
      } finally {
        decoded.forEach(ByteBuf::release);
        piece.release();
      }
    }
    return out.toByteArray();
  }

  /**
   * Encode content in chunks, each signed with the hex SHA-256 of its data if {@code signature} is given.
   */
  private static byte[] encode(byte[] content, int chunkSize, String signature, String trailer) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (int from = 0; from <= content.length; from += chunkSize) {
      int size = Math.min(chunkSize, content.length - from);
      byte[] data = Arrays.copyOfRange(content, from, from + size);
      String extension = signature == null ? "" : ";chunk-signature=" + HexFormat.of().formatHex(sha256(data));
      out.writeBytes(ascii(Integer.toHexString(size) + extension + "\r\n"));
      out.writeBytes(data);
      if (size == 0) {
        break;
      }
      out.writeBytes(ascii("\r\n"));
      if (from + size == content.length) {
        out.writeBytes(ascii("0" + (signature == null ? ""
            : ";chunk-signature=" + HexFormat.of().formatHex(sha256(new byte[0]))) + "\r\n"));
        break;
      }
    }
    if (trailer != null) {
      out.writeBytes(ascii(trailer));
    }
    out.writeBytes(ascii("\r\n"));
    return out.toByteArray();
  }

  private static byte[] sha256(byte[] data) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(data);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static byte[] ascii(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }

  private static byte[] randomBytes(int size) {
    byte[] bytes = new byte[size];
    new Random(size).nextBytes(bytes);
    return bytes;
  }

}
