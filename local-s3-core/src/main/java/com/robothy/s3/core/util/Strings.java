package com.robothy.s3.core.util;

/**
 * Checks of strings that treat {@code null} like an empty string.
 */
public final class Strings {

  private Strings() {
  }

  /**
   * Whether a string is {@code null}, empty or only whitespace, as {@linkplain Character#isWhitespace(int)} defines it.
   *
   * @param value the string; may be {@code null}.
   * @return {@code true} if the string holds no other character than whitespace.
   */
  public static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  /**
   * Whether a string holds a character other than whitespace; the opposite of {@linkplain #isBlank(String)}.
   *
   * @param value the string; may be {@code null}.
   * @return {@code true} if the string holds a character other than whitespace.
   */
  public static boolean isNotBlank(String value) {
    return !isBlank(value);
  }

}
