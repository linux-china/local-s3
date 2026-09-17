package com.robothy.s3.core.service;

import com.robothy.s3.core.assertions.BucketAssertions;
import com.robothy.s3.core.event.S3Change;
import com.robothy.s3.core.exception.LocalS3RequestException;
import com.robothy.s3.core.exception.S3ErrorCode;
import com.robothy.s3.core.model.Bucket;
import com.robothy.s3.core.model.internal.BucketMetadata;
import com.robothy.s3.core.model.internal.LocalS3Metadata;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The {@linkplain BucketService} of a LocalS3 service, used by both the in-memory and the persistent mode. It works on
 * the {@linkplain LocalS3Metadata} of the service, whose operations run within its {@linkplain BucketGuard}.
 */
public class DefaultBucketService implements BucketService {

  /**
   * Create a {@linkplain DefaultBucketService} with a {@linkplain LocalS3Metadata} instance.
   *
   * @param s3Metadata s3 metadata.
   * @return a new {@linkplain DefaultBucketService}.
   */
  public static BucketService create(LocalS3Metadata s3Metadata) {
    return create(s3Metadata, BucketGuard.inMemory());
  }

  /**
   * Create a {@linkplain DefaultBucketService}.
   *
   * @param s3Metadata s3 metadata.
   * @param bucketGuard the guard of the buckets, shared with the object service of the same LocalS3 service.
   * @return a new {@linkplain DefaultBucketService}.
   */
  public static BucketService create(LocalS3Metadata s3Metadata, BucketGuard bucketGuard) {
    Objects.requireNonNull(s3Metadata);
    return create(() -> s3Metadata, bucketGuard);
  }

  /**
   * Create a {@linkplain DefaultBucketService} whose metadata is looked up for every operation, so that a manager
   * can replace it, e.g. to reset the data of the service, within {@linkplain BucketGuard#exclusive}.
   *
   * @param s3Metadata supplies the current metadata of the service.
   * @param bucketGuard the guard of the buckets, shared with the object service of the same LocalS3 service.
   * @return a new {@linkplain DefaultBucketService}.
   */
  public static BucketService create(Supplier<LocalS3Metadata> s3Metadata, BucketGuard bucketGuard) {
    return new DefaultBucketService(Objects.requireNonNull(s3Metadata), bucketGuard);
  }

  private final Supplier<LocalS3Metadata> s3Metadata;

  private final BucketGuard bucketGuard;

  private DefaultBucketService(Supplier<LocalS3Metadata> metadata, BucketGuard bucketGuard) {
    this.s3Metadata = metadata;
    this.bucketGuard = Objects.requireNonNull(bucketGuard);
  }

  @Override
  public BucketGuard bucketGuard() {
    return bucketGuard;
  }

  @Override
  public LocalS3Metadata localS3Metadata() {
    return this.s3Metadata.get();
  }

  @Override
  public Bucket deleteBucket(String bucketName) {
    return changeBucket(bucketName, BucketGuard.Change.DELETE, () -> {
      BucketAssertions.assertBucketNameIsValid(bucketName);
      BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucketName);
      BucketAssertions.assertBucketIsEmpty(bucketMetadata);
      localS3Metadata().getBucketMetadataMap().remove(bucketName);
      publishChange(S3Change.bucketDeleted("DeleteBucket", bucketName, bucketMetadata.getRegion()));
      return Bucket.fromBucketMetadata(bucketMetadata);
    });
  }

  @Override
  public Bucket getBucket(String bucketName) {
    return withBucketReadLock(bucketName, () -> {
      BucketAssertions.assertBucketNameIsValid(bucketName);
      BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucketName);
      return Bucket.fromBucketMetadata(bucketMetadata);
    });
  }

  @Override
  public Bucket setVersioningEnabled(String bucketName, boolean versioningEnabled) {
    return changeBucket(bucketName, () -> {
      BucketAssertions.assertBucketNameIsValid(bucketName);
      BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucketName);
      if (!versioningEnabled && bucketMetadata.getObjectLock().isPresent()) {
        throw new LocalS3RequestException(S3ErrorCode.InvalidBucketState,
            "An Object Lock configuration is present on this bucket, so the versioning state cannot be changed.");
      }
      bucketMetadata.setVersioningEnabled(versioningEnabled);
      return Bucket.fromBucketMetadata(bucketMetadata);
    });
  }

  @Override
  public Boolean getVersioningEnabled(String bucketName) {
    return withBucketReadLock(bucketName, () -> {
      BucketAssertions.assertBucketNameIsValid(bucketName);
      BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucketName);
      return bucketMetadata.getVersioningEnabled();
    });
  }
}
