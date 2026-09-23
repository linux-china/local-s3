package com.robothy.s3.core.s3tables;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Draws the version tokens of the S3 Tables API.
 *
 * <p>A version token is opaque to the client: it reads one, hands it back with the write it based on that read, and
 * the service refuses the write if the token is no longer the current one. Amazon answers an opaque string, so LocalS3
 * does too rather than something a client could be tempted to parse, such as a counter — a test that passes against
 * LocalS3 because it guessed the next token would fail against the real service.
 */
public final class S3TablesVersionTokens {

  private static final SecureRandom RANDOM = new SecureRandom();

  /**
   * The number of random bytes in a token, which is 32 hexadecimal characters.
   */
  private static final int TOKEN_BYTES = 16;

  private S3TablesVersionTokens() {
  }

  /**
   * A new token.
   *
   * @return the token.
   */
  public static String next() {
    byte[] bytes = new byte[TOKEN_BYTES];
    RANDOM.nextBytes(bytes);
    return HexFormat.of().formatHex(bytes);
  }

}
