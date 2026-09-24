package com.robothy.s3.core.util;

import com.robothy.s3.core.exception.LocalS3BadDigestException;
import com.robothy.s3.core.model.request.RequestChecksum;
import com.robothy.s3.datatypes.enums.CheckSumAlgorithm;
import com.robothy.s3.datatypes.enums.ChecksumType;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.zip.CRC32;
import java.util.zip.CRC32C;
import java.util.zip.Checksum;

/**
 * The <a href="https://docs.aws.amazon.com/AmazonS3/latest/userguide/checking-object-integrity.html">checksums</a>
 * of Amazon S3: {@code CRC32}, {@code CRC32C}, {@code CRC64NVME}, {@code SHA1} and {@code SHA256}. A checksum is the
 * big-endian bytes of a CRC, or a digest, encoded in base64.
 */
public final class Checksums {

  private static final String HEADER_PREFIX = "x-amz-checksum-";

  private Checksums() {
  }

  /**
   * Computes a checksum of content that is fed to it.
   */
  public interface Calculator {

    void update(byte[] bytes, int offset, int length);

    /**
     * The checksum of the content fed so far. Call it once, after all the content is fed.
     *
     * @return the bytes of the checksum.
     */
    byte[] digest();

  }

  /**
   * A calculator of a checksum.
   *
   * @param algorithm the algorithm of the checksum.
   * @return a new calculator.
   */
  public static Calculator calculator(CheckSumAlgorithm algorithm) {
    return switch (algorithm) {
      case CRC32 -> crc(new CRC32(), 4);
      case CRC32C -> crc(new CRC32C(), 4);
      case CRC64NVME -> crc(new Crc64Nvme(), 8);
      case SHA1 -> digest("SHA-1");
      case SHA256 -> digest("SHA-256");
    };
  }

  private static Calculator crc(Checksum checksum, int length) {
    return new Calculator() {
      @Override
      public void update(byte[] bytes, int offset, int count) {
        checksum.update(bytes, offset, count);
      }

      @Override
      public byte[] digest() {
        return toBytes(checksum.getValue(), length);
      }
    };
  }

