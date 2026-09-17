package com.robothy.s3.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import com.robothy.s3.core.TestFiles;
import com.robothy.s3.core.exception.BucketNotExistException;
import com.robothy.s3.core.exception.LocalS3Exception;
import com.robothy.s3.core.exception.S3ErrorCode;
import com.robothy.s3.core.service.manager.LocalS3Manager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class BucketNotificationServiceTest extends LocalS3ServiceTestBase {

  private static final String BUCKET = "notification-bucket";

  private static final String CONFIGURATION = """
      <?xml version="1.0" encoding="UTF-8"?>
      <NotificationConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
        <QueueConfiguration>
          <Id>uploads</Id>
          <Queue>arn:aws:sqs:us-east-1:123456789012:no-such-queue</Queue>
          <Event>s3:ObjectCreated:*</Event>
        </QueueConfiguration>
      </NotificationConfiguration>
      """;

  @ParameterizedTest
  @MethodSource("bucketServices")
  void putsAndGetsTheNotificationConfiguration(BucketService bucketService) {
    bucketService.createBucket(BUCKET);
    assertEquals(BucketNotificationService.EMPTY_NOTIFICATION_CONFIGURATION,
        bucketService.getBucketNotificationConfiguration(BUCKET));

    bucketService.putBucketNotificationConfiguration(BUCKET, CONFIGURATION);
    assertEquals(CONFIGURATION, bucketService.getBucketNotificationConfiguration(BUCKET));

    // An empty configuration replaces the previous one, which turns notifications off.
    bucketService.putBucketNotificationConfiguration(BUCKET, "<NotificationConfiguration/>");
    assertEquals("<NotificationConfiguration/>", bucketService.getBucketNotificationConfiguration(BUCKET));

    assertThrows(BucketNotExistException.class,
        () -> bucketService.getBucketNotificationConfiguration("no-such-bucket"));
    assertThrows(BucketNotExistException.class,
        () -> bucketService.putBucketNotificationConfiguration("no-such-bucket", CONFIGURATION));
  }

  @Test
  void persistsTheNotificationConfiguration() throws IOException {
    Path dataPath = Files.createTempDirectory("local-s3");
    try {
      LocalS3Manager manager = LocalS3Manager.createFileSystemS3Manager(dataPath);
      manager.bucketService().createBucket(BUCKET);
      manager.bucketService().putBucketNotificationConfiguration(BUCKET, CONFIGURATION);
      manager.close();

      LocalS3Manager restarted = LocalS3Manager.createFileSystemS3Manager(dataPath);
      try {
        assertEquals(CONFIGURATION, restarted.bucketService().getBucketNotificationConfiguration(BUCKET));
      } finally {
        restarted.close();
      }
    } finally {
      TestFiles.deleteDirectory(dataPath);
    }
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {
      "  ",
      "not xml",
      "<NotificationConfiguration>",
      "<LifecycleConfiguration/>",
      "<NotificationConfiguration/><NotificationConfiguration/>",
      // A document type could declare entities that read files or URLs; it is refused rather than resolved.
      "<!DOCTYPE NotificationConfiguration [<!ENTITY id SYSTEM \"file:///etc/passwd\">]>"
          + "<NotificationConfiguration><QueueConfiguration><Id>&id;</Id></QueueConfiguration>"
          + "</NotificationConfiguration>"
  })
  void rejectsMalformedConfigurations(String configuration) {
    BucketService bucketService = LocalS3Manager.createInMemoryS3Manager().bucketService();
    bucketService.createBucket(BUCKET);
    bucketService.putBucketNotificationConfiguration(BUCKET, CONFIGURATION);

    LocalS3Exception thrown = assertThrows(LocalS3Exception.class,
        () -> bucketService.putBucketNotificationConfiguration(BUCKET, configuration));
    assertEquals(S3ErrorCode.MalformedXML, thrown.getS3ErrorCode());
    // A rejected configuration leaves the previous one in place.
    assertEquals(CONFIGURATION, bucketService.getBucketNotificationConfiguration(BUCKET));
  }

}
