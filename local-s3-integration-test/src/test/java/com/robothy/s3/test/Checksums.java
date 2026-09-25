package com.robothy.s3.test;

import com.robothy.s3.core.util.Crc64Nvme;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.zip.CRC32;
import java.util.zip.CRC32C;
import java.util.zip.Checksum;

/**
 * The base64 encoded checksums of test content, the way Amazon S3 answers them.
 */
final class Checksums {

  private Checksums() {
  }

  static String crc32(String content) {
    return crc32(bytes(content));
  }

  static String crc32(byte[] content) {
    return crc(new CRC32(), content, 4);
  }

  static String crc32c(String content) {
    return crc(new CRC32C(), bytes(content), 4);
  }

  static String crc64Nvme(String content) {
    return crc64Nvme(bytes(content));
  }

  static String crc64Nvme(byte[] content) {
    return crc(new Crc64Nvme(), content, 8);
  }

  static String sha1(String content) {
    return encode(digest("SHA-1", bytes(content)));
  }

  static String sha256(String content) {
    return encode(digest("SHA-256", bytes(content)));
  }

  static byte[] digest(String algorithm, byte[] content) {
    try {
      return MessageDigest.getInstance(algorithm).digest(content);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  static byte[] decode(String checksum) {
    return Base64.getDecoder().decode(checksum);
  }

  static String encode(byte[] checksum) {
    return Base64.getEncoder().encodeToString(checksum);
  }

  private static String crc(Checksum checksum, byte[] content, int length) {
    checksum.update(content, 0, content.length);
    long value = checksum.getValue();
    byte[] bytes = new byte[length];
    for (int i = 0; i < length; i++) {
      bytes[i] = (byte) (value >>> (8 * (length - 1 - i)));
    }
    return encode(bytes);
  }

  private static byte[] bytes(String content) {
    return content.getBytes(StandardCharsets.UTF_8);
  }

}
