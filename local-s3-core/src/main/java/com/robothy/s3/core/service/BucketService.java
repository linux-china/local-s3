package com.robothy.s3.core.service;

import com.robothy.s3.core.model.Bucket;
import java.util.List;
import java.util.stream.Collectors;

public interface BucketService extends CreateBucketService, BucketVersioningService, BucketTaggingService,
    BucketAclService, BucketPolicyService, BucketReplicationService,
    BucketEncryptionService, BucketPublicAccessBlockService, BucketPolicyStatusService, BucketCorsService {

  /**
   * Delete a bucket.
   */
  Bucket deleteBucket(String bucketName);

  /**
   * Get bucket info.
   */
  Bucket getBucket(String bucketName);

  /**
   * List all buckets.
   *
   * @return all buckets.
   */
  default List<Bucket> listBuckets() {
    return localS3Metadata().listBuckets().stream()
        .map(Bucket::fromBucketMetadata).collect(Collectors.toList());
  }

}

