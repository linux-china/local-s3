package com.robothy.s3.core.model.request;

import com.robothy.s3.core.exception.InvalidRangeException;
import java.util.Optional;

/**
 * Represents a parsed byte range from an HTTP {@code Range: bytes=...} header (RFC 9110).
 *
 * <p>Three forms are supported:
 * <ul>
 *   <li>{@code bytes=start-end} — {@link #of(long, long)}</li>
 *   <li>{@code bytes=start-}   — {@link #from(long)}</li>
 *   <li>{@code bytes=-suffix}  — {@link #last(long)}</li>
 * </ul>
 *
 * <p>A header that none of these forms describe, a multipart range like {@code bytes=0-1,5-6} and a unit
 * other than {@code bytes} are all unparsable here. Amazon S3 answers such a request with the whole object,
 * so a caller reading a {@code Range} header uses {@link #tryParse(String)} and ignores an empty answer;
 * {@link #parse(String)} stays strict for the headers that S3 does reject, such as {@code x-amz-copy-source-range}.
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

  /** {@code bytes=start-end} */
  public static Range of(long start, long end) {
    return new Range(start, end, null);
  }

  /** {@code bytes=start-} */
  public static Range from(long start) {
    return new Range(start, null, null);
  }

  /** {@code bytes=-suffixLength} */
  public static Range last(long suffixLength) {
    return new Range(null, null, suffixLength);
  }

  /**
   * Parse the value of a {@code Range} header.
   *
   * @throws InvalidRangeException if the header value cannot be parsed
   */
  public static Range parse(String rangeHeader) {
    return tryParse(rangeHeader).orElseThrow(InvalidRangeException::new);
  }

  /**
   * Parse the value of a {@code Range} header, answering {@link Optional#empty()} instead of failing when the
   * value is not a single satisfiable-looking byte range. RFC 9110 lets a server ignore a {@code Range} header
   * it cannot parse, and Amazon S3 does exactly that: the response is the whole object with status {@code 200}.
   *
   * <p>Whether a syntactically valid range fits the object is not decided here but by {@link #resolve(long)},
   * which fails with {@code 416} for a range that the object cannot satisfy.
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
      // Amazon S3 serves a single range only; it answers a multipart range with the whole object.
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
        // A suffix length of 0 parses; no object can satisfy it, so resolve() rejects it with 416.
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
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
  }

  /** Parse a byte position; a sign makes it no longer a position, so {@code -1} and {@code +1} are both rejected. */
  private static long parseUnsigned(String value) {
    for (int i = 0; i < value.length(); i++) {
      if (value.charAt(i) < '0' || value.charAt(i) > '9') {
        throw new NumberFormatException(value);
      }
    }
    return Long.parseLong(value);
  }

  /**
   * The number of bytes of a {@code bytes=start-end} range, whose both ends are given.
   *
   * @return the length; empty for a {@code bytes=start-} or {@code bytes=-suffix} range.
   */
  public Optional<Long> length() {
    return start != null && end != null ? Optional.of(end - start + 1) : Optional.empty();
  }

  /**
   * Resolve concrete start/end byte positions given the total object size.
   *
   * @return {@code long[2]} with inclusive {@code [start, end]} positions
   * @throws InvalidRangeException if the range cannot be satisfied for this object size
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
