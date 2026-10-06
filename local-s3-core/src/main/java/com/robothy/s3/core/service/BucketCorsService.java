package com.robothy.s3.core.service;

import com.robothy.s3.core.assertions.BucketAssertions;
import com.robothy.s3.core.exception.InvalidCORSConfigurationException;
import com.robothy.s3.core.model.internal.BucketMetadata;
import com.robothy.s3.datatypes.CORSConfiguration;
import com.robothy.s3.datatypes.CORSRule;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * See {@code docs/semantics.md#cors}.
 */
public interface BucketCorsService extends LocalS3MetadataApplicable {

  Set<String> CORS_METHODS = Set.of("GET", "PUT", "POST", "DELETE", "HEAD");

  int MAX_CORS_RULES = 100;

  default void putBucketCors(String bucketName, CORSConfiguration configuration) {
    changeBucket(bucketName, () -> {
      BucketAssertions.assertBucketNameIsValid(bucketName);
      BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucketName);
      validateCorsConfiguration(configuration);
      bucketMetadata.setCors(configuration);
    });
  }

  default Optional<CORSConfiguration> getBucketCors(String bucketName) {
    return withBucketReadLock(bucketName, () -> {
      BucketAssertions.assertBucketNameIsValid(bucketName);
      BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucketName);
      return bucketMetadata.getCors();
    });
  }

  default void deleteBucketCors(String bucketName) {
    changeBucket(bucketName, () -> {
      BucketAssertions.assertBucketNameIsValid(bucketName);
      BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucketName);
      bucketMetadata.setCors(null);
    });
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
