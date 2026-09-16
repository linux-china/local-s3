package com.robothy.s3.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * MD5 digests of test content, which entity tags are computed from.
 */
public final class Digests {

  private Digests() {
  }

  public static byte[] md5(byte[] content) {
    try {
      return MessageDigest.getInstance("MD5").digest(content);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public static byte[] md5(String content) {
    return md5(content.getBytes(StandardCharsets.UTF_8));
  }

  public static String md5Hex(byte[] content) {
    return HexFormat.of().formatHex(md5(content));
  }

  public static String md5Hex(String content) {
    return md5Hex(content.getBytes(StandardCharsets.UTF_8));
  }

}
