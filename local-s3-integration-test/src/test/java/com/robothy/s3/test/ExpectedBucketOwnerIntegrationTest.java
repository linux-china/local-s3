package com.robothy.s3.test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.robothy.s3.jupiter.LocalS3;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * {@code x-amz-expected-bucket-owner} and {@code x-amz-source-expected-bucket-owner} are accepted and ignored, see
 * docs/semantics.md: LocalS3 has one account, which owns every bucket, so a request that names any account as the owner
 * succeeds, rather than failing with {@code 403 AccessDenied} as it would on Amazon S3 for an account that isn't the
 * owner.
 */
class ExpectedBucketOwnerIntegrationTest {

  /**
   * An account that isn't the one of LocalS3, {@code 000000000000}.
   */
  private static final String OTHER_ACCOUNT = "111122223333";

  @Test
  @LocalS3(buckets = "bucket")
  void ignoresTheExpectedBucketOwner(S3Client s3) {
    s3.headBucket(b -> b.bucket("bucket").expectedBucketOwner(OTHER_ACCOUNT));
    s3.putObject(b -> b.bucket("bucket").key("a.txt").expectedBucketOwner(OTHER_ACCOUNT),
        RequestBody.fromString("Hello"));
    assertEquals("Hello", s3.getObjectAsBytes(b -> b.bucket("bucket").key("a.txt")
        .expectedBucketOwner(OTHER_ACCOUNT)).asUtf8String());
    assertEquals(1, s3.listObjectsV2(b -> b.bucket("bucket").expectedBucketOwner(OTHER_ACCOUNT)).keyCount());

    s3.copyObject(b -> b.sourceBucket("bucket").sourceKey("a.txt").destinationBucket("bucket").destinationKey("b.txt")
        .expectedBucketOwner(OTHER_ACCOUNT).expectedSourceBucketOwner(OTHER_ACCOUNT));
    assertEquals("Hello", s3.getObjectAsBytes(b -> b.bucket("bucket").key("b.txt")).asUtf8String());

    s3.deleteObject(b -> b.bucket("bucket").key("a.txt").expectedBucketOwner(OTHER_ACCOUNT));
    assertEquals(1, s3.listObjectsV2(b -> b.bucket("bucket")).keyCount());
  }

}
