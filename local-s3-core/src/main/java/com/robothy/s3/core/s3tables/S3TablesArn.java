package com.robothy.s3.core.s3tables;

import java.util.Locale;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The ARNs of the S3 Tables API, which is how its resources are addressed: every operation but
 * {@code CreateTableBucket} and {@code ListTableBuckets} names its table bucket, its table or its namespace by an ARN
 * rather than by a name, and the ARN travels in the path of the request.
 *
 * <p>A table bucket is {@code arn:aws:s3tables:<region>:<account>:bucket/<name>} and a table is that ARN with
 * {@code /table/<id>} after it, where the ID is the one the service assigned when the table was created. A LocalS3
 * service has no account of its own, so it answers a fixed one, see {@link #DEFAULT_ACCOUNT_ID}: a client that
 * round-trips an ARN of LocalS3 gets its resources back, and one that was written against a real account has the
 * account of its ARNs ignored rather than being refused — a test double is not an authorization boundary.
 *
 * @param partition the partition of the ARN, e.g. {@code aws}.
 * @param region the region, e.g. {@code us-east-1}.
 * @param accountId the account, twelve digits.
 * @param bucket the name of the table bucket.
 * @param tableId the ID of the table; {@code null} for the ARN of a table bucket.
 */
public record S3TablesArn(String partition, String region, String accountId, String bucket,
                          @Nullable String tableId) {

  /**
   * The service name in the ARNs and in the credential scope of a signed request, which is what tells a request of
   * this API from an Amazon S3 one that reached the same port.
   */
  public static final String SERVICE = "s3tables";

  /**
   * The partition of the ARNs that LocalS3 hands out.
   */
  public static final String DEFAULT_PARTITION = "aws";

  /**
   * The account that the ARNs of LocalS3 name. It is the one LocalStack and the other local AWS doubles use, so a
   * test that hard-codes an account works against either.
   */
  public static final String DEFAULT_ACCOUNT_ID = "000000000000";

  public S3TablesArn {
    Objects.requireNonNull(partition, "partition");
    Objects.requireNonNull(region, "region");
    Objects.requireNonNull(accountId, "accountId");
    Objects.requireNonNull(bucket, "bucket");
  }

  /**
   * The ARN of a table bucket.
   *
   * @param region the region of the service.
   * @param accountId the account of the service.
   * @param bucket the name of the table bucket.
   * @return the ARN.
   */
  public static S3TablesArn ofBucket(String region, String accountId, String bucket) {
    return new S3TablesArn(DEFAULT_PARTITION, region, accountId, bucket, null);
  }

  /**
   * The ARN of a table.
   *
   * @param region the region of the service.
   * @param accountId the account of the service.
   * @param bucket the name of the table bucket that holds the table.
   * @param tableId the ID of the table.
   * @return the ARN.
   */
  public static S3TablesArn ofTable(String region, String accountId, String bucket, String tableId) {
    return new S3TablesArn(DEFAULT_PARTITION, region, accountId, bucket,
        Objects.requireNonNull(tableId, "tableId"));
  }

  /**
   * Read an ARN of the API.
   *
   * <p>What is checked is the shape — six colon-separated parts, the {@code s3tables} service, and a resource that
   * starts with {@code bucket/} — rather than the region or the account, which a test double has no business
   * rejecting a client over.
   *
   * @param arn the ARN.
   * @return the parsed ARN.
   * @throws S3TablesException if it isn't an ARN of this API.
   */
  public static S3TablesArn parse(@Nullable String arn) {
    if (arn == null || arn.isBlank()) {
      throw S3TablesException.badRequest("An ARN of the S3 Tables API is required.");
    }
    // arn:<partition>:s3tables:<region>:<account>:<resource>, where the resource itself holds '/' and no ':'.
    String[] parts = arn.split(":", 6);
    if (parts.length != 6 || !"arn".equals(parts[0])) {
      throw invalid(arn);
    }
    if (!SERVICE.equals(parts[2].toLowerCase(Locale.ROOT))) {
      throw invalid(arn);
    }
    String resource = parts[5];
    if (!resource.startsWith("bucket/")) {
      throw invalid(arn);
    }
    String rest = resource.substring("bucket/".length());
    int slash = rest.indexOf('/');
    if (slash < 0) {
      if (rest.isEmpty()) {
        throw invalid(arn);
      }
      return new S3TablesArn(parts[1], parts[3], parts[4], rest, null);
    }
    String bucket = rest.substring(0, slash);
    String tail = rest.substring(slash + 1);
    if (bucket.isEmpty() || !tail.startsWith("table/")) {
      throw invalid(arn);
    }
    String tableId = tail.substring("table/".length());
    if (tableId.isEmpty() || tableId.indexOf('/') >= 0) {
      throw invalid(arn);
    }
    return new S3TablesArn(parts[1], parts[3], parts[4], bucket, tableId);
  }

  /**
   * Read the ARN of a table bucket.
   *
   * @param arn the ARN.
   * @return the parsed ARN.
   * @throws S3TablesException if it isn't the ARN of a table bucket.
   */
  public static S3TablesArn parseBucket(@Nullable String arn) {
    S3TablesArn parsed = parse(arn);
    if (parsed.tableId != null) {
      throw S3TablesException.badRequest("Expected the ARN of a table bucket, which names no table; got: " + arn);
    }
    return parsed;
  }

  /**
   * Read the ARN of a table.
   *
   * @param arn the ARN.
   * @return the parsed ARN.
   * @throws S3TablesException if it isn't the ARN of a table.
   */
  public static S3TablesArn parseTable(@Nullable String arn) {
    S3TablesArn parsed = parse(arn);
    if (parsed.tableId == null) {
      throw S3TablesException.badRequest("Expected the ARN of a table, e.g. "
          + "arn:aws:s3tables:us-east-1:000000000000:bucket/my-bucket/table/<id>; got: " + arn);
    }
    return parsed;
  }

  /**
   * Whether a string looks like an ARN of this API, which the operations that take either an ARN or a name read a
   * value by.
   *
   * @param value the value; {@code null} for none.
   * @return {@code true} if it is an {@code s3tables} ARN.
   */
  public static boolean isArn(@Nullable String value) {
    return value != null && value.startsWith("arn:") && value.contains(":" + SERVICE + ":");
  }

  /**
   * This ARN as a string.
   *
   * @return the ARN.
   */
  @Override
  public String toString() {
    String resource = tableId == null ? "bucket/" + bucket : "bucket/" + bucket + "/table/" + tableId;
    return "arn:" + partition + ":" + SERVICE + ":" + region + ":" + accountId + ":" + resource;
  }

  private static S3TablesException invalid(String arn) {
    return S3TablesException.badRequest("Not an ARN of the S3 Tables API, e.g."
        + " arn:aws:s3tables:us-east-1:000000000000:bucket/my-bucket; got: " + arn);
  }

}
