package com.robothy.s3.core.util;

import com.robothy.s3.core.exception.LocalS3InvalidArgumentException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * The continuation tokens of
 * <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_ListObjectsV2.html">ListObjectsV2</a>.
 *
 * <p>A token marks the key that a listing continues at. Amazon S3 answers with an opaque, base64 encoded
 * string rather than with the key itself, so code that reads a token, compares it or builds one is written
 * against something that Amazon S3 doesn't promise. The tokens here are opaque in the same way, so that such
 * code fails against LocalS3 as it would against Amazon S3.
 */
public final class ContinuationTokenUtils {

  /**
   * Marks the encoded tokens of this format. A token that doesn't carry it, e.g. a plain object key or a token
   * of another service, is rejected rather than decoded into a key that would silently list from the wrong place.
   */
  private static final String PREFIX = "v1:";

  private ContinuationTokenUtils() {
  }

  /**
   * Encode the key that a listing continues at into an opaque token.
   *
   * @param key the key to continue at; {@code null} if the listing is complete.
   * @return the token to answer with; {@code null} if {@code key} is {@code null}.
   */
  public static String encode(String key) {
    if (key == null) {
      return null;
    }
    return Base64.getEncoder().encodeToString((PREFIX + key).getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Decode the key that a listing continues at from a token of {@linkplain #encode(String)}.
   *
   * @param token the token that the request carries.
   * @return the key to continue at; {@code null} if {@code token} is {@code null}.
   * @throws LocalS3InvalidArgumentException if {@code token} isn't a token of {@linkplain #encode(String)}.
   */
  public static String decode(String token) {
    if (token == null) {
      return null;
    }

    String decoded;
    try {
      decoded = new String(Base64.getDecoder().decode(token), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      throw invalidToken(token);
    }
    if (!decoded.startsWith(PREFIX)) {
      throw invalidToken(token);
    }
    return decoded.substring(PREFIX.length());
  }

  private static LocalS3InvalidArgumentException invalidToken(String token) {
    return new LocalS3InvalidArgumentException("continuation-token", token,
        "The continuation token provided is incorrect");
  }

}
