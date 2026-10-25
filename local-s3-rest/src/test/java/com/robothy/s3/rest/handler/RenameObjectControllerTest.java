package com.robothy.s3.rest.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.robothy.s3.core.exception.LocalS3InvalidArgumentException;
import org.junit.jupiter.api.Test;

class RenameObjectControllerTest {

  @Test
  void sourceKey() {
    assertEquals("a.txt", RenameObjectController.sourceKey("my-bucket", "/my-bucket/a.txt"));
    assertEquals("a.txt", RenameObjectController.sourceKey("my-bucket", "a.txt"));
    assertEquals("a+b.txt", RenameObjectController.sourceKey("my-bucket", "/my-bucket/a+b.txt"));
    assertEquals("a+b c.txt", RenameObjectController.sourceKey("my-bucket", "/my-bucket/a%2Bb%20c.txt"));
    assertThrows(LocalS3InvalidArgumentException.class,
        () -> RenameObjectController.sourceKey("my-bucket", "/my-bucket/a%zz"));
  }

}
