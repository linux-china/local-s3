package com.robothy.s3.core.service.s3vectors;

import com.robothy.s3.core.model.internal.s3vectors.LocalS3VectorsMetadata;
import com.robothy.s3.core.service.BucketGuard;
import com.robothy.s3.core.storage.s3vectors.VectorStorage;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Default implementation of {@link S3VectorsService}.
 * All S3 vectors data is managed through the provided metadata instance.
 */
final class DefaultS3VectorsService implements S3VectorsService, S3VectorsStorageAware {

  private final Supplier<LocalS3VectorsMetadata> localS3VectorsMetadata;

  private final Supplier<VectorStorage> vectorStorage;

  private final BucketGuard bucketGuard;

  /**
   * Create a service whose metadata and storage are looked up for every operation, so that a manager can replace
   * them, e.g. to reset the data of the service, within {@linkplain BucketGuard#exclusive}.
   */
  DefaultS3VectorsService(Supplier<LocalS3VectorsMetadata> localS3VectorsMetadata,
                          Supplier<VectorStorage> vectorStorage, BucketGuard bucketGuard) {
    this.localS3VectorsMetadata = Objects.requireNonNull(localS3VectorsMetadata);
    this.vectorStorage = Objects.requireNonNull(vectorStorage);
    this.bucketGuard = Objects.requireNonNull(bucketGuard);
  }

  @Override
  public BucketGuard bucketGuard() {
    return bucketGuard;
  }

  @Override
  public LocalS3VectorsMetadata metadata() {
    return localS3VectorsMetadata.get();
  }

  @Override
  public VectorStorage vectorStorage() {
    return this.vectorStorage.get();
  }
}
