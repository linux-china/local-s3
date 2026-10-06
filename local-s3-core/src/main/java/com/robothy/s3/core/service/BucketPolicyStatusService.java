package com.robothy.s3.core.service;

import com.robothy.s3.core.assertions.BucketAssertions;
import com.robothy.s3.core.model.internal.BucketMetadata;
import com.robothy.s3.datatypes.PolicyStatus;
import com.robothy.s3.datatypes.PublicAccessBlockConfiguration;
import java.util.Optional;

/**
 * See {@code docs/semantics.md#stored-not-applied}.
 */
public interface BucketPolicyStatusService extends LocalS3MetadataApplicable {

  default PolicyStatus getBucketPolicyStatus(String bucketName) {
    return withBucketReadLock(bucketName, () -> {
      BucketAssertions.assertBucketNameIsValid(bucketName);
      BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucketName);
    
      boolean hasPolicy = bucketMetadata.getPolicy().isPresent();
    
      Optional<PublicAccessBlockConfiguration> publicAccessBlock = bucketMetadata.getPublicAccessBlock();
      boolean hasBlocksEnabled = publicAccessBlock.isPresent() && 
                                (Boolean.TRUE.equals(publicAccessBlock.get().getBlockPublicPolicy()) ||
                                 Boolean.TRUE.equals(publicAccessBlock.get().getRestrictPublicBuckets()));

      boolean isPublic = hasPolicy && !hasBlocksEnabled;
    
      return PolicyStatus.builder()
          .isPublic(isPublic)
          .build();
    });
  }
}
