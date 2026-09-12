package com.robothy.s3.core.util;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.util.List;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class S3ObjectUtilsTest {

  @CsvSource({
      "a,a",
      "a/b,a/b",
      "a/#b,a/%23b",
      "/a@,/a%40",
      "a@/b,a%40/b",
      "/a/,/a/"
  })
  @ParameterizedTest
  void urlEncodeEscapeSlash(String input, String expected) {
    assertEquals(expected, S3ObjectUtils.urlEncodeEscapeSlash(input));
  }

  /**
   * The entity tag of an object uploaded in parts is the digest of the concatenated digests of its parts,
   * with the number of parts appended. The expected values are computed elsewhere, so that an entity tag
   * that this implementation and the expectation are both wrong about doesn't pass.
   */
  @Test
  void compositeEtag() {
    // md5(md5("Hello") + md5("World")), where the digests are concatenated as bytes, not as hex.
    assertEquals("64d1e57a34042883053ec1c5d8d60167-2",
        S3ObjectUtils.compositeEtag(List.of(md5("Hello"), md5("World"))));
    assertEquals("4054c6f7787ecb7b323323155b0c0583-3",
        S3ObjectUtils.compositeEtag(List.of(md5("a"), md5("b"), md5("c"))));
    // An upload of a single part gets a composite entity tag too, with the suffix "-1".
    assertEquals("49c24cf3c5af9ba03cec39ee4aac4f77-1", S3ObjectUtils.compositeEtag(List.of(md5("Hello"))));
  }

  /**
   * The digests are concatenated as bytes, so the entity tag isn't the digest of the concatenated hex
   * digests, which is what an implementation that forgets to decode them computes.
   */
  @Test
  void compositeEtagConcatenatesTheDigestsAsBytes() {
    String hexOfHexes = DigestUtils.md5Hex(
        DigestUtils.md5Hex("Hello") + DigestUtils.md5Hex("World")) + "-2";
    assertNotEquals(hexOfHexes, S3ObjectUtils.compositeEtag(List.of(md5("Hello"), md5("World"))));
  }

  /**
   * The digest of the concatenated parts identifies the same content, but it is not the entity tag: the
   * suffix is what tells an object uploaded in parts from one uploaded at once.
   */
  @Test
  void compositeEtagDiffersFromTheDigestOfTheWholeContent() {
    assertEquals("68e109f0f40ca72a15e05cc22786f8e6", DigestUtils.md5Hex("HelloWorld"));
    assertNotEquals(DigestUtils.md5Hex("HelloWorld"),
        S3ObjectUtils.compositeEtag(List.of(md5("Hello"), md5("World"))));
  }

  @Test
  void digestingStreamDigestsTheContentItReads() throws IOException {
    DigestInputStream stream = S3ObjectUtils.digestingStream(
        new ByteArrayInputStream("Hello".getBytes(StandardCharsets.UTF_8)));
    assertEquals("Hello", new String(stream.readAllBytes(), StandardCharsets.UTF_8));
    assertEquals(DigestUtils.md5Hex("Hello"), Hex.encodeHexString(stream.getMessageDigest().digest()));
  }

  private static byte[] md5(String content) {
    return DigestUtils.md5(content);
  }

}