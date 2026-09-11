package com.robothy.s3.core.service;

import com.robothy.s3.core.annotations.BucketChanged;
import com.robothy.s3.core.annotations.BucketReadLock;
import com.robothy.s3.core.annotations.BucketWriteLock;
import com.robothy.s3.core.assertions.BucketAssertions;
import com.robothy.s3.core.exception.InvalidCORSConfigurationException;
import com.robothy.s3.core.model.internal.BucketMetadata;
import com.robothy.s3.datatypes.CORSConfiguration;
import com.robothy.s3.datatypes.CORSRule;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Bucket cross-origin resource sharing (CORS) configuration service.
 *
 * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_PutBucketCors.html">PutBucketCors</a>
 * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_GetBucketCors.html">GetBucketCors</a>
 * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_DeleteBucketCors.html">DeleteBucketCors</a>
 */
public interface BucketCorsService extends LocalS3MetadataApplicable {

  /**
   * HTTP methods that CORS rules can allow.
   */
  Set<String> CORS_METHODS = Set.of("GET", "PUT", "POST", "DELETE", "HEAD");

  /**
   * Max number of rules of a CORS configuration.
   */
  int MAX_CORS_RULES = 100;

  /**
   * Put the CORS configuration of a bucket, replacing the existing one.
   *
   * @param bucketName the bucket name.
   * @param configuration the CORS configuration.
   * @throws InvalidCORSConfigurationException if the configuration is invalid.
   */
  @BucketChanged
  @BucketWriteLock
  default void putBucketCors(String bucketName, CORSConfiguration configuration) {
    BucketAssertions.assertBucketNameIsValid(bucketName);
    BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucketName);
    validateCorsConfiguration(configuration);
    bucketMetadata.setCors(configuration);
  }

  /**
   * Get the CORS configuration of a bucket.
   *
   * @param bucketName the bucket name.
   * @return the CORS configuration; empty if the bucket has none.
   */
  @BucketReadLock
  default Optional<CORSConfiguration> getBucketCors(String bucketName) {
    BucketAssertions.assertBucketNameIsValid(bucketName);
    BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucketName);
    return bucketMetadata.getCors();
  }

  /**
   * Delete the CORS configuration of a bucket.
   *
   * @param bucketName the bucket name.
   */
  @BucketChanged
  @BucketWriteLock
  default void deleteBucketCors(String bucketName) {
    BucketAssertions.assertBucketNameIsValid(bucketName);
    BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucketName);
    bucketMetadata.setCors(null);
  }

  private static void validateCorsConfiguration(CORSConfiguration configuration) {
    List<CORSRule> rules = configuration == null ? null : configuration.getCorsRules();
    if (rules == null || rules.isEmpty()) {
      throw new InvalidCORSConfigurationException("The CORS configuration must contain at least one CORSRule.");
    }
    if (rules.size() > MAX_CORS_RULES) {
      throw new InvalidCORSConfigurationException(
          "The CORS configuration can contain at most " + MAX_CORS_RULES + " CORSRule elements.");
    }

    for (CORSRule rule : rules) {
      if (isEmpty(rule.getAllowedOrigins()) || isEmpty(rule.getAllowedMethods())) {
        throw new InvalidCORSConfigurationException(
            "Each CORSRule must specify at least one AllowedOrigin and one AllowedMethod.");
      }
      for (String method : rule.getAllowedMethods()) {
        if (!CORS_METHODS.contains(method)) {
          throw new InvalidCORSConfigurationException(
              "Found unsupported HTTP method in CORS config. Unsupported method is " + method);
        }
      }
      assertAtMostOneWildcard("AllowedOrigin", rule.getAllowedOrigins());
      assertAtMostOneWildcard("AllowedHeader", rule.getAllowedHeaders());
      if (rule.getMaxAgeSeconds() != null && rule.getMaxAgeSeconds() < 0) {
        throw new InvalidCORSConfigurationException("MaxAgeSeconds must not be negative.");
      }
    }
  }

  private static void assertAtMostOneWildcard(String element, List<String> values) {
    if (values == null) {
      return;
    }
    for (String value : values) {
      if (value == null || value.indexOf('*') != value.lastIndexOf('*')) {
        throw new InvalidCORSConfigurationException(
            element + " \"" + value + "\" can not have more than one wildcard.");
      }
    }
  }

  private static boolean isEmpty(List<String> values) {
    return values == null || values.isEmpty();
  }

}
