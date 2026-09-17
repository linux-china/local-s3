package com.robothy.s3.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Bucket;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.CreateBucketResponse;
import software.amazon.awssdk.regions.Region;
import com.robothy.s3.jupiter.LocalS3;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

public class GetBucketLocationIntegrationTest {

  @Test
  @LocalS3
  void getBucketLocation(S3Client s3) {
    assertThrows(NoSuchBucketException.class, () -> s3.getBucketLocation(b -> b.bucket("non-exist-bucket")));
    CreateBucketRequest createBucketRequest = CreateBucketRequest.builder()
        .bucket("bucket")
        .createBucketConfiguration(builder -> builder.locationConstraint(Region.AP_EAST_1.id()))
        .build();
    s3.createBucket(createBucketRequest);
    assertNotNull(s3.headBucket(builder -> builder.bucket("bucket").build()));
    assertEquals(Region.AP_EAST_1.id(), s3.getBucketLocation(builder -> builder.bucket("bucket").build()).locationConstraintAsString());
    assertEquals(Region.AP_EAST_1.id(), s3.headBucket(builder -> builder.bucket("bucket")).bucketRegion());
    assertEquals(Region.AP_EAST_1.id(), bucketRegion(s3, "bucket"));
  }

  @Test
  @LocalS3
  void aBucketWithoutLocationConstraintIsInUsEast1(S3Client s3) {
    CreateBucketResponse response = s3.createBucket(builder -> builder.bucket("default-bucket"));
    assertEquals("/default-bucket", response.location());
    assertEquals("arn:aws:s3:::default-bucket", response.bucketArn());

    assertEquals("us-east-1", s3.headBucket(builder -> builder.bucket("default-bucket")).bucketRegion());
    assertEquals("", s3.getBucketLocation(builder -> builder.bucket("default-bucket")).locationConstraintAsString(),
        "Amazon S3 answers an empty location constraint for the buckets in us-east-1.");
    assertEquals("us-east-1", bucketRegion(s3, "default-bucket"));
  }

  private static String bucketRegion(S3Client s3, String bucketName) {
    return s3.listBuckets().buckets().stream()
        .filter(bucket -> bucket.name().equals(bucketName))
        .map(Bucket::bucketRegion)
        .findFirst()
        .orElseThrow();
  }

}
