package com.robothy.s3.jupiter.extensions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import com.robothy.s3.jupiter.LocalS3;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;

class VersionedBucketsTest {

  @Test
  @LocalS3(buckets = "plain", versionedBuckets = "audit")
  void createsBucketsWithVersioningEnabled(S3Client client) {
    assertNull(client.getBucketVersioning(b -> b.bucket("plain")).status());
    assertEquals(BucketVersioningStatus.ENABLED, client.getBucketVersioning(b -> b.bucket("audit")).status());
  }

}
