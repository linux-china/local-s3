package com.robothy.s3.core.model.request;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.core.exception.InvalidRangeException;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RangeTest {

  @Test
  void parseAcceptsSupportedForms() {
    assertArrayEquals(new long[] {1, 3}, Range.parse("bytes=1-3").resolve(10));
    assertArrayEquals(new long[] {4, 9}, Range.parse("bytes=4-").resolve(10));
    assertArrayEquals(new long[] {7, 9}, Range.parse("bytes=-3").resolve(10));
    assertArrayEquals(new long[] {1, 3}, Range.parse("Bytes=1-3").resolve(10));
    assertArrayEquals(new long[] {1, 3}, Range.parse("bytes= 1 - 3 ").resolve(10));
  }

  @Test
  void tryParseIgnoresHeadersAmazonS3IgnoresToo() {
    // Amazon S3 answers each of these with the whole object, so the header is dropped rather than rejected.
    for (String header : new String[] {null, "", "0-3", "bytes=3", "bytes=-", "bytes=-a", "bytes=abc",
        "bytes=-2-3", "bytes=-1--2", "bytes=-1-2", "bytes=6-2", "bytes=+1-2", "items=1-2",
        "bytes=0-1,5-6", "bytes=0-2, 4-6"}) {
      assertTrue(Range.tryParse(header).isEmpty(), header + " should be ignored");
    }
  }

  @Test
  void parseRejectsWhatTryParseIgnores() {
    // x-amz-copy-source-range is rejected rather than ignored, and keeps reading the strict parse.
    assertThrows(InvalidRangeException.class, () -> Range.parse("0-3"));
    assertThrows(InvalidRangeException.class, () -> Range.parse("bytes=3"));
    assertThrows(InvalidRangeException.class, () -> Range.parse("bytes=-"));
    assertThrows(InvalidRangeException.class, () -> Range.parse("bytes=6-2"));
    assertThrows(InvalidRangeException.class, () -> Range.parse("bytes=0-1,5-6"));
  }

  @Test
  void tryParseAcceptsSyntacticallyValidButUnsatisfiableRanges() {
    // A range that the object cannot satisfy is a 416, decided by resolve() once the object size is known.
    assertThrows(InvalidRangeException.class, () -> Range.tryParse("bytes=-0").orElseThrow().resolve(10));
    assertThrows(InvalidRangeException.class, () -> Range.tryParse("bytes=10-12").orElseThrow().resolve(10));
    assertThrows(InvalidRangeException.class, () -> Range.tryParse("bytes=10-").orElseThrow().resolve(10));
  }

  @Test
  void resolveClampsEndToObjectSize() {
    assertArrayEquals(new long[] {8, 9}, Range.of(8, 99).resolve(10));
    assertArrayEquals(new long[] {0, 9}, Range.last(99).resolve(10));
  }

  @Test
  void resolveRejectsUnsatisfiedRanges() {
    assertThrows(InvalidRangeException.class, () -> Range.of(10, 12).resolve(10));
    assertThrows(InvalidRangeException.class, () -> Range.of(1, 2).resolve(0));
    assertThrows(InvalidRangeException.class, () -> Range.last(0).resolve(10));
  }

  @Test
  void tryParseAnswersTheSameRangeAsParse() {
    assertArrayEquals(Range.parse("bytes=1-3").resolve(10),
        Optional.of("bytes=1-3").flatMap(Range::tryParse).orElseThrow().resolve(10));
  }
}
