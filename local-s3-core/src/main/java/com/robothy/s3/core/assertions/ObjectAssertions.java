package com.robothy.s3.core.assertions;

import com.robothy.s3.core.exception.InvalidObjectKeyException;
import com.robothy.s3.core.exception.LocalS3RequestException;
import com.robothy.s3.core.exception.ObjectNotExistException;
import com.robothy.s3.core.exception.S3ErrorCode;
import com.robothy.s3.core.model.internal.BucketMetadata;
import com.robothy.s3.core.model.internal.ObjectMetadata;
import com.robothy.s3.core.util.Strings;
import java.util.HashSet;
import java.util.Set;

public class ObjectAssertions {

  /**
   * The most tags that an object can have.
   */
  public static final int MAX_TAG_COUNT = 10;

  /**
   * The most characters, i.e. Unicode code points, of the key of a tag.
   */
  public static final int MAX_TAG_KEY_LENGTH = 128;

  /**
   * The most characters, i.e. Unicode code points, of the value of a tag.
   */
  public static final int MAX_TAG_VALUE_LENGTH = 256;

  public static void assertObjectKeyIsValid(String key) {
    if (Strings.isBlank(key)) {
      throw new InvalidObjectKeyException(key);
    }
  }

  public static ObjectMetadata assertObjectExists(BucketMetadata bucketMetadata, String key) {
    return bucketMetadata.getObjectMetadata(key).orElseThrow(() -> new ObjectNotExistException(key));
  }

  /**
   * Assert that tags can be the tags of an object, within the
   * <a href="https://docs.aws.amazon.com/AmazonS3/latest/userguide/object-tagging.html">limits</a> of Amazon S3: at
   * most 10 tags, each with a key of 1 to 128 characters that no other tag has, and a value of at most 256.
   *
   * @param tagging the tags, each a pair of a key and a value; {@code null} for none.
   * @throws LocalS3RequestException {@code InvalidTag} if they can't.
   */
  public static void assertObjectTaggingIsValid(String[][] tagging) {
    if (tagging == null) {
      return;
    }
    if (tagging.length > MAX_TAG_COUNT) {
      throw invalidTag("Object tags cannot be greater than " + MAX_TAG_COUNT + ".");
    }
    Set<String> keys = new HashSet<>();
    for (String[] tag : tagging) {
      String key = tag[0];
      String value = tag[1] == null ? "" : tag[1];
      if (key == null || key.isEmpty() || key.codePointCount(0, key.length()) > MAX_TAG_KEY_LENGTH) {
        throw invalidTag("The TagKey you have provided is invalid.");
      }
      if (value.codePointCount(0, value.length()) > MAX_TAG_VALUE_LENGTH) {
        throw invalidTag("The TagValue you have provided is invalid.");
      }
      if (!keys.add(key)) {
        throw invalidTag("Cannot provide multiple Tags with the same key.");
      }
    }
  }

  private static LocalS3RequestException invalidTag(String message) {
    return new LocalS3RequestException(S3ErrorCode.InvalidTag, message);
  }

}
