package com.robothy.s3.rest.utils;

import java.util.Locale;
import java.util.Map;

/**
 * The trailing headers of an {@code aws-chunked} request body, which follow its last chunk, e.g. the
 * {@code x-amz-checksum-crc32} that the AWS SDK computes while it sends the content. They are only known once the body
 * is read to its end.
 */
public interface TrailingHeaders {

  /**
   * The trailing headers read so far.
   *
   * @return the headers by their lower case names; empty until the body is read to its end.
   */
  Map<String, String> trailingHeaders();

  /**
   * Add a trailing header line to headers, ignoring a line that is no header.
   *
   * @param headers the headers to add to.
   * @param line a line of the trailer, e.g. {@code x-amz-checksum-crc32:AAAAAA==}.
   */
  static void parse(Map<String, String> headers, String line) {
    int separator = line.indexOf(':');
    if (separator > 0) {
      headers.put(line.substring(0, separator).trim().toLowerCase(Locale.ROOT), line.substring(separator + 1).trim());
    }
  }

}
