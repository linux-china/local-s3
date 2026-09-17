package com.robothy.s3.rest.utils;

import com.robothy.netty.http.HttpRequest;
import com.robothy.netty.http.HttpResponse;
import com.robothy.s3.core.exception.LocalS3InvalidArgumentException;
import com.robothy.s3.core.model.internal.CustomerEncryption;
import com.robothy.s3.core.model.internal.ServerSideEncryption;
import com.robothy.s3.rest.constants.AmzHeaderNames;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.function.Function;

/**
 * Reads the SSE-S3, SSE-KMS or DSSE-KMS encryption of a request from its {@code x-amz-server-side-encryption*} headers,
 * and writes the encryption of an object to the headers of a response. The headers are validated like Amazon S3
 * validates them, but nothing is encrypted; see {@linkplain ServerSideEncryption}.
 */
public final class ServerSideEncryptionHeaders {

  private ServerSideEncryptionHeaders() {
  }

  /**
   * Read the encryption of a request.
   *
   * @param request the request.
   * @param customerEncryption the SSE-C key of the request, which the encryption can't be combined with; {@code null}
   *     if it provides none.
   * @return the encryption; {@code null} if the request sends none of the headers.
   * @throws LocalS3InvalidArgumentException if a header is invalid, or doesn't go with the others.
   */
  public static ServerSideEncryption fromRequest(HttpRequest request, CustomerEncryption customerEncryption) {
    return fromValues(name -> request.header(name).orElse(null), customerEncryption);
  }

  /**
   * Read the encryption from named values, e.g. the fields of a {@code POST} form.
   *
   * @param values the value of a header name; {@code null} if it has none.
   * @param customerEncryption the SSE-C key of the request; {@code null} if it provides none.
   * @return the encryption; {@code null} if none of the values are given.
   * @throws LocalS3InvalidArgumentException if a value is invalid, or doesn't go with the others.
   */
  public static ServerSideEncryption fromValues(Function<String, String> values,
                                                CustomerEncryption customerEncryption) {
    String algorithm = trim(values.apply(AmzHeaderNames.X_AMZ_SERVER_SIDE_ENCRYPTION));
    String kmsKeyId = trim(values.apply(AmzHeaderNames.X_AMZ_SSE_KMS_KEY_ID));
    String context = trim(values.apply(AmzHeaderNames.X_AMZ_SSE_CONTEXT));
    String bucketKeyEnabled = trim(values.apply(AmzHeaderNames.X_AMZ_SSE_BUCKET_KEY_ENABLED));
    if (algorithm == null) {
      if (kmsKeyId != null || context != null) {
        throw requiresKms(kmsKeyId != null ? AmzHeaderNames.X_AMZ_SSE_KMS_KEY_ID : AmzHeaderNames.X_AMZ_SSE_CONTEXT,
            kmsKeyId != null ? kmsKeyId : context);
      }
      // A bucket key without an algorithm names nothing to use it for; Amazon S3 ignores it too.
      return null;
    }
    if (customerEncryption != null) {
      throw new LocalS3InvalidArgumentException(AmzHeaderNames.X_AMZ_SERVER_SIDE_ENCRYPTION, algorithm,
          "Server Side Encryption with Customer provided key is incompatible with the encryption method specified");
    }
    if (!ServerSideEncryption.isSupported(algorithm)) {
      throw new LocalS3InvalidArgumentException(AmzHeaderNames.X_AMZ_SERVER_SIDE_ENCRYPTION, algorithm,
          "The encryption method specified is not supported");
    }
    if (!ServerSideEncryption.isKms(algorithm)) {
      if (kmsKeyId != null || context != null) {
        throw requiresKms(kmsKeyId != null ? AmzHeaderNames.X_AMZ_SSE_KMS_KEY_ID : AmzHeaderNames.X_AMZ_SSE_CONTEXT,
            kmsKeyId != null ? kmsKeyId : context);
      }
      return new ServerSideEncryption(algorithm, null, null, null);
    }
    if (context != null) {
      assertValidContext(context);
    }
    return new ServerSideEncryption(algorithm, kmsKeyId, context, parseBucketKeyEnabled(bucketKeyEnabled));
  }

  /**
   * Add the encryption of an object to a response.
   *
   * @param response the response.
   * @param encryption the encryption of the object; {@code null} if it has none.
   * @param withContext whether to add the encryption context, which Amazon S3 only answers the requests that store
   *     an object with, i.e. {@code PutObject}, {@code CopyObject} and {@code CreateMultipartUpload}.
   */
  public static void addHeaders(HttpResponse response, ServerSideEncryption encryption, boolean withContext) {
    if (encryption == null) {
      return;
    }
    response.putHeader(AmzHeaderNames.X_AMZ_SERVER_SIDE_ENCRYPTION, encryption.algorithm());
    ResponseUtils.putHeaderIfPresent(response, AmzHeaderNames.X_AMZ_SSE_KMS_KEY_ID, encryption.kmsKeyId());
    if (withContext) {
      ResponseUtils.putHeaderIfPresent(response, AmzHeaderNames.X_AMZ_SSE_CONTEXT, encryption.context());
    }
    if (Boolean.TRUE.equals(encryption.bucketKeyEnabled())) {
      response.putHeader(AmzHeaderNames.X_AMZ_SSE_BUCKET_KEY_ENABLED, true);
    }
  }

  private static LocalS3InvalidArgumentException requiresKms(String header, String value) {
    return new LocalS3InvalidArgumentException(header, value,
        "Server Side Encryption with AWS KMS managed key requires HTTP header x-amz-server-side-encryption : aws:kms");
  }

  /**
   * An encryption context is a base64 encoded JSON object of string values; only its encoding is checked.
   */
  private static void assertValidContext(String context) {
    String json;
    try {
      json = new String(Base64.getDecoder().decode(context), StandardCharsets.UTF_8).trim();
    } catch (IllegalArgumentException e) {
      json = null;
    }
    if (json == null || !json.startsWith("{") || !json.endsWith("}")) {
      throw new LocalS3InvalidArgumentException(AmzHeaderNames.X_AMZ_SSE_CONTEXT, context,
          "The header 'x-amz-server-side-encryption-context' shall be Base64-encoded UTF-8 string holding JSON which "
              + "represents a string-string map");
    }
  }

  private static Boolean parseBucketKeyEnabled(String value) {
    if (value == null) {
      return null;
    }
    return switch (value.toLowerCase(Locale.ROOT)) {
      case "true" -> true;
      case "false" -> false;
      default -> throw new LocalS3InvalidArgumentException(AmzHeaderNames.X_AMZ_SSE_BUCKET_KEY_ENABLED, value,
          "Invalid value for x-amz-server-side-encryption-bucket-key-enabled");
    };
  }

  private static String trim(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

}
