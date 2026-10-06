package com.robothy.s3.core.service;

import com.robothy.s3.core.assertions.BucketAssertions;
import com.robothy.s3.core.event.S3Change;
import com.robothy.s3.core.model.Bucket;
import com.robothy.s3.core.model.BucketObjectLockConfiguration;
import com.robothy.s3.core.model.internal.BucketMetadata;
import com.robothy.s3.core.util.BucketPublicAccess;
import com.robothy.s3.datatypes.AccessControlPolicy;
import com.robothy.s3.datatypes.Grant;
import com.robothy.s3.datatypes.Grantee;
import com.robothy.s3.datatypes.Owner;
import java.util.List;

public interface CreateBucketService extends LocalS3MetadataApplicable {

  default Bucket createBucket(String bucketName) {
    return changeBucket(bucketName, BucketGuard.Change.CREATE, () -> {
      return createBucket(bucketName, null);
    });
  }

  default Bucket createBucket(String bucketName, String region) {
    return createBucket(bucketName, region, false);
  }

  default Bucket createBucket(String bucketName, String region, boolean objectLockEnabled) {
    return changeBucket(bucketName, BucketGuard.Change.CREATE, () -> {
      BucketAssertions.assertBucketNameIsValid(bucketName);
      BucketAssertions.assertBucketNotExists(localS3Metadata(), bucketName);
      BucketMetadata bucketMetadata = new BucketMetadata();
      bucketMetadata.setBucketName(bucketName);
      bucketMetadata.setCreationDate(System.currentTimeMillis());
      bucketMetadata.setRegion(region);
      if (objectLockEnabled) {
        bucketMetadata.setVersioningEnabled(true);
        bucketMetadata.setObjectLock(new BucketObjectLockConfiguration(null));
      }
      localS3Metadata().addBucketMetadata(bucketMetadata);
      publishChange(S3Change.bucketCreated("CreateBucket", bucketName, region));
      return Bucket.fromBucketMetadata(bucketMetadata);
    });
  }

}
