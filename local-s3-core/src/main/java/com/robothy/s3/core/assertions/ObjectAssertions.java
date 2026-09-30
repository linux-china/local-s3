package com.robothy.s3.core.assertions;

import com.robothy.s3.core.exception.InvalidObjectKeyException;
import com.robothy.s3.core.exception.LocalS3RequestException;
import com.robothy.s3.core.exception.ObjectNotExistException;
import com.robothy.s3.core.exception.S3ErrorCode;
import com.robothy.s3.core.model.internal.BucketMetadata;
import com.robothy.s3.core.model.internal.ObjectMetadata;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
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

  /**
   * The most bytes of the UTF-8 encoding of an object key.
   */
  public static final int MAX_KEY_BYTES = 1024;

  /**
   * The most bytes of the user-defined metadata of an object: the UTF-8 encodings of the names, without their
   * {@code x-amz-meta-} prefix, and of the values, summed.
   */
  public static final int MAX_USER_METADATA_BYTES = 2048;

  public static void assertObjectKeyIsValid(String key) {
    // Amazon S3 takes a key of whitespace alone, e.g. " ", like any other.
    if (key == null || key.isEmpty()) {
      throw new InvalidObjectKeyException(key);
    }
  }

  /**
   * Assert that a key can be the key of an object that is written, within the
   * <a href="https://docs.aws.amazon.com/AmazonS3/latest/userguide/object-keys.html">limit</a> of Amazon S3: at most
   * 1024 bytes in UTF-8.
   *
   * @param key the object key.
   * @throws InvalidObjectKeyException if the key is empty.
   * @throws LocalS3RequestException {@code KeyTooLongError} if the key is longer.
   */
  public static void assertObjectKeyIsWritable(String key) {
    assertObjectKeyIsValid(key);
    // A UTF-8 encoding takes at most 3 bytes per char, so a short key needs no encoding.
    if (key.length() * 3 > MAX_KEY_BYTES && key.getBytes(StandardCharsets.UTF_8).length > MAX_KEY_BYTES) {
      throw new LocalS3RequestException(S3ErrorCode.KeyTooLongError);
    }
  }

  /**
   * Assert that metadata can be the user-defined metadata of an object, within the
   * <a href="https://docs.aws.amazon.com/AmazonS3/latest/userguide/UsingMetadata.html">limit</a> of Amazon S3: the
   * UTF-8 encodings of the names and the values take at most 2 KB.
   *
   * @param userMetadata the metadata, by name without the {@code x-amz-meta-} prefix; {@code null} for none.
   * @throws LocalS3RequestException {@code MetadataTooLarge} if they take more.
   */
  public static void assertUserMetadataIsValid(Map<String, String> userMetadata) {
    if (userMetadata == null || userMetadata.isEmpty()) {
      return;
    }
    long size = 0;
    for (Map.Entry<String, String> entry : userMetadata.entrySet()) {
      size += utf8Length(entry.getKey()) + utf8Length(entry.getValue());
    }
    if (size > MAX_USER_METADATA_BYTES) {
      throw new LocalS3RequestException(S3ErrorCode.MetadataTooLarge,
          "Your metadata headers exceed the maximum allowed metadata size of " + MAX_USER_METADATA_BYTES
              + " bytes; they take " + size + ".");
    }
  }

  private static int utf8Length(String value) {
    return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
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
