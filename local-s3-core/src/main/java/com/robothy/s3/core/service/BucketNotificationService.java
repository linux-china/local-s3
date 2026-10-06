package com.robothy.s3.core.service;

import com.robothy.s3.core.assertions.BucketAssertions;
import com.robothy.s3.core.model.internal.BucketMetadata;
import com.robothy.s3.core.util.XmlConfigurations;

/**
 * See {@code docs/semantics.md#stored-not-applied}.
 */
public interface BucketNotificationService extends LocalS3MetadataApplicable {

  // Answered for a bucket that was never configured, rather than an error, like Amazon S3.
  String EMPTY_NOTIFICATION_CONFIGURATION =
      "<NotificationConfiguration xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"/>";

  default void putBucketNotificationConfiguration(String bucketName, String configuration) {
    changeBucket(bucketName, () -> {
      BucketAssertions.assertBucketNameIsValid(bucketName);
      BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucketName);
      XmlConfigurations.assertWellFormed(configuration, "NotificationConfiguration");
      bucketMetadata.setNotification(configuration);
    });
  }

  default String getBucketNotificationConfiguration(String bucketName) {
    return withBucketReadLock(bucketName, () -> {
      BucketAssertions.assertBucketNameIsValid(bucketName);
      return BucketAssertions.assertBucketExists(localS3Metadata(), bucketName).getNotification()
          .orElse(EMPTY_NOTIFICATION_CONFIGURATION);
    });
  }

}
