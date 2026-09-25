package com.robothy.s3.core.model.internal;

import java.util.Objects;

/**
 * The server-side encryption of an object version with a key that Amazon S3 manages
 * (<a href="https://docs.aws.amazon.com/AmazonS3/latest/userguide/UsingServerSideEncryption.html">SSE-S3</a>), or
 * with a key of AWS KMS (<a href="https://docs.aws.amazon.com/AmazonS3/latest/userguide/UsingKMSEncryption.html">SSE-KMS</a>,
 * <a href="https://docs.aws.amazon.com/AmazonS3/latest/userguide/UsingDSSEncryption.html">DSSE-KMS</a>), as a request
 * names it.
 *
 * <p>LocalS3 doesn't encrypt anything and doesn't talk to KMS: it only records what the request named, so that the
 * object is answered with the same {@code x-amz-server-side-encryption*} headers as by Amazon S3.
 *
 * @param algorithm the algorithm, one of {@linkplain #AES256}, {@linkplain #AWS_KMS} and {@linkplain #AWS_KMS_DSSE}.
 * @param kmsKeyId the ID of the KMS key, as the request names it; {@linkplain #AWS_MANAGED_KMS_KEY_ID} if a KMS
 *     algorithm names none, like Amazon S3 uses the AWS managed key {@code aws/s3} then; {@code null} if the algorithm
 *     isn't a KMS one.
 * @param context the base64 encoded encryption context; {@code null} for none, or if the algorithm isn't a KMS one.
 * @param bucketKeyEnabled whether an S3 Bucket Key is used; {@code null} if the request didn't say, or the algorithm
 *     isn't a KMS one.
 */
public record ServerSideEncryption(String algorithm, String kmsKeyId, String context, Boolean bucketKeyEnabled) {

  /**
   * SSE-S3.
   */
  public static final String AES256 = "AES256";

  /**
   * SSE-KMS.
   */
  public static final String AWS_KMS = "aws:kms";

  /**
   * DSSE-KMS.
   */
  public static final String AWS_KMS_DSSE = "aws:kms:dsse";

  /**
   * The AWS managed key {@code aws/s3} that Amazon S3 encrypts with when a KMS algorithm names no key. LocalS3 has no
   * account or KMS of its own, so it answers a fixed key of the account that LocalS3 answers elsewhere.
   */
  public static final String AWS_MANAGED_KMS_KEY_ID =
      "arn:aws:kms:us-east-1:000000000000:key/00000000-0000-0000-0000-000000000000";

  /**
   * Validate the components, and name the AWS managed key for a KMS algorithm that names no key.
   *
   * @throws NullPointerException if the algorithm is {@code null}.
   * @throws IllegalArgumentException if the algorithm is unknown, or a KMS component is given for SSE-S3.
   */
  public ServerSideEncryption {
    Objects.requireNonNull(algorithm, "algorithm");
    if (!isSupported(algorithm)) {
      throw new IllegalArgumentException("Unsupported server-side encryption algorithm: " + algorithm);
    }
    if (!isKms(algorithm) && (kmsKeyId != null || context != null || bucketKeyEnabled != null)) {
      throw new IllegalArgumentException("A KMS key, context or bucket key is only valid for a KMS algorithm.");
    }
    if (isKms(algorithm) && (kmsKeyId == null || kmsKeyId.isBlank())) {
      kmsKeyId = AWS_MANAGED_KMS_KEY_ID;
    }
  }

  /**
   * Whether an algorithm is one that LocalS3 records.
   */
  public static boolean isSupported(String algorithm) {
    return AES256.equals(algorithm) || isKms(algorithm);
  }

  /**
   * Whether an algorithm is one of AWS KMS, i.e. {@code aws:kms} or {@code aws:kms:dsse}.
   */
  public static boolean isKms(String algorithm) {
    return AWS_KMS.equals(algorithm) || AWS_KMS_DSSE.equals(algorithm);
  }

}
