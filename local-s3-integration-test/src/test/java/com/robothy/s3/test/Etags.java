package com.robothy.s3.test;

import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * The entity tags that Amazon S3 answers, which the AWS SDK hands over as they are: a quoted string, e.g.
 * {@code "5d41402abc4b2a76b9719d911017c592"}, in the {@code ETag} header and in the {@code ETag} elements of the XML
 * documents alike. Tests compare them with these, so that an entity tag without its quotes fails as well as one with
 * the wrong digest.
 */
final class Etags {

  private Etags() {
  }

  /**
   * The entity tag of a value, e.g. of a digest or of a composite entity tag: the value in double quotes.
   */
  static String quoted(String value) {
    return "\"" + value + "\"";
  }

  /**
   * The entity tag of an object stored at once, i.e. the quoted MD5 digest of its content.
   */
  static String md5(String content) {
    return quoted(Digests.md5Hex(content));
  }

  /**
   * The entity tag of an object stored at once, i.e. the quoted MD5 digest of its content.
   */
  static String md5(byte[] content) {
    return quoted(Digests.md5Hex(content));
  }

  /**
   * The value of an entity tag, i.e. without its quotes, which the entity tag must have.
   */
  static String unquoted(String etag) {
    assertTrue(etag != null && etag.length() >= 2 && etag.startsWith("\"") && etag.endsWith("\""),
        "The entity tag " + etag + " is quoted.");
    return etag.substring(1, etag.length() - 1);
  }

}
