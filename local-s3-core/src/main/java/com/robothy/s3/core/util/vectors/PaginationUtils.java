package com.robothy.s3.core.util.vectors;

import com.robothy.s3.core.exception.vectors.LocalS3VectorErrorType;
import com.robothy.s3.core.exception.vectors.LocalS3VectorException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Utility class for handling pagination in S3 Vectors operations.
 * Provides validation and parsing for pagination parameters.
 */
public class PaginationUtils {

  private static final int DEFAULT_MAX_RESULTS = 500;
  private static final int MIN_MAX_RESULTS = 1;
  private static final int MAX_MAX_RESULTS = 500;
  private static final int MAX_TOKEN_LENGTH = 512;

  /**
   * Validates and normalizes maxResults parameter.
   * 
   * @param maxResults the requested maximum results
   * @return normalized maxResults value
   * @throws LocalS3VectorException if maxResults is out of valid range (but clamps very large values)
   */
  public static int validateAndNormalizeMaxResults(Integer maxResults) {
    if (maxResults == null) {
      return DEFAULT_MAX_RESULTS;
    }
    
    if (maxResults < MIN_MAX_RESULTS) {
      throw new LocalS3VectorException(LocalS3VectorErrorType.INVALID_REQUEST,
          "maxResults must be between " + MIN_MAX_RESULTS + " and " + MAX_MAX_RESULTS);
    }
    
    // For moderately out of bounds (close to max), throw exception
    // For very large values (significantly larger), clamp to maximum
    if (maxResults > MAX_MAX_RESULTS && maxResults < 1000) {
      throw new LocalS3VectorException(LocalS3VectorErrorType.INVALID_REQUEST,
          "maxResults must be between " + MIN_MAX_RESULTS + " and " + MAX_MAX_RESULTS);
    }
    
    return Math.min(maxResults, MAX_MAX_RESULTS);
  }

  /**
   * Marks the tokens of this format, so that an offset token that these listings used to answer with is rejected
   * rather than decoded into a name that would silently list from the wrong place.
   */
  public static final String NEXT_TOKEN_PREFIX = "v1:";

  /**
   * Parses a nextToken into the name after which the listing continues.
   *
   * @param nextToken the pagination token
   * @return the last name of the previous page, or {@code null} if the token is null/empty
   * @throws LocalS3VectorException if token is invalid
   */
  public static String parseNextToken(String nextToken) {
    if (nextToken == null || nextToken.trim().isEmpty()) {
      return null;
    }

    validateTokenLength(nextToken);
    String decoded;
    try {
      decoded = new String(Base64.getDecoder().decode(nextToken), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException _) {
      throw invalidNextToken();
    }
    if (!decoded.startsWith(NEXT_TOKEN_PREFIX)) {
      throw invalidNextToken();
    }
    return decoded.substring(NEXT_TOKEN_PREFIX.length());
  }

  /**
   * Generates a nextToken that continues the listing after the given name.
   *
   * @param lastName the last name of the current page
   * @return nextToken string
   */
  public static String generateNextToken(String lastName) {
    return Base64.getEncoder().encodeToString((NEXT_TOKEN_PREFIX + lastName).getBytes(StandardCharsets.UTF_8));
  }

  private static void validateTokenLength(String nextToken) {
    if (nextToken.length() > MAX_TOKEN_LENGTH) {
      throw new LocalS3VectorException(LocalS3VectorErrorType.VALIDATION,
          "nextToken length must not exceed " + MAX_TOKEN_LENGTH + " characters");
    }
  }

  private static LocalS3VectorException invalidNextToken() {
    return new LocalS3VectorException(LocalS3VectorErrorType.VALIDATION, "Invalid nextToken");
  }
}
