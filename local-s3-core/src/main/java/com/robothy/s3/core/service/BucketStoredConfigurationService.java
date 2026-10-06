package com.robothy.s3.core.service;

import com.robothy.s3.core.assertions.BucketAssertions;
import com.robothy.s3.core.exception.LocalS3RequestException;
import com.robothy.s3.core.exception.S3ErrorCode;
import com.robothy.s3.core.model.IdentifiedBucketConfiguration;
import com.robothy.s3.core.model.StoredBucketConfiguration;
import com.robothy.s3.core.model.internal.BucketMetadata;
import com.robothy.s3.core.util.XmlConfigurations;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;

/**
 * See {@code docs/semantics.md#stored-not-applied}.
 */
public interface BucketStoredConfigurationService extends LocalS3MetadataApplicable {

  default void putBucketConfiguration(String bucketName, StoredBucketConfiguration type, String configuration) {
    changeBucket(bucketName, () -> {
      BucketAssertions.assertBucketNameIsValid(bucketName);
      BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucketName);
      XmlConfigurations.assertWellFormed(configuration, type.rootElement());
      bucketMetadata.getStoredConfigurations().put(type.name(), configuration);
    });
  }

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

  // For the callers that apply a configuration, e.g. the website endpoint, rather than answer a request for it.
  default Optional<String> findBucketConfiguration(String bucketName, StoredBucketConfiguration type) {
    return withBucketReadLock(bucketName, () -> localS3Metadata().getBucketMetadata(bucketName)
        .map(bucket -> bucket.getStoredConfigurations().getOrDefault(type.name(),
            type.defaultConfiguration().orElse("")))
        .filter(configuration -> !configuration.isEmpty()));
  }

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

  default void putBucketConfiguration(String bucketName, IdentifiedBucketConfiguration type, String id,
                                      String configuration) {
    changeBucket(bucketName, () -> {
      BucketAssertions.assertBucketNameIsValid(bucketName);
      BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucketName);
      assertIdProvided(id);
      XmlConfigurations.assertWellFormed(configuration, type.rootElement());
      String documentId = XmlConfigurations.childText(configuration, "Id")
          .orElseThrow(() -> new LocalS3RequestException(S3ErrorCode.MalformedXML));
      if (!id.equals(documentId)) {
        throw new LocalS3RequestException(S3ErrorCode.InvalidArgument,
            "The ID of the configuration, " + documentId + ", doesn't match the id parameter, " + id + ".");
      }
      bucketMetadata.getIdentifiedConfigurations()
          .computeIfAbsent(type.name(), name -> new TreeMap<>())
          .put(id, configuration);
    });
  }

  default String getBucketConfiguration(String bucketName, IdentifiedBucketConfiguration type, String id) {
    return withBucketReadLock(bucketName, () -> {
      BucketAssertions.assertBucketNameIsValid(bucketName);
      BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucketName);
      assertIdProvided(id);
      String configuration = identifiedConfigurations(bucketMetadata, type).get(id);
      if (configuration == null) {
        throw new LocalS3RequestException(S3ErrorCode.NoSuchConfiguration);
      }
      return configuration;
    });
  }

  default List<String> listBucketConfigurations(String bucketName, IdentifiedBucketConfiguration type) {
    return withBucketReadLock(bucketName, () -> {
      BucketAssertions.assertBucketNameIsValid(bucketName);
      BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucketName);
      return List.copyOf(identifiedConfigurations(bucketMetadata, type).values());
    });
  }

  default void deleteBucketConfiguration(String bucketName, IdentifiedBucketConfiguration type, String id) {
    changeBucket(bucketName, () -> {
      BucketAssertions.assertBucketNameIsValid(bucketName);
      BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucketName);
      assertIdProvided(id);
      NavigableMap<String, String> configurations = bucketMetadata.getIdentifiedConfigurations().get(type.name());
      if (configurations == null || configurations.remove(id) == null) {
        throw new LocalS3RequestException(S3ErrorCode.NoSuchConfiguration);
      }
      if (configurations.isEmpty()) {
        bucketMetadata.getIdentifiedConfigurations().remove(type.name());
      }
    });
  }

  private static Map<String, String> identifiedConfigurations(BucketMetadata bucketMetadata,
                                                              IdentifiedBucketConfiguration type) {
    return bucketMetadata.getIdentifiedConfigurations().getOrDefault(type.name(), new TreeMap<>());
  }

  private static void assertIdProvided(String id) {
    if (id == null || id.isEmpty()) {
      throw new LocalS3RequestException(S3ErrorCode.InvalidArgument, "The id parameter is required.");
    }
  }

}
