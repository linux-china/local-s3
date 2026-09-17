package com.robothy.s3.core.model.internal;

import java.util.Objects;

/**
 * The <a href="https://docs.aws.amazon.com/AmazonS3/latest/userguide/ServerSideEncryptionCustomerKeys.html">server-side
 * encryption with a customer-provided key (SSE-C)</a> of an object version, as a request names it.
 *
 * <p>LocalS3 doesn't encrypt anything: it only records the algorithm and the MD5 digest of the key, never the key
 * itself, so that the object is answered with the same headers as by Amazon S3, and so that a read without the key, or
 * with another key, fails like it does there.
 *
 * @param algorithm the encryption algorithm, which is always {@code AES256}.
 * @param keyMd5 the base64 encoded MD5 digest of the key.
 */
public record CustomerEncryption(String algorithm, String keyMd5) {

  /**
   * The only algorithm of SSE-C.
   */
  public static final String AES256 = "AES256";

  /**
   * Validate the components.
   *
   * @throws NullPointerException if a component is {@code null}.
   */
  public CustomerEncryption {
    Objects.requireNonNull(algorithm, "algorithm");
    Objects.requireNonNull(keyMd5, "keyMd5");
  }

  /**
   * Whether a request provides the key of this encryption.
   *
   * @param provided the encryption of the request; {@code null} if it provides none.
   * @return {@code true} if the request provides a key with the same digest.
   */
  public boolean matches(CustomerEncryption provided) {
    return provided != null && keyMd5.equals(provided.keyMd5());
  }

}
