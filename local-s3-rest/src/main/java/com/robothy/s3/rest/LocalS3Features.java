package com.robothy.s3.rest;

/**
 * The optional capabilities that a {@linkplain LocalS3} serves beside its S3 API. Every one is on by default; turning
 * one off leaves its routes out of the router, so its requests are answered as if LocalS3 had never known it. See
 * {@code docs/embedding.md#turn-features-off}.
 *
 * <p>The Iceberg REST catalog and the static website hosting have switches of their own,
 * {@linkplain LocalS3Builder#icebergCatalog(boolean)} and {@linkplain LocalS3Builder#website(boolean)}.
 *
 * @param vector whether the S3 Vectors API is served.
 * @param s3Tables whether the S3 Tables API is served, together with the Iceberg REST catalog of its table buckets.
 * @param kms whether the AWS KMS endpoint is served.
 * @param sts whether the AWS STS endpoint is served.
 * @param console whether the built-in console is served under {@code /_admin/ui}.
 */
public record LocalS3Features(boolean vector, boolean s3Tables, boolean kms, boolean sts, boolean console) {

  private static final LocalS3Features ALL = new LocalS3Features(true, true, true, true, true);

  /**
   * Every capability on, which is the default.
   *
   * @return the features of a service that serves every capability.
   */
  public static LocalS3Features all() {
    return ALL;
  }

  /**
   * A copy with the S3 Vectors API turned on or off.
   *
   * @param vector whether the S3 Vectors API is served.
   * @return the copy.
   */
  public LocalS3Features withVector(boolean vector) {
    return new LocalS3Features(vector, s3Tables, kms, sts, console);
  }

  /**
   * A copy with the S3 Tables API turned on or off.
   *
   * @param s3Tables whether the S3 Tables API is served.
   * @return the copy.
   */
  public LocalS3Features withS3Tables(boolean s3Tables) {
    return new LocalS3Features(vector, s3Tables, kms, sts, console);
  }

  /**
   * A copy with the AWS KMS endpoint turned on or off.
   *
   * @param kms whether the AWS KMS endpoint is served.
   * @return the copy.
   */
  public LocalS3Features withKms(boolean kms) {
    return new LocalS3Features(vector, s3Tables, kms, sts, console);
  }

  /**
   * A copy with the AWS STS endpoint turned on or off.
   *
   * @param sts whether the AWS STS endpoint is served.
   * @return the copy.
   */
  public LocalS3Features withSts(boolean sts) {
    return new LocalS3Features(vector, s3Tables, kms, sts, console);
  }

  /**
   * A copy with the built-in console turned on or off.
   *
   * @param console whether the console is served.
   * @return the copy.
   */
  public LocalS3Features withConsole(boolean console) {
    return new LocalS3Features(vector, s3Tables, kms, sts, console);
  }

}