  private static Calculator digest(String name) {
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance(name);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("The JVM provides no " + name + " digest.", e);
    }
    return new Calculator() {
      @Override
      public void update(byte[] bytes, int offset, int count) {
        digest.update(bytes, offset, count);
      }

      @Override
      public byte[] digest() {
        return digest.digest();
      }
    };
  }

  /**
   * The number of bytes of a checksum.
   */
  public static int length(CheckSumAlgorithm algorithm) {
    return switch (algorithm) {
      case CRC32, CRC32C -> 4;
      case CRC64NVME -> 8;
      case SHA1 -> 20;
      case SHA256 -> 32;
    };
  }

  /**
   * Whether the algorithm is a CRC, whose checksum of a whole object can be combined from the checksums of its parts.
   */
  public static boolean isCrc(CheckSumAlgorithm algorithm) {
    return algorithm == CheckSumAlgorithm.CRC32 || algorithm == CheckSumAlgorithm.CRC32C
        || algorithm == CheckSumAlgorithm.CRC64NVME;
  }

  /**
   * The name of the header that carries a checksum, e.g. {@code x-amz-checksum-crc32}.
   */
  public static String headerName(CheckSumAlgorithm algorithm) {
    return HEADER_PREFIX + algorithm.name().toLowerCase(Locale.ROOT);
  }

  /**
   * The algorithm of a header that carries a checksum.
   *
   * @param headerName a header name, e.g. {@code x-amz-checksum-crc32}, in any case.
   * @return the algorithm; empty if the header carries no checksum, e.g. {@code x-amz-checksum-mode}.
   */
  public static Optional<CheckSumAlgorithm> algorithmOfHeader(String headerName) {
    String name = headerName.trim().toLowerCase(Locale.ROOT);
    return name.startsWith(HEADER_PREFIX) ? algorithmOf(name.substring(HEADER_PREFIX.length())) : Optional.empty();
  }

  /**
   * An algorithm by its name, e.g. {@code crc32}, in any case.
   *
   * @return the algorithm; empty if there is none of that name.
   */
  public static Optional<CheckSumAlgorithm> algorithmOf(String name) {
    if (Objects.isNull(name)) {
      return Optional.empty();
    }
    String upperCase = name.trim().toUpperCase(Locale.ROOT);
    return Arrays.stream(CheckSumAlgorithm.values())
        .filter(algorithm -> algorithm.name().equals(upperCase))
        .findFirst();
  }

  /**
   * The type of the checksum of an object that a multipart upload stores if the upload names none: a CRC-64 has
   * no composite checksum.
   */
  public static ChecksumType defaultMultipartType(CheckSumAlgorithm algorithm) {
    return algorithm == CheckSumAlgorithm.CRC64NVME ? ChecksumType.FULL_OBJECT : ChecksumType.COMPOSITE;
  }

  /**
   * Whether an object that a multipart upload stores can have a checksum of the type: only a CRC has a checksum of
   * the whole object, and a CRC-64 has no composite checksum.
   */
  public static boolean supports(CheckSumAlgorithm algorithm, ChecksumType type) {
    return type == ChecksumType.FULL_OBJECT ? isCrc(algorithm) : algorithm != CheckSumAlgorithm.CRC64NVME;
  }

  public static String encode(byte[] checksum) {
    return Base64.getEncoder().encodeToString(checksum);
  }

  /**
   * Decode a checksum that a client sent.
   *
   * @param algorithm the algorithm of the checksum.
   * @param value the base64 encoded checksum.
   * @return the bytes of the checksum.
   * @throws LocalS3BadDigestException if the value isn't a base64 encoded checksum of the algorithm, which is what
   *     ceph/s3-tests expect of it, e.g. {@code test_object_checksum_sha256}.
   */
  public static byte[] decode(CheckSumAlgorithm algorithm, String value) {
    try {
      byte[] bytes = Base64.getDecoder().decode(value.trim());
      if (bytes.length == length(algorithm)) {
        return bytes;
      }
    } catch (IllegalArgumentException e) {
      // Rejected below.
    }
    throw new LocalS3BadDigestException("Value for " + headerName(algorithm) + " header is invalid.");
  }

  /**
   * Verify that content matches the checksum that the client sent for it, if it sent one.
   *
   * @param checksum the checksum of the request.
   * @param computed the checksum computed from the content.
   * @throws LocalS3BadDigestException if the client sent something that isn't a checksum, or the checksums differ.
   */
  public static void verify(RequestChecksum checksum, byte[] computed) {
    String expected = checksum.expected().get();
    if (Objects.isNull(expected)) {
      return;
    }
    if (!MessageDigest.isEqual(decode(checksum.algorithm(), expected), computed)) {
      throw new LocalS3BadDigestException(
          "The " + checksum.algorithm() + " you specified did not match the calculated checksum.");
    }
  }

  /**
   * Compute the checksum of content, which is read to its end but not closed.
   */
  public static byte[] compute(CheckSumAlgorithm algorithm, InputStream content) throws IOException {
    Calculator calculator = calculator(algorithm);
    byte[] buffer = new byte[64 * 1024];
    int read;
    while ((read = content.read(buffer)) != -1) {
      calculator.update(buffer, 0, read);
    }
    return calculator.digest();
  }

  /**
   * Wrap a stream so that a checksum of the content is computed while the stream is consumed.
   *
   * @param content the content.
   * @param calculator the calculator that the content read is fed to.
   * @return a stream that feeds the content it reads to the calculator.
   */
  public static InputStream checksumStream(InputStream content, Calculator calculator) {
    return new FilterInputStream(content) {
      @Override
      public int read() throws IOException {
        int read = super.read();
        if (read != -1) {
          calculator.update(new byte[] {(byte) read}, 0, 1);
        }
        return read;
      }

      @Override
      public int read(byte[] b, int off, int len) throws IOException {
        int read = super.read(b, off, len);
        if (read > 0) {
          calculator.update(b, off, read);
        }
        return read;
      }

      @Override
      public long skip(long n) throws IOException {
        // Skipped content wouldn't be checksummed.
        byte[] buffer = new byte[(int) Math.min(n, 8192)];
        int read = read(buffer, 0, buffer.length);
        return Math.max(read, 0);
      }

      @Override
      public boolean markSupported() {
        return false;
      }
    };
  }

  /**
   * The composite checksum of an object that a multipart upload stored: the checksum of the concatenated checksums
   * of its parts, followed by {@code -} and the number of parts.
   *
   * @param algorithm the algorithm of the checksums.
   * @param partChecksums the checksums of the parts, in ascending order of their part numbers.
   * @return the base64 encoded composite checksum.
   */
  public static String composite(CheckSumAlgorithm algorithm, List<byte[]> partChecksums) {
    Calculator calculator = calculator(algorithm);
    partChecksums.forEach(checksum -> calculator.update(checksum, 0, checksum.length));
    return encode(calculator.digest()) + "-" + partChecksums.size();
  }

  /**
   * The CRC of the whole content of an object, combined from the CRCs of its parts without reading their content,
   * the way zlib's {@code crc32_combine} does.
   *
   * @param algorithm a CRC algorithm, see {@linkplain #isCrc}.
   * @param partChecksums the CRCs of the parts, in the order of the content.
   * @param partSizes the numbers of bytes of the parts, in the same order.
   * @return the CRC of the concatenated content.
   */
  public static byte[] combine(CheckSumAlgorithm algorithm, List<byte[]> partChecksums, List<Long> partSizes) {
    int width = length(algorithm) * 8;
    long polynomial = switch (algorithm) {
      case CRC32 -> 0xEDB88320L;
      case CRC32C -> 0x82F63B78L;
      case CRC64NVME -> Crc64Nvme.POLYNOMIAL;
      default -> throw new IllegalArgumentException(algorithm + " is not a CRC.");
    };

    // The CRC of no content.
    long crc = toLong(calculator(algorithm).digest());
    for (int i = 0; i < partChecksums.size(); i++) {
      crc = combine(crc, toLong(partChecksums.get(i)), partSizes.get(i), polynomial, width);
    }
    return toBytes(crc, width / 8);
  }

  private static long combine(long crc1, long crc2, long length2, long polynomial, int width) {
    if (length2 <= 0) {
      // An empty part, whose CRC is the one of no content.
      return crc1;
    }

    // The operator of one zero bit, and then of two and four zero bits.
    long[] odd = new long[width];
    long[] even = new long[width];
    odd[0] = polynomial;
    long row = 1;
    for (int n = 1; n < width; n++) {
      odd[n] = row;
      row <<= 1;
    }
    square(even, odd);
    square(odd, even);

    // Apply the operator of length2 zero bytes to crc1, squaring it for every bit of the length.
    long length = length2;
    long result = crc1;
    do {
      square(even, odd);
      if ((length & 1) != 0) {
        result = times(even, result);
      }
      length >>>= 1;
      if (length == 0) {
        break;
      }
      square(odd, even);
      if ((length & 1) != 0) {
        result = times(odd, result);
      }
      length >>>= 1;
    } while (length != 0);
    return result ^ crc2;
  }

  private static long times(long[] matrix, long vector) {
    long sum = 0;
    long remaining = vector;
    for (int i = 0; remaining != 0; i++, remaining >>>= 1) {
      if ((remaining & 1) != 0) {
        sum ^= matrix[i];
      }
    }
    return sum;
  }

  private static void square(long[] square, long[] matrix) {
    for (int n = 0; n < matrix.length; n++) {
      square[n] = times(matrix, matrix[n]);
    }
  }

  private static byte[] toBytes(long value, int length) {
    byte[] bytes = new byte[length];
    for (int i = 0; i < length; i++) {
      bytes[i] = (byte) (value >>> (8 * (length - 1 - i)));
    }
    return bytes;
  }

  private static long toLong(byte[] bytes) {
    long value = 0;
    for (byte b : bytes) {
      value = (value << 8) | (b & 0xFF);
    }
    return value;
  }

}
