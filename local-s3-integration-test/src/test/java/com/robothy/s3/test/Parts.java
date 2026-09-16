package com.robothy.s3.test;

/**
 * The content of the parts of multipart uploads. Like Amazon S3, LocalS3 requires every part of an upload but
 * the last one to be at least 5 MiB.
 */
final class Parts {

  /**
   * The minimum size of a part that isn't the last one of an upload.
   */
  static final int MIN_PART_SIZE = 5 * 1024 * 1024;

  private Parts() {
  }

  /**
   * A part that is large enough to be followed by other parts: {@code content} padded with dots to
   * {@linkplain #MIN_PART_SIZE}, or to {@linkplain #MIN_PART_SIZE} plus {@code extra} bytes.
   */
  static String large(String content, int extra) {
    return content + ".".repeat(MIN_PART_SIZE + extra - content.length());
  }

  /**
   * A part of exactly {@linkplain #MIN_PART_SIZE} bytes that starts with {@code content}.
   */
  static String large(String content) {
    return large(content, 0);
  }

}
