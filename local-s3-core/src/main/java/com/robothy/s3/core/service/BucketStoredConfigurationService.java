package com.robothy.s3.core.service;

import com.robothy.s3.core.assertions.BucketAssertions;
import com.robothy.s3.core.exception.LocalS3RequestException;
import com.robothy.s3.core.model.StoredBucketConfiguration;
import com.robothy.s3.core.model.internal.BucketMetadata;
import com.robothy.s3.core.util.XmlConfigurations;
import java.util.Optional;

/**
 * The configurations of a bucket that LocalS3 stores and returns but never applies: transfer acceleration, access
 * logging, requester pays, static website hosting and ownership controls; see {@linkplain StoredBucketConfiguration}.
 *
 * <p>A document must be well-formed XML whose root element is the one of its configuration; its contents aren't
 * checked, since nothing reads them. It is stored as it was put, and returned as is.
 */
public interface BucketStoredConfigurationService extends LocalS3MetadataApplicable {

  /**
   * Put a configuration of a bucket, replacing the existing one.
   *
   * @param bucketName the bucket name.
   * @param type the configuration.
   * @param configuration the XML document.
   * @throws LocalS3RequestException if the document isn't a well-formed document of the configuration.
   */
  default void putBucketConfiguration(String bucketName, StoredBucketConfiguration type, String configuration) {
    changeBucket(bucketName, () -> {
      BucketAssertions.assertBucketNameIsValid(bucketName);
      BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucketName);
      XmlConfigurations.assertWellFormed(configuration, type.rootElement());
      bucketMetadata.getStoredConfigurations().put(type.name(), configuration);
    });
  }

  /**
   * Get a configuration of a bucket.
   *
   * @param bucketName the bucket name.
   * @param type the configuration.
   * @return the document that was put; the default configuration if none was.
   * @throws LocalS3RequestException with {@linkplain StoredBucketConfiguration#notFoundError()} if the bucket has
   *     none: it was deleted, or was never put and has no default.
   */
  default String getBucketConfiguration(String bucketName, StoredBucketConfiguration type) {
    return withBucketReadLock(bucketName, () -> {
      BucketAssertions.assertBucketNameIsValid(bucketName);
      String configuration = BucketAssertions.assertBucketExists(localS3Metadata(), bucketName)
          .getStoredConfigurations().get(type.name());
      if (configuration == null) {
        configuration = type.defaultConfiguration().orElse(null);
      }
      if (configuration == null || configuration.isEmpty()) {
        throw new LocalS3RequestException(type.notFoundError());
      }
      return configuration;
    });
  }

  /**
   * Find a configuration of a bucket, for the callers that apply one if the bucket has it, e.g. the static website
   * endpoint reading the {@code WebsiteConfiguration}, rather than answering a request for it.
   *
   * @param bucketName the bucket name.
   * @param type the configuration.
   * @return the document that was put, or the default one if none was; empty if the bucket has none, or doesn't
   *     exist.
   */
  default Optional<String> findBucketConfiguration(String bucketName, StoredBucketConfiguration type) {
    return withBucketReadLock(bucketName, () -> localS3Metadata().getBucketMetadata(bucketName)
        .map(bucket -> bucket.getStoredConfigurations().getOrDefault(type.name(),
            type.defaultConfiguration().orElse("")))
        .filter(configuration -> !configuration.isEmpty()));
  }

  /**
   * Delete a configuration of a bucket. Deleting one that the bucket doesn't have succeeds, like on Amazon S3.
   *
   * @param bucketName the bucket name.
   * @param type the configuration.
   */
  default void deleteBucketConfiguration(String bucketName, StoredBucketConfiguration type) {
    changeBucket(bucketName, () -> {
      BucketAssertions.assertBucketNameIsValid(bucketName);
      BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucketName);
      if (type.defaultConfiguration().isPresent()) {
        // Remembered as deleted, so that the default doesn't come back.
        bucketMetadata.getStoredConfigurations().put(type.name(), "");
      } else {
        bucketMetadata.getStoredConfigurations().remove(type.name());
      }
    });
  }

}
