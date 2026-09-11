package com.robothy.s3.jupiter.extensions;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import com.robothy.s3.jupiter.LocalS3;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;

class StrictBucketNamesTest {

  /**
   * The AWS SDK itself rejects some invalid names, e.g. with uppercase characters, before sending
   * CreateBucket; but not reserved prefixes, which only Amazon S3, or LocalS3 in strict mode, rejects.
   */
  private static final String RESERVED_PREFIX_NAME = "sthree-my-bucket";

  @Test
  @LocalS3(strictBucketNames = true)
  void rejectsBucketNamesThatAmazonS3Rejects(S3Client client) {
    S3Exception e = assertThrows(S3Exception.class, () -> client.createBucket(b -> b.bucket(RESERVED_PREFIX_NAME)));
    assertEquals(400, e.statusCode());
    assertEquals("InvalidBucketName", e.awsErrorDetails().errorCode());
    assertDoesNotThrow(() -> client.createBucket(b -> b.bucket("my-bucket")));
  }

  @Test
  @LocalS3
  void acceptsAnyBucketNameByDefault(S3Client client) {
    assertDoesNotThrow(() -> client.createBucket(b -> b.bucket(RESERVED_PREFIX_NAME)));
  }

}
