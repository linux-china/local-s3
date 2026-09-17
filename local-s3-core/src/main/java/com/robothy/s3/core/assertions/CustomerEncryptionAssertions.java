package com.robothy.s3.core.assertions;

import com.robothy.s3.core.exception.LocalS3RequestException;
import com.robothy.s3.core.exception.S3ErrorCode;
import com.robothy.s3.core.model.internal.CustomerEncryption;

/**
 * Checks that a request that reads an object, or adds to an upload, provides the customer key that the object or the
 * upload was stored with, see {@linkplain CustomerEncryption}.
 */
public final class CustomerEncryptionAssertions {

  private CustomerEncryptionAssertions() {
  }

  /**
   * Assert that a request provides the key that the content it reads was encrypted with.
   *
   * @param stored the encryption the content was stored with; {@code null} if it wasn't encrypted with a customer key.
   * @param provided the encryption the request provides; {@code null} if it provides none.
   * @throws LocalS3RequestException {@code InvalidRequest} if the request provides no key for an encrypted content, or
   *     a key for a content that isn't encrypted with one; {@code AccessDenied} if it provides another key.
   */
  public static void assertKeyProvided(CustomerEncryption stored, CustomerEncryption provided) {
    if (stored == null) {
      if (provided != null) {
        throw new LocalS3RequestException(S3ErrorCode.InvalidRequest,
            "The encryption parameters are not applicable to this object.");
      }
      return;
    }
    if (provided == null) {
      throw new LocalS3RequestException(S3ErrorCode.InvalidRequest,
          "The object was stored using a form of Server Side Encryption. "
              + "The correct parameters must be provided to retrieve the object.");
    }
    if (!stored.matches(provided)) {
      throw new LocalS3RequestException(S3ErrorCode.AccessDenied, "Access Denied");
    }
  }

}
