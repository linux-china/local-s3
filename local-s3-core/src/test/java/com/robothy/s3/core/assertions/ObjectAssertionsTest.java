package com.robothy.s3.core.assertions;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.robothy.s3.core.exception.InvalidObjectKeyException;
import com.robothy.s3.core.exception.LocalS3RequestException;
import com.robothy.s3.core.exception.S3ErrorCode;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The limits of Amazon S3 on the key and the user-defined metadata of an object that is written, both counted in
 * bytes of UTF-8.
 */
class ObjectAssertionsTest {

  @Test
  void aKeyOfUpTo1024BytesIsWritable() {
    assertDoesNotThrow(() -> ObjectAssertions.assertObjectKeyIsWritable("k".repeat(1024)));
    // 341 characters of 3 bytes each.
    assertDoesNotThrow(() -> ObjectAssertions.assertObjectKeyIsWritable("中".repeat(341)));
    // 256 characters outside of the Basic Multilingual Plane, i.e. 512 chars, of 4 bytes each.
    assertDoesNotThrow(() -> ObjectAssertions.assertObjectKeyIsWritable("😀".repeat(256)));
  }

  @Test
  void aLongerKeyIsTooLong() {
    assertKeyTooLong("k".repeat(1025));
    // 342 characters, but 1026 bytes.
    assertKeyTooLong("中".repeat(342));
    assertKeyTooLong("😀".repeat(257));
  }

  @Test
  void anEmptyKeyIsInvalidRatherThanTooLong() {
    assertThrows(InvalidObjectKeyException.class, () -> ObjectAssertions.assertObjectKeyIsWritable(""));
    assertThrows(InvalidObjectKeyException.class, () -> ObjectAssertions.assertObjectKeyIsWritable(null));
  }

  @Test
  void metadataOfUpTo2KbIsValid() {
    assertDoesNotThrow(() -> ObjectAssertions.assertUserMetadataIsValid(null));
    assertDoesNotThrow(() -> ObjectAssertions.assertUserMetadataIsValid(Map.of()));
    assertDoesNotThrow(() -> ObjectAssertions.assertUserMetadataIsValid(Map.of("a", "v".repeat(2047))));
    // The names count as well: 2 * (4 + 1020).
    assertDoesNotThrow(() -> ObjectAssertions.assertUserMetadataIsValid(
        Map.of("name", "v".repeat(1020), "nam2", "v".repeat(1020))));
  }

  @Test
  void largerMetadataIsTooLarge() {
    assertMetadataTooLarge(Map.of("a", "v".repeat(2048)));
    assertMetadataTooLarge(Map.of("name", "v".repeat(1020), "nam2", "v".repeat(1021)));
    // 683 characters of 3 bytes each, 2049 bytes with the name.
    assertMetadataTooLarge(Map.of("a", "中".repeat(683)));
  }

  @Test
  void aValueThatIsNullTakesNoBytes() {
    Map<String, String> metadata = new HashMap<>();
    metadata.put("a".repeat(2048), null);
    assertDoesNotThrow(() -> ObjectAssertions.assertUserMetadataIsValid(metadata));
  }

  private static void assertKeyTooLong(String key) {
    LocalS3RequestException e = assertThrows(LocalS3RequestException.class,
        () -> ObjectAssertions.assertObjectKeyIsWritable(key));
    assertEquals(S3ErrorCode.KeyTooLongError, e.getS3ErrorCode());
  }

  private static void assertMetadataTooLarge(Map<String, String> metadata) {
    LocalS3RequestException e = assertThrows(LocalS3RequestException.class,
        () -> ObjectAssertions.assertUserMetadataIsValid(metadata));
    assertEquals(S3ErrorCode.MetadataTooLarge, e.getS3ErrorCode());
  }

}
