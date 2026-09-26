package com.robothy.s3.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.core.model.internal.BucketMetadata;
import com.robothy.s3.core.model.internal.LocalS3Metadata;
import com.robothy.s3.datatypes.PolicyStatus;
import com.robothy.s3.datatypes.PublicAccessBlockConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BucketPolicyStatusServiceTest {
  
  private final LocalS3Metadata localS3Metadata = new LocalS3Metadata();
  
  private final BucketService bucketService = new BucketServiceMock(localS3Metadata);

  @BeforeEach
  void setup() {
    bucketService.createBucket("test-bucket", "us-east-1");
  }

  @Test
  void testGetBucketPolicyStatusWithNoPolicy() {
    PolicyStatus status = bucketService.getBucketPolicyStatus("test-bucket");
    assertNotNull(status);
    assertFalse(status.getIsPublic());
  }
    @Test
  void testGetBucketPolicyStatusWithPolicy() {
    // Set a policy
    BucketMetadata bucketMetadata = localS3Metadata.getBucketMetadata("test-bucket").orElseThrow();
    bucketMetadata.setPolicy("{}"); // Any non-null policy
    
    // No public access blocks - should be public
    PolicyStatus status = bucketService.getBucketPolicyStatus("test-bucket");
    assertNotNull(status);
    assertTrue(status.getIsPublic());
    
    // Add public access blocks with BlockPublicPolicy=true
    PublicAccessBlockConfiguration config = new PublicAccessBlockConfiguration();
    config.setBlockPublicPolicy(true);
    bucketService.putPublicAccessBlock("test-bucket", config);
    
    // With blocks - should not be public
    status = bucketService.getBucketPolicyStatus("test-bucket");
    assertNotNull(status);
    assertFalse(status.getIsPublic());
  }
  private static class BucketServiceMock implements BucketService {

    private final com.robothy.s3.core.service.BucketGuard bucketGuard =
        com.robothy.s3.core.service.BucketGuard.inMemory();

    @Override
    public com.robothy.s3.core.service.BucketGuard bucketGuard() {
      return bucketGuard;
    }

    private final LocalS3Metadata localS3Metadata;
    
    public BucketServiceMock(LocalS3Metadata localS3Metadata) {
      this.localS3Metadata = localS3Metadata;
    }
    
    @Override
    public LocalS3Metadata localS3Metadata() {
      return localS3Metadata;
    }
    
    @Override
    public com.robothy.s3.core.model.Bucket getBucket(String bucketName) {
      return com.robothy.s3.core.model.Bucket.builder()
          .name(bucketName)
          .creationDate(System.currentTimeMillis())
          .build();
    }
    
    @Override
    public com.robothy.s3.core.model.Bucket deleteBucket(String bucketName) {
      throw new UnsupportedOperationException("Not implemented for test");
    }
    
    @Override
    public Boolean getVersioningEnabled(String bucketName) {
      return null; // Return null as default (versioning not set)
    }
    
    @Override
    public com.robothy.s3.core.model.Bucket setVersioningEnabled(String bucketName, boolean versioningEnabled) {
      return getBucket(bucketName);
    }

    @Override
    public com.robothy.s3.core.model.Bucket putVersioningConfiguration(String bucketName, Boolean versioningEnabled,
                                                                       Boolean mfaDeleteEnabled) {
      return getBucket(bucketName);
    }
  }
}
