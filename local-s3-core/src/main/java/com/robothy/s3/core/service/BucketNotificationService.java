package com.robothy.s3.core.service;

import com.robothy.s3.core.assertions.BucketAssertions;
import com.robothy.s3.core.exception.LocalS3RequestException;
import com.robothy.s3.core.model.internal.BucketMetadata;
import com.robothy.s3.core.util.XmlConfigurations;

/**
 * Bucket notification configuration service. The configuration is stored and returned as put, but no event is sent to
 * the destinations it names; see {@code docs/semantics.md#stored-not-applied}. Its contents aren't checked beyond a
 * {@code NotificationConfiguration} root, since nothing reads them.
 *
 * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_PutBucketNotificationConfiguration.html">PutBucketNotificationConfiguration</a>
 * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_GetBucketNotificationConfiguration.html">GetBucketNotificationConfiguration</a>
 */
public interface BucketNotificationService extends LocalS3MetadataApplicable {

  /**
   * The notification configuration of a bucket that was never configured, which Amazon S3 answers rather than an
   * error.
   */
  String EMPTY_NOTIFICATION_CONFIGURATION =
      "<NotificationConfiguration xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"/>";

  /**
   * Put the notification configuration of a bucket, replacing the existing one.
   *
   * @param bucketName the bucket name.
   * @param configuration the {@code NotificationConfiguration} XML document.
   * @throws LocalS3RequestException if the configuration isn't a well-formed {@code NotificationConfiguration}.
   */
  default void putBucketNotificationConfiguration(String bucketName, String configuration) {
    changeBucket(bucketName, () -> {
      BucketAssertions.assertBucketNameIsValid(bucketName);
      BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucketName);
      XmlConfigurations.assertWellFormed(configuration, "NotificationConfiguration");
      bucketMetadata.setNotification(configuration);
    });
  }

  /**
   * Get the notification configuration of a bucket.
   *
   * @param bucketName the bucket name.
   * @return the configuration that was put; {@linkplain #EMPTY_NOTIFICATION_CONFIGURATION} if none was.
   */
  default String getBucketNotificationConfiguration(String bucketName) {
    return withBucketReadLock(bucketName, () -> {
      BucketAssertions.assertBucketNameIsValid(bucketName);
      return BucketAssertions.assertBucketExists(localS3Metadata(), bucketName).getNotification()
          .orElse(EMPTY_NOTIFICATION_CONFIGURATION);
    });
  }

}
