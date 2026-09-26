package com.robothy.s3.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.jupiter.LocalS3;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.OptionalObjectAttributes;
import software.amazon.awssdk.services.s3.model.RestoreObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.StorageClass;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.Tier;
import java.time.Instant;

/**
 * {@code RestoreObject} of an archived object completes at once, and the object then answers {@code x-amz-restore}, so
 * that code that restores cold data before it reads it runs against LocalS3.
 */
class RestoreObjectIntegrationTest {

  @Test
  @LocalS3
  void anArchivedObjectIsRestoredAtOnce(S3Client s3) {
    String bucket = "restore-bucket";
    s3.createBucket(b -> b.bucket(bucket));
    s3.putObject(b -> b.bucket(bucket).key("cold.txt").storageClass(StorageClass.DEEP_ARCHIVE),
        RequestBody.fromString("Hello"));
    assertNull(s3.headObject(b -> b.bucket(bucket).key("cold.txt")).restore());

    RestoreObjectResponse first = s3.restoreObject(b -> b.bucket(bucket).key("cold.txt")
        .restoreRequest(r -> r.days(2).glacierJobParameters(p -> p.tier(Tier.BULK))));
    assertEquals(202, first.sdkHttpResponse().statusCode());

    HeadObjectResponse head = s3.headObject(b -> b.bucket(bucket).key("cold.txt"));
    assertTrue(head.restore().startsWith("ongoing-request=\"false\", expiry-date=\""), head.restore());
    // Midnight UTC after the days elapse.
    assertTrue(head.restore().endsWith(" 00:00:00 GMT\""), head.restore());
    assertEquals("Hello", s3.getObjectAsBytes(b -> b.bucket(bucket).key("cold.txt")).asUtf8String());
    assertFalse(s3.getObjectAsBytes(b -> b.bucket(bucket).key("cold.txt")).response().restore().isEmpty());

    // A restore of a restored object extends its copy.
    RestoreObjectResponse again = s3.restoreObject(b -> b.bucket(bucket).key("cold.txt")
        .restoreRequest(r -> r.days(5)));
    assertEquals(200, again.sdkHttpResponse().statusCode());
  }

  @Test
  @LocalS3
  void anObjectThatIsNotArchivedCannotBeRestored(S3Client s3) {
    String bucket = "restore-standard-bucket";
    s3.createBucket(b -> b.bucket(bucket));
    s3.putObject(b -> b.bucket(bucket).key("hot.txt"), RequestBody.fromString("Hello"));

    S3Exception exception = assertThrows(S3Exception.class, () -> s3.restoreObject(b -> b.bucket(bucket)
        .key("hot.txt").restoreRequest(r -> r.days(1))));
    assertEquals(403, exception.statusCode());
    assertEquals("InvalidObjectState", exception.awsErrorDetails().errorCode());

    S3Exception missing = assertThrows(S3Exception.class, () -> s3.restoreObject(b -> b.bucket(bucket)
        .key("missing.txt").restoreRequest(r -> r.days(1))));
    assertEquals(404, missing.statusCode());
  }

  @Test
  @LocalS3
  void aListingAnswersTheRestoreStatusWhenAskedFor(S3Client s3) {
    String bucket = "restore-listing-bucket";
    s3.createBucket(b -> b.bucket(bucket));
    s3.putObject(b -> b.bucket(bucket).key("cold.txt").storageClass(StorageClass.GLACIER),
        RequestBody.fromString("Hello"));
    s3.putObject(b -> b.bucket(bucket).key("frozen.txt").storageClass(StorageClass.GLACIER),
        RequestBody.fromString("Hello"));
    s3.restoreObject(b -> b.bucket(bucket).key("cold.txt").restoreRequest(r -> r.days(2)));

    ListObjectsV2Response asked = s3.listObjectsV2(b -> b.bucket(bucket)
        .optionalObjectAttributes(OptionalObjectAttributes.RESTORE_STATUS));
    S3Object restored = asked.contents().get(0);
    assertEquals("cold.txt", restored.key());
    assertFalse(restored.restoreStatus().isRestoreInProgress());
    assertTrue(restored.restoreStatus().restoreExpiryDate().isAfter(Instant.now()));
    // An object that was never restored has no restore status.
    assertEquals("frozen.txt", asked.contents().get(1).key());
    assertNull(asked.contents().get(1).restoreStatus());

    ListObjectsResponse v1 = s3.listObjects(b -> b.bucket(bucket)
        .optionalObjectAttributes(OptionalObjectAttributes.RESTORE_STATUS));
    assertEquals(restored.restoreStatus().restoreExpiryDate(), v1.contents().get(0).restoreStatus().restoreExpiryDate());

    // Left out unless asked for, like Amazon S3 does.
    assertNull(s3.listObjectsV2(b -> b.bucket(bucket)).contents().get(0).restoreStatus());
    assertNull(s3.listObjects(b -> b.bucket(bucket)).contents().get(0).restoreStatus());
  }

}
