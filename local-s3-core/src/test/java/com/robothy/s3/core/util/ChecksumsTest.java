package com.robothy.s3.core.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.robothy.s3.core.exception.LocalS3BadDigestException;
import com.robothy.s3.core.exception.LocalS3RequestException;
import com.robothy.s3.core.model.request.RequestChecksum;
import com.robothy.s3.datatypes.enums.CheckSumAlgorithm;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ChecksumsTest {

  private static final byte[] CHECK = "123456789".getBytes(StandardCharsets.US_ASCII);

  /**
   * The check values of the CRC catalogue, i.e. the CRCs of {@code 123456789}.
   */
  @Test
  void computesTheCheckValues() throws IOException {
    assertEquals("CBF43926", hex(CheckSumAlgorithm.CRC32, CHECK));
    assertEquals("E3069283", hex(CheckSumAlgorithm.CRC32C, CHECK));
    assertEquals("AE8B14860A799888", hex(CheckSumAlgorithm.CRC64NVME, CHECK));
    assertEquals("F7C3BC1D808E04732ADF679965CCC34CA7AE3441", hex(CheckSumAlgorithm.SHA1, CHECK));
  }

  /**
   * The CRC-64 is computed eight bytes at a time; every length and offset must answer what one byte at a time does.
   */
  @Test
  void crc64NvmeOfEveryAlignmentIsTheOneOfSingleBytes() {
    byte[] content = new byte[100];
    new Random(7).nextBytes(content);
    for (int offset = 0; offset < 9; offset++) {
      for (int length = 0; offset + length <= content.length; length++) {
        Crc64Nvme bulk = new Crc64Nvme();
        bulk.update(content, offset, length);
        Crc64Nvme single = new Crc64Nvme();
        for (int i = offset; i < offset + length; i++) {
          single.update(content[i]);
        }
        assertEquals(single.getValue(), bulk.getValue());
      }
    }
  }

  @ParameterizedTest
  @EnumSource(value = CheckSumAlgorithm.class, names = {"CRC32", "CRC32C", "CRC64NVME"})
  void combinesTheCrcsOfPartsIntoTheOneOfTheWholeContent(CheckSumAlgorithm algorithm) throws IOException {
    Random random = new Random(42);
    for (int round = 0; round < 20; round++) {
      int parts = 1 + random.nextInt(5);
      List<byte[]> checksums = new ArrayList<>();
      List<Long> sizes = new ArrayList<>();
      java.io.ByteArrayOutputStream whole = new java.io.ByteArrayOutputStream();
      for (int i = 0; i < parts; i++) {
        // Empty parts included.
        byte[] part = new byte[random.nextInt(3) == 0 ? 0 : random.nextInt(5000)];
        random.nextBytes(part);
        whole.write(part);
        checksums.add(Checksums.compute(algorithm, new ByteArrayInputStream(part)));
        sizes.add((long) part.length);
      }
      assertArrayEquals(Checksums.compute(algorithm, new ByteArrayInputStream(whole.toByteArray())),
          Checksums.combine(algorithm, checksums, sizes));
    }
  }

  @Test
  void compositeChecksumIsTheChecksumOfThePartChecksums() throws IOException {
    byte[] part1 = Checksums.compute(CheckSumAlgorithm.SHA256, new ByteArrayInputStream("a".getBytes()));
    byte[] part2 = Checksums.compute(CheckSumAlgorithm.SHA256, new ByteArrayInputStream("b".getBytes()));
    byte[] concatenated = new byte[64];
    System.arraycopy(part1, 0, concatenated, 0, 32);
    System.arraycopy(part2, 0, concatenated, 32, 32);

    assertEquals(Checksums.encode(Checksums.compute(CheckSumAlgorithm.SHA256, new ByteArrayInputStream(concatenated)))
        + "-2", Checksums.composite(CheckSumAlgorithm.SHA256, List.of(part1, part2)));
  }

  @Test
  void verifiesTheChecksumThatTheClientSent() throws IOException {
    byte[] computed = Checksums.compute(CheckSumAlgorithm.CRC32, new ByteArrayInputStream(CHECK));
    String value = Base64.getEncoder().encodeToString(computed);

    assertDoesNotThrow(() -> Checksums.verify(RequestChecksum.of(CheckSumAlgorithm.CRC32, value), computed));
    assertDoesNotThrow(() -> Checksums.verify(RequestChecksum.of(CheckSumAlgorithm.CRC32, null), computed));
    assertThrows(LocalS3BadDigestException.class,
        () -> Checksums.verify(RequestChecksum.of(CheckSumAlgorithm.CRC32, "AAAAAA=="), computed));
    // Not base64, and a checksum of the wrong length.
    assertThrows(LocalS3RequestException.class,
        () -> Checksums.verify(RequestChecksum.of(CheckSumAlgorithm.CRC32, "not base64!"), computed));
    assertThrows(LocalS3RequestException.class,
        () -> Checksums.verify(RequestChecksum.of(CheckSumAlgorithm.CRC32, "AAAAAAAAAAA="), computed));
  }

  @Test
  void namesHeadersAndAlgorithms() {
    assertEquals("x-amz-checksum-crc64nvme", Checksums.headerName(CheckSumAlgorithm.CRC64NVME));
    assertEquals(CheckSumAlgorithm.SHA256, Checksums.algorithmOfHeader("X-Amz-Checksum-SHA256").orElseThrow());
    assertEquals(CheckSumAlgorithm.CRC32C, Checksums.algorithmOf("crc32c").orElseThrow());
    assertEquals(false, Checksums.algorithmOfHeader("x-amz-checksum-mode").isPresent());
  }

  private static String hex(CheckSumAlgorithm algorithm, byte[] content) throws IOException {
    return java.util.HexFormat.of().withUpperCase()
        .formatHex(Checksums.compute(algorithm, new ByteArrayInputStream(content)));
  }

}
