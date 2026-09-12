package com.robothy.s3.core.assertions;

import com.robothy.s3.core.exception.BucketAlreadyExistsException;
import com.robothy.s3.core.exception.BucketNotEmptyException;
import com.robothy.s3.core.exception.BucketNotExistException;
import com.robothy.s3.core.exception.BucketPolicyNotExistException;
import com.robothy.s3.core.exception.BucketReplicationNotExistException;
import com.robothy.s3.core.exception.BucketTaggingNotExistException;
import com.robothy.s3.core.exception.InvalidBucketNameException;
import com.robothy.s3.core.exception.ServerSideEncryptionConfigurationNotFoundException;
import com.robothy.s3.core.model.internal.BucketMetadata;
import com.robothy.s3.core.model.internal.LocalS3Metadata;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;

/**
 * Bucket related assertions.
 */
public class BucketAssertions {

  /**
   * A valid bucket name shouldn't be blank, and must name a file in a single directory: the file system
   * stores of the PERSISTENCE mode build metadata file names from the bucket name, so a name holding a
   * path separator or a traversal segment would read, write or delete files outside the data directory.
   * Names that break these rules are rejected in both strict and lenient mode.
   *
   * @param bucketName bucket name to validate.
   * @return valid bucket name.
   */
  public static String assertBucketNameIsValid(String bucketName) {
    if (StringUtils.isBlank(bucketName) || escapesDataDirectory(bucketName)) {
      throw new InvalidBucketNameException(bucketName);
    }
    return bucketName;
  }

  /**
   * Whether a file named after the bucket could land outside the directory it is resolved against.
   * Control characters are rejected as well; a NUL truncates the path that native code sees.
   */
  private static boolean escapesDataDirectory(String bucketName) {
    if (".".equals(bucketName) || "..".equals(bucketName)) {
      return true;
    }
    for (int i = 0; i < bucketName.length(); i++) {
      char c = bucketName.charAt(i);
      if (c == '/' || c == '\\' || c < ' ' || c == '') {
        return true;
      }
    }
    return false;
  }

  private static final Pattern BUCKET_NAME_CHARACTERS = Pattern.compile("[a-z0-9][a-z0-9.-]*[a-z0-9]");

  private static final Pattern IP_ADDRESS = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

  private static final List<String> RESERVED_PREFIXES = List.of("xn--", "sthree-", "amzn-s3-demo-");

  private static final List<String> RESERVED_SUFFIXES = List.of("-s3alias", "--ol-s3", ".mrap", "--x-s3", "--table-s3");

  /**
   * Assert that the bucket name follows the
   * <a href="https://docs.aws.amazon.com/AmazonS3/latest/userguide/bucketnamingrules.html">naming rules</a>
   * of Amazon S3 general purpose buckets.
   *
   * @param bucketName bucket name to validate.
   * @return valid bucket name.
   * @throws InvalidBucketNameException naming the rule that the bucket name breaks.
   */
  public static String assertBucketNameFollowsNamingRules(String bucketName) {
    assertBucketNameIsValid(bucketName);
    String violation = namingRuleViolation(bucketName);
    if (violation != null) {
      throw new InvalidBucketNameException(bucketName, violation);
    }
    return bucketName;
  }

  private static String namingRuleViolation(String bucketName) {
    if (bucketName.length() < 3 || bucketName.length() > 63) {
      return "Bucket names must be between 3 and 63 characters long.";
    }
    if (!BUCKET_NAME_CHARACTERS.matcher(bucketName).matches()) {
      return "Bucket names can consist only of lowercase letters, numbers, periods and hyphens, "
          + "and must begin and end with a letter or number.";
    }
    if (bucketName.contains("..")) {
      return "Bucket names must not contain two adjacent periods.";
    }
    if (IP_ADDRESS.matcher(bucketName).matches()) {
      return "Bucket names must not be formatted as an IP address.";
    }
    for (String prefix : RESERVED_PREFIXES) {
      if (bucketName.startsWith(prefix)) {
        return "Bucket names must not start with the prefix '" + prefix + "'.";
      }
    }
    for (String suffix : RESERVED_SUFFIXES) {
      if (bucketName.endsWith(suffix)) {
        return "Bucket names must not end with the suffix '" + suffix + "'.";
      }
    }
    return null;
  }

  /**
   * Assert that the bucket is exists in the {@linkplain LocalS3Metadata}.
   *
   * @param s3Metadata LocalS3 metadata.
   * @param bucketName bucket to validate.
   * @return fetched {@linkplain BucketMetadata} instance.
   */
  public static BucketMetadata assertBucketExists(LocalS3Metadata s3Metadata, String bucketName) {
    return s3Metadata.getBucketMetadata(bucketName)
        .orElseThrow(() -> new BucketNotExistException(bucketName));
  }

  /**
   * Assert that the bucket not exist in the {@code s3Metadata}.
   *
   * @param s3Metadata LocalS3 metadata.
   * @param bucketName bucket to validate.
   */
  public static void assertBucketNotExists(LocalS3Metadata s3Metadata, String bucketName) {
    if (s3Metadata.getBucketMetadataMap().containsKey(bucketName)) {
      throw new BucketAlreadyExistsException(bucketName);
    }
  }

  /**
   * Assert that the bucket is empty.
   *
   * @param bucketMetadata bucket metadata.
   */
  public static void assertBucketIsEmpty(BucketMetadata bucketMetadata) {
    if (!bucketMetadata.getObjectMap().isEmpty()) {
      throw new BucketNotEmptyException(bucketMetadata.getBucketName());
    }
  }

  /**
   * Assert the bucket tagging is set.
   *
   * @param bucketMetadata bucket metadata.
   * @return bucket tagging of the specified bucket metadata.
   */
  public static Collection<Map<String, String>> assertBucketTaggingExist(BucketMetadata bucketMetadata) {
    return bucketMetadata.getTagging()
        .orElseThrow(() -> new BucketTaggingNotExistException(bucketMetadata.getBucketName()));
  }

  /**
   * Assert policy of the specified bucket exist.
   */
  public static String assertBucketPolicyExist(LocalS3Metadata s3Metadata, String bucketName) {
    BucketMetadata bucketMetadata = assertBucketExists(s3Metadata, bucketName);
    return assertBucketPolicyExist(bucketMetadata);
  }

  /**
   * Assert the given {@linkplain BucketMetadata} has policy.
   */
  public static String assertBucketPolicyExist(BucketMetadata bucketMetadata) {
    return bucketMetadata.getPolicy().orElseThrow(() -> new BucketPolicyNotExistException(bucketMetadata.getBucketName()));
  }

  /**
   * Assert the specified bucket has configured replication.
   *
   * @param s3Metadata LocalS3 metadata.
   * @param bucketName the bucket name.
   * @return bucket replication configuration.
   */
  public static String assertBucketReplicationExist(LocalS3Metadata s3Metadata, String bucketName) {
    BucketMetadata bucketMetadata = assertBucketExists(s3Metadata, bucketName);
    return bucketMetadata.getReplication().orElseThrow(BucketReplicationNotExistException::new);
  }

  /**
   * Assert the specified bucket has configured server side encryption.
   *
   * @param localS3Metadata LocalS3 metadata.
   * @param bucketName the bucket name.
   * @return bucket encryption configuration.
   */
  public static String assertBucketEncryptionExist(LocalS3Metadata localS3Metadata, String bucketName) {
    BucketMetadata bucketMetadata = assertBucketExists(localS3Metadata, bucketName);
    return bucketMetadata.getEncryption().orElseThrow(() ->
        new ServerSideEncryptionConfigurationNotFoundException(bucketName));
  }

}
