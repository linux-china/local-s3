package com.robothy.s3.core.service;

import com.robothy.s3.core.model.internal.LocalS3Metadata;
import com.robothy.s3.core.storage.Storage;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The {@linkplain ObjectService} of a LocalS3 service, whose operations run within its {@linkplain BucketGuard}.
 */
public class InMemoryObjectService implements ObjectService {

  /**
   * Create a service with a guard of its own, which only locks the buckets.
   *
   * @param s3Metadata the metadata of the service.
   * @param storage the storage of the service.
   * @return the service.
   */
  public static ObjectService create(LocalS3Metadata s3Metadata, Storage storage) {
    return create(s3Metadata, storage, BucketGuard.inMemory());
  }

  /**
   * Create a service.
   *
   * @param s3Metadata the metadata of the service.
   * @param storage the storage of the service.
   * @param bucketGuard the guard of the buckets, shared with the bucket service of the same LocalS3 service.
   * @return the service.
   */
  public static ObjectService create(LocalS3Metadata s3Metadata, Storage storage, BucketGuard bucketGuard) {
    return create(() -> s3Metadata, () -> storage, bucketGuard);
  }

  /**
   * Create a service whose metadata and storage are looked up for every operation, so that a manager can replace
   * them, e.g. to reset the data of the service, within {@linkplain BucketGuard#exclusive}.
   *
   * @param s3Metadata supplies the current metadata of the service.
   * @param storage supplies the current storage of the service.
   * @param bucketGuard the guard of the buckets, shared with the bucket service of the same LocalS3 service.
   * @return the service.
   */
  public static ObjectService create(Supplier<LocalS3Metadata> s3Metadata, Supplier<Storage> storage,
                                     BucketGuard bucketGuard) {
    return new InMemoryObjectService(s3Metadata, storage, bucketGuard);
  }

  private final Supplier<LocalS3Metadata> s3Metadata;

  private final Supplier<Storage> storage;

  private final BucketGuard bucketGuard;

  private InMemoryObjectService(Supplier<LocalS3Metadata> s3Metadata, Supplier<Storage> storage,
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
