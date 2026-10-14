package com.robothy.s3.rest;

/**
 * The optional capabilities that a {@linkplain LocalS3} serves beside its S3 API. Every one but the MCP server is on
 * by default; turning one off leaves its routes out of the router, so its requests are answered as if LocalS3 had
 * never known it. See {@code docs/embedding.md#turn-features-off}.
 *
 * <p>The Iceberg REST catalog and the static website hosting have switches of their own,
 * {@linkplain LocalS3Builder#icebergCatalog(boolean)} and {@linkplain LocalS3Builder#website(boolean)}.
 *
 * @param vector whether the S3 Vectors API is served.
 * @param s3Tables whether the S3 Tables API is served, together with the Iceberg REST catalog of its table buckets.
 * @param kms whether the AWS KMS endpoint is served.
 * @param sts whether the AWS STS endpoint is served.
 * @param console whether the built-in console is served under {@code /_admin/ui}.
 * @param mcp whether the built-in MCP server is served under {@code /_mcp}.
 */
public record LocalS3Features(boolean vector, boolean s3Tables, boolean kms, boolean sts, boolean console,
                              boolean mcp) {

  private static final LocalS3Features ALL = new LocalS3Features(true, true, true, true, true, true);

  // The MCP server lets an agent write and delete objects, so it is served only to a service that asks for it.
  private static final LocalS3Features DEFAULTS = ALL.withMcp(false);

  /**
   * The features without the MCP server, which was added later and is off unless it is turned on.
   *
   * @param vector whether the S3 Vectors API is served.
   * @param s3Tables whether the S3 Tables API is served.
   * @param kms whether the AWS KMS endpoint is served.
   * @param sts whether the AWS STS endpoint is served.
   * @param console whether the built-in console is served.
   */
  public LocalS3Features(boolean vector, boolean s3Tables, boolean kms, boolean sts, boolean console) {
    this(vector, s3Tables, kms, sts, console, false);
  }

  /**
   * Every capability on, the MCP server included.
   *
   * @return the features of a service that serves every capability.
   */
  public static LocalS3Features all() {
    return ALL;
  }

  /**
   * The default: every capability on but the MCP server.
   *
   * @return the features of a service that was configured with none.
   */
  public static LocalS3Features defaults() {
    return DEFAULTS;
  }

  /**
   * A copy with the S3 Vectors API turned on or off.
   *
   * @param vector whether the S3 Vectors API is served.
   * @return the copy.
   */
  public LocalS3Features withVector(boolean vector) {
    return new LocalS3Features(vector, s3Tables, kms, sts, console, mcp);
  }

  /**
   * A copy with the S3 Tables API turned on or off.
   *
   * @param s3Tables whether the S3 Tables API is served.
   * @return the copy.
   */
  public LocalS3Features withS3Tables(boolean s3Tables) {
    return new LocalS3Features(vector, s3Tables, kms, sts, console, mcp);
  }

  /**
   * A copy with the AWS KMS endpoint turned on or off.
   *
   * @param kms whether the AWS KMS endpoint is served.
   * @return the copy.
   */
  public LocalS3Features withKms(boolean kms) {
    return new LocalS3Features(vector, s3Tables, kms, sts, console, mcp);
  }

  /**
   * A copy with the AWS STS endpoint turned on or off.
   *
   * @param sts whether the AWS STS endpoint is served.
   * @return the copy.
   */
  public LocalS3Features withSts(boolean sts) {
    return new LocalS3Features(vector, s3Tables, kms, sts, console, mcp);
  }

  /**
   * A copy with the built-in console turned on or off.
   *
   * @param console whether the console is served.
   * @return the copy.
   */
  public LocalS3Features withConsole(boolean console) {
    return new LocalS3Features(vector, s3Tables, kms, sts, console, mcp);
  }

  /**
   * A copy with the built-in MCP server turned on or off.
   *
   * @param mcp whether the MCP server is served.
   * @return the copy.
   */
  public LocalS3Features withMcp(boolean mcp) {
    return new LocalS3Features(vector, s3Tables, kms, sts, console, mcp);
  }

}
