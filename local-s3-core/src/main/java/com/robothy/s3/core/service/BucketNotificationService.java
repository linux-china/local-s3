package com.robothy.s3.core.service;

import com.robothy.s3.core.assertions.BucketAssertions;
import com.robothy.s3.core.exception.LocalS3RequestException;
import com.robothy.s3.core.model.internal.BucketMetadata;
import com.robothy.s3.core.util.XmlConfigurations;

/**
 * Bucket notification configuration service.
 *
 * <p>The configuration is <b>stored but never delivered to</b>: LocalS3 doesn't send events to the SNS topics, SQS
 * queues, Lambda functions or EventBridge that it names, and doesn't check that their ARNs exist. Applications and
 * infrastructure code that configure notifications when they start thus work against LocalS3; an application that
 * wants to hear of changes registers an {@code S3ChangeListener} instead.
 *
 * <p>The document must be well-formed XML whose root element is {@code NotificationConfiguration}; its contents
 * aren't checked, since nothing reads them. It is stored as it was put, and returned as is. Putting an empty
 * {@code NotificationConfiguration} turns notifications off, like it does on Amazon S3.
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
