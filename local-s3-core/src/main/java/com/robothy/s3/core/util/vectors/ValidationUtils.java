package com.robothy.s3.core.util.vectors;

import com.robothy.s3.core.exception.vectors.LocalS3VectorErrorType;
import com.robothy.s3.core.exception.vectors.LocalS3VectorException;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Utility class for validating S3 Vectors operation parameters.
 * Provides consistent validation logic for common parameters.
 */
public class ValidationUtils {

  private static final int MIN_PREFIX_LENGTH = 1;
  private static final int MAX_PREFIX_LENGTH = 63;

  /**
   * The most tags that a vector bucket or index can have.
   */
  public static final int MAX_TAGS = 50;

  private static final int MAX_TAG_KEY_LENGTH = 128;
  private static final int MAX_TAG_VALUE_LENGTH = 256;

  /**
   * The prefix of the tag keys that AWS reserves for its own tags.
   */
  private static final String RESERVED_TAG_KEY_PREFIX = "aws:";

  /**
   * Validates prefix parameter for list operations.
   * 
   * @param prefix the prefix to validate
   * @throws LocalS3VectorException if prefix length is invalid
   */
  public static void validatePrefix(String prefix) {
    if (prefix == null) {
      return;
    }
    
    if (prefix.length() < MIN_PREFIX_LENGTH || prefix.length() > MAX_PREFIX_LENGTH) {
      throw new LocalS3VectorException(LocalS3VectorErrorType.INVALID_REQUEST,
          "prefix length must be between " + MIN_PREFIX_LENGTH + " and " + MAX_PREFIX_LENGTH + " characters");
    }
  }

  /**
   * Checks if prefix filtering should be applied.
   * 
   * @param prefix the prefix parameter
   * @return true if prefix is non-null and non-empty
   */
  public static boolean shouldApplyPrefixFilter(String prefix) {
    return prefix != null && !prefix.trim().isEmpty();
  }

  /**
   * Validates that a list is not null or empty.
   * 
   * @param list the list to validate
   * @param message the error message if validation fails
   * @throws LocalS3VectorException if list is null or empty
   */
  public static void validateNotNullOrEmpty(List<?> list, String message) {
    if (list == null || list.isEmpty()) {
      throw new LocalS3VectorException(LocalS3VectorErrorType.INVALID_REQUEST, message);
    }
  }

  /**
   * Validates that a string is not null or blank.
   * 
   * @param value the string to validate
   * @param message the error message if validation fails
   * @throws LocalS3VectorException if string is null or blank
   */
  public static void validateNotBlank(String value, String message) {
    if (value == null || value.trim().isEmpty()) {
      throw new LocalS3VectorException(LocalS3VectorErrorType.INVALID_REQUEST, message);
    }
  }

  /**
   * Validates the tags to add to a vector bucket or index: at most {@value #MAX_TAGS} of them, each with a key of 1 to
   * 128 characters that doesn't start with {@code aws:}, and a value of at most 256 characters.
   *
   * @param tags the tags to validate; {@code null} for none.
   * @throws LocalS3VectorException if a tag is invalid, or there are too many of them.
   */
  public static void validateTags(Map<String, String> tags) {
    if (tags == null) {
      return;
    }
    validateTagCount(tags.size());
    tags.forEach((key, value) -> {
      validateTagKey(key);
      if (value == null || value.length() > MAX_TAG_VALUE_LENGTH) {
        throw new LocalS3VectorException(LocalS3VectorErrorType.VALIDATION,
            "The value of tag '" + key + "' must be at most " + MAX_TAG_VALUE_LENGTH + " characters");
      }
    });
  }

  /**
   * Validates the keys of the tags to remove from a vector bucket or index.
   *
   * @param tagKeys the tag keys to validate.
   * @throws LocalS3VectorException if there is no key, or a key is invalid.
   */
  public static void validateTagKeys(Collection<String> tagKeys) {
    if (tagKeys == null || tagKeys.isEmpty()) {
      throw new LocalS3VectorException(LocalS3VectorErrorType.VALIDATION, "tagKeys is required");
    }
    tagKeys.forEach(ValidationUtils::validateTagKey);
  }

  /**
   * Validates the number of tags that a vector bucket or index would have.
   *
   * @param count the number of tags.
   * @throws LocalS3VectorException if there are more than {@value #MAX_TAGS}.
   */
  public static void validateTagCount(int count) {
    if (count > MAX_TAGS) {
      throw new LocalS3VectorException(LocalS3VectorErrorType.VALIDATION,
          "A resource can have at most " + MAX_TAGS + " tags");
    }
  }

  private static void validateTagKey(String key) {
    if (key == null || key.isEmpty() || key.length() > MAX_TAG_KEY_LENGTH) {
      throw new LocalS3VectorException(LocalS3VectorErrorType.VALIDATION,
          "Tag keys must be between 1 and " + MAX_TAG_KEY_LENGTH + " characters");
    }
    if (key.regionMatches(true, 0, RESERVED_TAG_KEY_PREFIX, 0, RESERVED_TAG_KEY_PREFIX.length())) {
      throw new LocalS3VectorException(LocalS3VectorErrorType.VALIDATION,
          "Tag keys can't start with '" + RESERVED_TAG_KEY_PREFIX + "': " + key);
    }
  }
}
