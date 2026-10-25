package com.robothy.s3.core.service;

import com.robothy.s3.core.assertions.BucketAssertions;
import com.robothy.s3.core.event.S3Change;
import com.robothy.s3.core.exception.LocalS3RequestException;
import com.robothy.s3.core.exception.S3ErrorCode;
import com.robothy.s3.core.model.Bucket;
import com.robothy.s3.core.model.internal.BucketMetadata;
import com.robothy.s3.core.model.internal.LocalS3Metadata;
import com.robothy.s3.core.storage.Storage;
import java.util.Objects;
import java.util.function.Supplier;

public class DefaultBucketService implements BucketService {

  public static BucketService create(LocalS3Metadata s3Metadata) {
    return create(s3Metadata, Storage.createInMemory(), BucketGuard.inMemory());
  }

  // The storage must be the one of the object service: deleting a bucket deletes the parts of its uploads.
  public static BucketService create(LocalS3Metadata s3Metadata, Storage storage, BucketGuard bucketGuard) {
    Objects.requireNonNull(s3Metadata);
    Objects.requireNonNull(storage);
    return create(() -> s3Metadata, () -> storage, bucketGuard);
  }

  // Looked up for every operation, so that a reset can replace them within BucketGuard#exclusive.
  public static BucketService create(Supplier<LocalS3Metadata> s3Metadata, Supplier<Storage> storage,
                                     BucketGuard bucketGuard) {
    return new DefaultBucketService(Objects.requireNonNull(s3Metadata), Objects.requireNonNull(storage), bucketGuard);
  }

  private final Supplier<LocalS3Metadata> s3Metadata;

  private final Supplier<Storage> storage;

  private final BucketGuard bucketGuard;

  private DefaultBucketService(Supplier<LocalS3Metadata> metadata, Supplier<Storage> storage,
                               BucketGuard bucketGuard) {
    this.s3Metadata = metadata;
    this.storage = storage;
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
      // Uploads in progress don't keep a bucket from being deleted, as in AWS, but nothing else references their
      // parts. Deleted before the bucket is removed, like AbortMultipartUpload does, so that an IN_MEMORY service
      // still references the parts left if a deletion fails; a storage transaction defers them to its commit.
      Storage storage = this.storage.get();
      bucketMetadata.getUploads().values().forEach(uploads -> uploads.values().forEach(upload ->
          upload.getParts().values().forEach(part -> storage.delete(part.getFileId()))));
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
    return putVersioningConfiguration(bucketName, versioningEnabled, null);
  }

  @Override
  public Bucket putVersioningConfiguration(String bucketName, Boolean versioningEnabled, Boolean mfaDeleteEnabled) {
    return changeBucket(bucketName, () -> {
      BucketAssertions.assertBucketNameIsValid(bucketName);
      BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucketName);
      if (Boolean.FALSE.equals(versioningEnabled) && bucketMetadata.getObjectLock().isPresent()) {
        throw new LocalS3RequestException(S3ErrorCode.InvalidBucketState,
            "An Object Lock configuration is present on this bucket, so the versioning state cannot be changed.");
      }
      if (Objects.nonNull(versioningEnabled)) {
        bucketMetadata.setVersioningEnabled(versioningEnabled);
      }
      if (Objects.nonNull(mfaDeleteEnabled)) {
        bucketMetadata.setMfaDeleteEnabled(mfaDeleteEnabled);
      }
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
