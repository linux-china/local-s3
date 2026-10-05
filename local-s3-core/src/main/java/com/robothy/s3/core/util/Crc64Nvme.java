package com.robothy.s3.core.util;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.zip.Checksum;

/**
 * The CRC-64/NVME checksum, which Amazon S3 calls {@code CRC64NVME}: the reflected polynomial
 * {@code 0x9A6C9329AC4BC9B5}, with an initial value and a final XOR of all ones. The JDK has no CRC-64, so it is
 * computed here, eight bytes at a time.
 */
public final class Crc64Nvme implements Checksum {

  /**
   * The reflected polynomial.
   */
  static final long POLYNOMIAL = 0x9A6C9329AC4BC9B5L;

  /**
   * {@code TABLES[k][b]} is the CRC register after byte {@code b} is followed by {@code k} zero bytes.
   */
  private static final long[][] TABLES = new long[8][256];

  /**
   * Reads eight bytes of an array as a little-endian {@code long}, which the JIT compiles to a single load.
   */
  private static final VarHandle LONG_LE = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

  static {
    for (int b = 0; b < 256; b++) {
      long crc = b;
      for (int bit = 0; bit < 8; bit++) {
        crc = (crc & 1) != 0 ? (crc >>> 1) ^ POLYNOMIAL : crc >>> 1;
      }
      TABLES[0][b] = crc;
    }
    for (int k = 1; k < 8; k++) {
      for (int b = 0; b < 256; b++) {
        long previous = TABLES[k - 1][b];
        TABLES[k][b] = (previous >>> 8) ^ TABLES[0][(int) (previous & 0xFF)];
      }
    }
  }

  private long crc = ~0L;

  @Override
  public void update(int b) {
    crc = (crc >>> 8) ^ TABLES[0][(int) ((crc ^ b) & 0xFF)];
  }

  @Override
  public void update(byte[] b, int off, int len) {
    long value = crc;
    int index = off;
    int end = off + len;
    for (; index + 8 <= end; index += 8) {
      value ^= (long) LONG_LE.get(b, index);
      value = TABLES[7][(int) (value & 0xFF)]
          ^ TABLES[6][(int) ((value >>> 8) & 0xFF)]
          ^ TABLES[5][(int) ((value >>> 16) & 0xFF)]
          ^ TABLES[4][(int) ((value >>> 24) & 0xFF)]
          ^ TABLES[3][(int) ((value >>> 32) & 0xFF)]
          ^ TABLES[2][(int) ((value >>> 40) & 0xFF)]
          ^ TABLES[1][(int) ((value >>> 48) & 0xFF)]
          ^ TABLES[0][(int) (value >>> 56)];
    }
    for (; index < end; index++) {
      value = (value >>> 8) ^ TABLES[0][(int) ((value ^ b[index]) & 0xFF)];
    }
    crc = value;
  }

  @Override
  public long getValue() {
    return ~crc;
  }

  @Override
  public void reset() {
    crc = ~0L;
  }

}
