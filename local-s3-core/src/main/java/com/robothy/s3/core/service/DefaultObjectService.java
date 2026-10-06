package com.robothy.s3.core.service;

import com.robothy.s3.core.model.internal.LocalS3Metadata;
import com.robothy.s3.core.storage.Storage;
import java.util.Objects;
import java.util.function.Supplier;

public class DefaultObjectService implements ObjectService {

  public static ObjectService create(LocalS3Metadata s3Metadata, Storage storage) {
    return create(s3Metadata, storage, BucketGuard.inMemory());
  }

  public static ObjectService create(LocalS3Metadata s3Metadata, Storage storage, BucketGuard bucketGuard) {
    return create(() -> s3Metadata, () -> storage, bucketGuard);
  }

  // Looked up for every operation, so that a reset can replace them within BucketGuard#exclusive.
  public static ObjectService create(Supplier<LocalS3Metadata> s3Metadata, Supplier<Storage> storage,
                                     BucketGuard bucketGuard) {
    return new DefaultObjectService(s3Metadata, storage, bucketGuard);
  }

  private final Supplier<LocalS3Metadata> s3Metadata;

  private final Supplier<Storage> storage;

  private final BucketGuard bucketGuard;

  private DefaultObjectService(Supplier<LocalS3Metadata> s3Metadata, Supplier<Storage> storage,
                                BucketGuard bucketGuard) {
    this.s3Metadata = Objects.requireNonNull(s3Metadata);
    this.storage = Objects.requireNonNull(storage);
    this.bucketGuard = Objects.requireNonNull(bucketGuard);
  }

  @Override
  public LocalS3Metadata localS3Metadata() {
    return this.s3Metadata.get();
  }

  @Override
  public Storage storage() {
    return this.storage.get();
  }

  @Override
  public BucketGuard bucketGuard() {
    return bucketGuard;
  }

}
