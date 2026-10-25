package com.robothy.s3.core.util.vectors;

import static org.junit.jupiter.api.Assertions.*;
import com.robothy.s3.core.exception.vectors.LocalS3VectorException;
import com.robothy.s3.core.exception.vectors.LocalS3VectorErrorType;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class PaginationUtilsTest {

  @Test
  void validateAndNormalizeMaxResults_withNull_returnsDefault() {
    int result = PaginationUtils.validateAndNormalizeMaxResults(null);
    
    assertEquals(500, result);
  }

  @Test
  void validateAndNormalizeMaxResults_withValidValue_returnsValue() {
    int result = PaginationUtils.validateAndNormalizeMaxResults(100);
    
    assertEquals(100, result);
  }

  @Test
  void validateAndNormalizeMaxResults_withMinimumValue_returnsValue() {
    int result = PaginationUtils.validateAndNormalizeMaxResults(1);
    
    assertEquals(1, result);
  }

  @Test
  void validateAndNormalizeMaxResults_withMaximumValue_returnsValue() {
    int result = PaginationUtils.validateAndNormalizeMaxResults(500);
    
    assertEquals(500, result);
  }

  @Test
  void validateAndNormalizeMaxResults_withZero_throwsException() {
    LocalS3VectorException exception = assertThrows(LocalS3VectorException.class,
        () -> PaginationUtils.validateAndNormalizeMaxResults(0));
    
    assertEquals(LocalS3VectorErrorType.INVALID_REQUEST, exception.getErrorType());
    assertEquals("maxResults must be between 1 and 500", exception.getMessage());
  }

  @Test
  void validateAndNormalizeMaxResults_withNegativeValue_throwsException() {
    LocalS3VectorException exception = assertThrows(LocalS3VectorException.class,
        () -> PaginationUtils.validateAndNormalizeMaxResults(-1));
    
    assertEquals(LocalS3VectorErrorType.INVALID_REQUEST, exception.getErrorType());
    assertEquals("maxResults must be between 1 and 500", exception.getMessage());
  }

  @Test
  void validateAndNormalizeMaxResults_withValueTooLarge_throwsException() {
    LocalS3VectorException exception = assertThrows(LocalS3VectorException.class,
        () -> PaginationUtils.validateAndNormalizeMaxResults(501));
    
    assertEquals(LocalS3VectorErrorType.INVALID_REQUEST, exception.getErrorType());
    assertEquals("maxResults must be between 1 and 500", exception.getMessage());
  }

  @Test
  void parseNextToken_withNull_returnsNull() {
    assertNull(PaginationUtils.parseNextToken(null));
  }

  @Test
  void parseNextToken_withEmptyString_returnsNull() {
    assertNull(PaginationUtils.parseNextToken(""));
  }

  @Test
  void parseNextToken_withWhitespaceOnly_returnsNull() {
    assertNull(PaginationUtils.parseNextToken("   "));
  }

  @Test
  void parseNextToken_roundTripWithGeneratedToken_returnsLastName() {
    String token = PaginationUtils.generateNextToken("index-b");

    assertEquals("index-b", PaginationUtils.parseNextToken(token));
  }

  @Test
  void parseNextToken_withInvalidBase64_throwsException() {
    LocalS3VectorException exception = assertThrows(LocalS3VectorException.class,
        () -> PaginationUtils.parseNextToken("invalid-base64!!!"));

    assertEquals(LocalS3VectorErrorType.VALIDATION, exception.getErrorType());
    assertEquals("Invalid nextToken", exception.getMessage());
  }

  @Test
  void parseNextToken_withOffsetToken_throwsException() {
    String negativeOffset = Base64.getEncoder().encodeToString("-1".getBytes());

    LocalS3VectorException exception = assertThrows(LocalS3VectorException.class,
        () -> PaginationUtils.parseNextToken(negativeOffset));

    assertEquals(LocalS3VectorErrorType.VALIDATION, exception.getErrorType());
    assertEquals("Invalid nextToken", exception.getMessage());
  }

  @Test
  void parseNextToken_withTooLongToken_throwsException() {
    String longToken = "a".repeat(513);

    LocalS3VectorException exception = assertThrows(LocalS3VectorException.class,
        () -> PaginationUtils.parseNextToken(longToken));

    assertEquals(LocalS3VectorErrorType.VALIDATION, exception.getErrorType());
    assertEquals("nextToken length must not exceed 512 characters", exception.getMessage());
  }

  @Test
  void validateAndNormalizeMaxResults_withLargeValue_clamsToMaximum() {
    int result = PaginationUtils.validateAndNormalizeMaxResults(1000);
    
    assertEquals(500, result);
  }
}
