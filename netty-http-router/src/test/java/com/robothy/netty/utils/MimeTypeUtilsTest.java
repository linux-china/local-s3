package com.robothy.netty.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import org.junit.jupiter.api.Test;

class MimeTypeUtilsTest {

  @Test
  void mimeTypeByFileExtension() {
    assertEquals("text/html; charset=utf-8", MimeTypeUtils.mimeTypeByFileExtension("html"));
    assertEquals("image/png", MimeTypeUtils.mimeTypeByFileExtension("PNG"));
    assertEquals(MimeTypeUtils.DEFAULT_MIME_TYPE, MimeTypeUtils.mimeTypeByFileExtension("no-such-extension"));
    assertEquals(MimeTypeUtils.DEFAULT_MIME_TYPE, MimeTypeUtils.mimeTypeByFileExtension(null));
  }

  @Test
  void mimeTypeByFileName() {
    assertEquals("text/html; charset=utf-8", MimeTypeUtils.mimeTypeByFileName("static/index.html"));
    assertEquals("application/vnd.apache.parquet", MimeTypeUtils.mimeTypeByFileName("/data/part-0.PARQUET"));
    assertEquals("application/json", MimeTypeUtils.mimeTypeByFileName("C:\\data\\a.b\\conf.json"));
    assertEquals(MimeTypeUtils.DEFAULT_MIME_TYPE, MimeTypeUtils.mimeTypeByFileName("data.no-such-extension"));
    // Never null, even without an extension.
    assertNotNull(MimeTypeUtils.mimeTypeByFileName("static/Makefile"));
    assertNotNull(MimeTypeUtils.mimeTypeByFileName(""));
  }

}
