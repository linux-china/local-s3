package com.robothy.s3.core.model.request;

import com.robothy.s3.datatypes.enums.CheckSumAlgorithm;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The checksum that a request stores content with: the algorithm to compute the checksum of the content with, and
 * the checksum that the client computed, which the content must match.
 *
 * @param algorithm the algorithm of the checksum.
 * @param expected the base64 encoded checksum that the client sent, which is read once the content is stored: a
 *     client that sends the content {@code aws-chunked} encoded sends it in a trailing header, after the content.
 *     It supplies {@code null} if the client sent none, which stores the checksum that is computed as it is.
 */
public record RequestChecksum(CheckSumAlgorithm algorithm, Supplier<String> expected) {

  public RequestChecksum {
    Objects.requireNonNull(algorithm, "algorithm");
    Objects.requireNonNull(expected, "expected");
  }

  /**
   * A checksum whose value is known before the content is read, e.g. the one of a header.
   *
   * @param algorithm the algorithm of the checksum.
   * @param expected the base64 encoded checksum; {@code null} to compute one without verifying it.
   * @return the checksum.
   */
  public static RequestChecksum of(CheckSumAlgorithm algorithm, String expected) {
    return new RequestChecksum(algorithm, () -> expected);
  }

}
