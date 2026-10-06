package com.robothy.s3.core.model.request;

import com.robothy.s3.core.exception.InvalidRangeException;
import java.util.Optional;

/**
 * A single byte range of a {@code Range} header; see {@code docs/semantics.md#range-requests}.
 */
public class Range {

  private final Long start;

  private final Long end;

  private final Long suffixLength;

  private Range(Long start, Long end, Long suffixLength) {
    this.start = start;
    this.end = end;
    this.suffixLength = suffixLength;
  }

  public static Range of(long start, long end) {
    return new Range(start, end, null);
  }

  public static Range from(long start) {
    return new Range(start, null, null);
  }

  public static Range last(long suffixLength) {
    return new Range(null, null, suffixLength);
  }

  /**
   * Strict, for the headers that Amazon S3 rejects when they don't parse, e.g. {@code x-amz-copy-source-range}.
   */
  public static Range parse(String rangeHeader) {
    return tryParse(rangeHeader).orElseThrow(InvalidRangeException::new);
  }

  /**
   * Empty for a header that a read ignores, as Amazon S3 does; whether the range fits the object is
   * {@linkplain #resolve(long)}'s to decide.
   */
  public static Optional<Range> tryParse(String rangeHeader) {
    if (rangeHeader == null) {
      return Optional.empty();
    }

    String header = rangeHeader.trim();
    if (!header.regionMatches(true, 0, "bytes=", 0, "bytes=".length())) {
      return Optional.empty();
    }

    String spec = header.substring("bytes=".length()).trim();
    if (spec.indexOf(',') >= 0) {
      return Optional.empty();
    }

    int dashIdx = spec.indexOf('-');
    if (dashIdx < 0) {
      return Optional.empty();
    }

    String startStr = spec.substring(0, dashIdx).trim();
    String endStr = spec.substring(dashIdx + 1).trim();

    try {
      if (startStr.isEmpty()) {
        if (endStr.isEmpty()) {
          return Optional.empty();
        }
        // resolve() rejects a suffix length of 0 with 416.
        return Optional.of(last(parseUnsigned(endStr)));
      }

      long start = parseUnsigned(startStr);
      if (endStr.isEmpty()) {
        return Optional.of(from(start));
      }

      long end = parseUnsigned(endStr);
      if (start > end) {
        return Optional.empty();
      }
      return Optional.of(of(start, end));
    } catch (NumberFormatException _) {
      return Optional.empty();
    }
  }

  /** A sign would make {@code Long.parseLong} accept {@code +1} and {@code -1}. */
  private static long parseUnsigned(String value) {
    for (int i = 0; i < value.length(); i++) {
      if (value.charAt(i) < '0' || value.charAt(i) > '9') {
        throw new NumberFormatException(value);
      }
    }
    return Long.parseLong(value);
  }

  /** Empty unless both ends are given. */
  public Optional<Long> length() {
    return start != null && end != null ? Optional.of(end - start + 1) : Optional.empty();
  }

  /**
   * @return the inclusive {@code [start, end]} of the range, clipped to the object.
   * @throws InvalidRangeException if the object can't satisfy the range.
   */
  public long[] resolve(long objectSize) {
    if (objectSize <= 0) {
      throw new InvalidRangeException();
    }

    if (suffixLength != null) {
      if (suffixLength <= 0) {
        throw new InvalidRangeException();
      }
      long start = Math.max(0, objectSize - suffixLength);
      return new long[]{start, objectSize - 1};
    }

    long s = start;
    long e = end == null ? objectSize - 1 : end;
    if (s < 0 || s >= objectSize || s > e) {
      throw new InvalidRangeException();
    }
    if (e >= objectSize) {
      e = objectSize - 1;
    }
    return new long[]{s, e};
  }

}
