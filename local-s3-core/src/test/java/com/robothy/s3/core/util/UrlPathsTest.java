package com.robothy.s3.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class UrlPathsTest {

  @Test
  void decode() {
    assertNull(UrlPaths.decode(null));
    assertEquals("a+b.txt", UrlPaths.decode("a+b.txt"));
    assertEquals("a+b.txt", UrlPaths.decode("a%2Bb.txt"));
    assertEquals("a b/c+d", UrlPaths.decode("a%20b/c+d"));
    assertEquals("目录/文件", UrlPaths.decode("%E7%9B%AE%E5%BD%95/%E6%96%87%E4%BB%B6"));
    assertThrows(IllegalArgumentException.class, () -> UrlPaths.decode("a%zz"));
  }

}
