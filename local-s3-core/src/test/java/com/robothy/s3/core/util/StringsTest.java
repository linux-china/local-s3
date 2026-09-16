package com.robothy.s3.core.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StringsTest {

  @Test
  void aStringOfWhitespaceIsBlank() {
    assertTrue(Strings.isBlank(null));
    assertTrue(Strings.isBlank(""));
    assertTrue(Strings.isBlank(" \t\r\n"));
    assertFalse(Strings.isBlank(" a "));
    assertFalse(Strings.isBlank(" "), "A no-break space isn't whitespace, like in commons-lang3.");
    assertTrue(Strings.isNotBlank("a"));
    assertFalse(Strings.isNotBlank(null));
  }

}
