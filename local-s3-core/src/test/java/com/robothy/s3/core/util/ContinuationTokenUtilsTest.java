package com.robothy.s3.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import com.robothy.s3.core.exception.LocalS3InvalidArgumentException;
import com.robothy.s3.core.exception.S3ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ContinuationTokenUtilsTest {

  @ValueSource(strings = {"a.txt", "dir/key", "", " ", "中文 key.txt", "a+b/c=d", "😀.txt"})
  @ParameterizedTest
  void encodedTokenDecodesBackToTheKey(String key) {
    assertEquals(key, ContinuationTokenUtils.decode(ContinuationTokenUtils.encode(key)));
  }

  @ValueSource(strings = {"a.txt", "dir/key", "中文 key.txt"})
  @ParameterizedTest
  void tokenDoesNotRevealTheKey(String key) {
    assertNotEquals(key, ContinuationTokenUtils.encode(key));
  }

  @Test
  void aCompleteListingHasNoToken() {
    assertNull(ContinuationTokenUtils.encode(null));
    assertNull(ContinuationTokenUtils.decode(null));
  }

  /**
   * A plain object key, which earlier versions answered with, is rejected rather than decoded into a key that
   * would list from the wrong place. "abcd" is valid base64, so the marker of the format is what rejects it.
   */
  @ValueSource(strings = {"a.txt", "abcd", "not a token", "!!!", "dir/key"})
  @ParameterizedTest
  void aTokenThatWasNotEncodedIsRejected(String token) {
    LocalS3InvalidArgumentException thrown =
        assertThrows(LocalS3InvalidArgumentException.class, () -> ContinuationTokenUtils.decode(token));
    assertEquals(S3ErrorCode.InvalidArgument, thrown.getS3ErrorCode());
    assertEquals("continuation-token", thrown.getArgumentName());
    assertEquals(token, thrown.getArgumentValue());
  }

}
