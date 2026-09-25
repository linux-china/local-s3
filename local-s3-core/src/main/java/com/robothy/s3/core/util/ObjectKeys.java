package com.robothy.s3.core.util;

import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * The order of object keys that S3 lists them in: the binary order of their UTF-8 bytes, which is the order of their
 * code points. {@linkplain String#compareTo(String)} compares UTF-16 code units instead, which sorts the surrogate
 * pairs of the supplementary characters (U+10000 and up) before U+E000..U+FFFF, e.g. {@code 😀} before {@code U+FFFD}.
 */
public final class ObjectKeys {

  private ObjectKeys() {
  }

  /**
   * Compares strings in the order of their code points, i.e. of their UTF-8 bytes. A lone surrogate, which has no
   * UTF-8 encoding, sorts like the surrogate pairs do: after every other character of the BMP.
   */
  public static final Comparator<String> ORDER = ObjectKeys::compare;

  /**
   * Compare two keys in the order of their code points.
   *
   * @param left a key.
   * @param right another key.
   * @return a negative number, zero or a positive number as {@code left} sorts before, like or after {@code right}.
   */
  public static int compare(String left, String right) {
    int length = Math.min(left.length(), right.length());
    for (int i = 0; i < length; i++) {
      char l = left.charAt(i);
      char r = right.charAt(i);
      if (l != r) {
        return rank(l) - rank(r);
      }
    }
    return left.length() - right.length();
  }

  /**
   * The rank of a UTF-16 code unit in code point order: the surrogates move after U+E000..U+FFFF, which is where the
   * code points they encode sort. Code units that differ first at a surrogate either both are surrogates of the same
   * kind or one of them is a BMP character, so ranking the units one by one orders whole code points.
   */
  private static int rank(char c) {
    if (c < Character.MIN_SURROGATE) {
      return c;
    }
    return c <= Character.MAX_SURROGATE ? c + 0x2000 : c - 0x800;
  }

  /**
   * The code unit that follows {@code c} in {@linkplain #ORDER}, or {@code -1} if {@code c} is the last one.
   */
  static int nextCodeUnit(char c) {
    return switch (c) {
      case Character.MAX_SURROGATE -> -1;
      case Character.MIN_SURROGATE - 1 -> Character.MIN_SURROGATE + 0x800; // U+D7FF -> U+E000
      case Character.MAX_VALUE -> Character.MIN_SURROGATE; // U+FFFF -> the surrogates
      default -> c + 1;
    };
  }

  /**
   * The least string that sorts, in {@linkplain #ORDER}, after every string that starts with {@code prefix}: the
   * prefix with its last code unit that isn't the greatest one advanced, and the code units after it dropped.
   *
   * @param prefix the prefix.
   * @return the successor, or {@code null} if the prefix is empty or only the greatest code units, so that no string
   *     sorts after all strings starting with it.
   */
  public static String prefixSuccessor(String prefix) {
    for (int i = prefix.length() - 1; i >= 0; i--) {
      int next = nextCodeUnit(prefix.charAt(i));
      if (next >= 0) {
        return prefix.substring(0, i) + (char) next;
      }
    }
    return null;
  }

  /**
   * Create an empty concurrent map of keys in {@linkplain #ORDER}.
   */
  public static <V> ConcurrentSkipListMap<String, V> newMap() {
    return new ConcurrentSkipListMap<>(ORDER);
  }

  /**
   * Create a concurrent map of keys in {@linkplain #ORDER} with the entries of {@code entries}.
   */
  public static <V> ConcurrentSkipListMap<String, V> newMap(Map<String, ? extends V> entries) {
    ConcurrentSkipListMap<String, V> map = newMap();
    map.putAll(entries);
    return map;
  }

}
