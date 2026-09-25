package com.robothy.s3.rest.utils;

import com.robothy.netty.http.HttpRequest;
import com.robothy.netty.http.HttpResponse;
import com.robothy.s3.core.exception.LocalS3InvalidArgumentException;
import com.robothy.s3.core.exception.LocalS3RequestException;
import com.robothy.s3.core.exception.S3ErrorCode;
import com.robothy.s3.core.model.internal.CustomerEncryption;
import com.robothy.s3.rest.constants.AmzHeaderNames;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.function.Function;

/**
 * Reads the customer-provided encryption key (SSE-C) of a request from its
 * {@code x-amz-server-side-encryption-customer-*} headers, and writes the encryption of an object to the headers of a
 * response. The key is validated like Amazon S3 validates it, but nothing is encrypted; see
 * {@linkplain CustomerEncryption}.
 */
public final class CustomerEncryptionHeaders {

  private static final int KEY_LENGTH = 32;

  private static final String AMZ_PREFIX = "x-amz-";

  private CustomerEncryptionHeaders() {
  }

  /**
   * Read the customer key of a request.
   *
   * @param request the request.
   * @return the encryption; {@code null} if the request sends none of the headers.
   * @throws LocalS3InvalidArgumentException if a header is missing, or the key or its digest is invalid.
   * @throws LocalS3RequestException {@code InvalidEncryptionAlgorithmError} if the algorithm isn't {@code AES256}.
   */
  public static CustomerEncryption fromRequest(HttpRequest request) {
    return fromRequest(request, "");
  }

  /**
   * Read the customer key of the source object of a copy from the
   * {@code x-amz-copy-source-server-side-encryption-customer-*} headers.
   *
   * @param request the {@code CopyObject} or {@code UploadPartCopy} request.
   * @return the encryption; {@code null} if the request sends none of the headers.
   */
  public static CustomerEncryption fromCopySourceRequest(HttpRequest request) {
    return fromRequest(request, AmzHeaderNames.X_AMZ_COPY_SOURCE_PREFIX);
  }

  /**
   * Read the customer key of a request from the headers whose names start with a prefix instead of {@code x-amz-}.
   */
  private static CustomerEncryption fromRequest(HttpRequest request, String prefix) {
    return fromValues(name -> request.header(name).orElse(null), prefix);
  }

  /**
   * Read the customer key from named values, e.g. the {@code x-amz-server-side-encryption-customer-*} fields of a
   * {@code POST} form.
   *
   * @param values the value of a header name; {@code null} if it has none.
   * @return the encryption; {@code null} if none of the values are given.
   * @throws LocalS3InvalidArgumentException if a value is missing, or the key or its digest is invalid.
   * @throws LocalS3RequestException {@code InvalidEncryptionAlgorithmError} if the algorithm isn't {@code AES256}.
   */
  public static CustomerEncryption fromValues(Function<String, String> values) {
    return fromValues(values, "");
  }

  private static CustomerEncryption fromValues(Function<String, String> values, String prefix) {
    String algorithmHeader = headerName(prefix, AmzHeaderNames.X_AMZ_SSE_CUSTOMER_ALGORITHM);
    String keyHeader = headerName(prefix, AmzHeaderNames.X_AMZ_SSE_CUSTOMER_KEY);
    String keyMd5Header = headerName(prefix, AmzHeaderNames.X_AMZ_SSE_CUSTOMER_KEY_MD5);
    String algorithm = values.apply(algorithmHeader);
    String key = values.apply(keyHeader);
    String keyMd5 = values.apply(keyMd5Header);
    if (algorithm == null && key == null && keyMd5 == null) {
      return null;
    }
    if (algorithm == null) {
      throw new LocalS3InvalidArgumentException(algorithmHeader, null,
          "Requests specifying Server Side Encryption with Customer provided keys must provide a valid encryption "
              + "algorithm.");
    }
    if (!CustomerEncryption.AES256.equals(algorithm.trim())) {
      throw new LocalS3RequestException(S3ErrorCode.InvalidEncryptionAlgorithmError);
    }
    if (key == null) {
      throw new LocalS3InvalidArgumentException(keyHeader, null,
          "Requests specifying Server Side Encryption with Customer provided keys must provide an appropriate secret "
              + "key.");
    }
    if (keyMd5 == null) {
      throw new LocalS3InvalidArgumentException(keyMd5Header, null,
          "Requests specifying Server Side Encryption with Customer provided keys must provide the client calculated "
              + "MD5 of the secret key.");
    }
    byte[] keyBytes;
    try {
      keyBytes = Base64.getDecoder().decode(key.trim());
    } catch (IllegalArgumentException e) {
      keyBytes = null;
    }
    if (keyBytes == null || keyBytes.length != KEY_LENGTH) {
      throw new LocalS3InvalidArgumentException(keyHeader, null,
          "The secret key was invalid for the specified algorithm.");
    }
    String computedMd5 = Base64.getEncoder().encodeToString(md5(keyBytes));
    if (!computedMd5.equals(keyMd5.trim())) {
      throw new LocalS3InvalidArgumentException(keyMd5Header, keyMd5,
          "The calculated MD5 hash of the key did not match the hash that was provided.");
    }
    return new CustomerEncryption(CustomerEncryption.AES256, computedMd5);
  }

  /**
   * Add the customer key of an object to a response, i.e. its algorithm and the digest of the key, never the key.
   *
   * @param response the response.
   * @param encryption the encryption of the object; {@code null} if it has none.
   */
  public static void addHeaders(HttpResponse response, CustomerEncryption encryption) {
    if (encryption == null) {
      return;
    }
    response.putHeader(AmzHeaderNames.X_AMZ_SSE_CUSTOMER_ALGORITHM, encryption.algorithm())
        .putHeader(AmzHeaderNames.X_AMZ_SSE_CUSTOMER_KEY_MD5, encryption.keyMd5());
  }

  /**
   * The name of a header with a prefix, e.g. {@code x-amz-copy-source-server-side-encryption-customer-key} for
   * {@code x-amz-server-side-encryption-customer-key}.
   */
  private static String headerName(String prefix, String name) {
    return prefix.isEmpty() ? name : prefix + name.substring(AMZ_PREFIX.length());
  }

  private static byte[] md5(byte[] bytes) {
    try {
      return MessageDigest.getInstance("MD5").digest(bytes);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("MD5 is not supported.", e);
    }
  }

}
