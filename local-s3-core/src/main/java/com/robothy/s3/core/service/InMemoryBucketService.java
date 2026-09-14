package com.robothy.s3.core.service;

import com.robothy.s3.core.assertions.BucketAssertions;
import com.robothy.s3.core.model.Bucket;
import com.robothy.s3.core.model.internal.BucketMetadata;
import com.robothy.s3.core.model.internal.LocalS3Metadata;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * In memory implementation of {@linkplain BucketService}. All related data
 * are stored in memory.
 */
public class InMemoryBucketService implements BucketService {

  /**
   * Create an {@linkplain InMemoryBucketService} with a {@linkplain LocalS3Metadata} instance.
   *
   * @param s3Metadata s3 metadata.
   * @return a new {@linkplain InMemoryBucketService} with a {@linkplain BucketMetadata} from the {@code provider}.
   */
  public static BucketService create(LocalS3Metadata s3Metadata) {
    return create(s3Metadata, BucketGuard.inMemory());
  }

  /**
   * Create an {@linkplain InMemoryBucketService}.
   *
   * @param s3Metadata s3 metadata.
   * @param bucketGuard the guard of the buckets, shared with the object service of the same LocalS3 service.
   * @return a new {@linkplain InMemoryBucketService}.
   */
  public static BucketService create(LocalS3Metadata s3Metadata, BucketGuard bucketGuard) {
    Objects.requireNonNull(s3Metadata);
    return create(() -> s3Metadata, bucketGuard);
  }

  /**
   * Create an {@linkplain InMemoryBucketService} whose metadata is looked up for every operation, so that a manager
   * can replace it, e.g. to reset the data of the service, within {@linkplain BucketGuard#exclusive}.
   *
   * @param s3Metadata supplies the current metadata of the service.
   * @param bucketGuard the guard of the buckets, shared with the object service of the same LocalS3 service.
   * @return a new {@linkplain InMemoryBucketService}.
   */
  public static BucketService create(Supplier<LocalS3Metadata> s3Metadata, BucketGuard bucketGuard) {
    return new InMemoryBucketService(Objects.requireNonNull(s3Metadata), bucketGuard);
  }

  private final Supplier<LocalS3Metadata> s3Metadata;

  private final BucketGuard bucketGuard;

  private InMemoryBucketService(Supplier<LocalS3Metadata> metadata, BucketGuard bucketGuard) {
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
