package com.robothy.s3.core.service.s3vectors;

import com.robothy.s3.core.service.BucketGuard;
import com.robothy.s3.core.model.internal.s3vectors.LocalS3VectorsMetadata;
import com.robothy.s3.core.storage.s3vectors.VectorStorage;
import java.util.function.Supplier;

public interface S3VectorsService extends
    CreateVectorBucketService,
    GetVectorBucketService,
    DeleteVectorBucketService,
    ListVectorBucketsService,
    PutVectorBucketPolicyService,
    GetVectorBucketPolicyService,
    DeleteVectorBucketPolicyService,
    CreateIndexService,
    GetIndexService,
    ListIndexesService,
    DeleteIndexService,
    PutVectorsService,
    GetVectorsService,
    DeleteVectorsService,
    QueryVectorsService,
    ListVectorsService {

  /**
   * Create a service with a guard of its own, which only locks the vector buckets.
   */
  static S3VectorsService create(LocalS3VectorsMetadata metadata, VectorStorage vectorStorage) {
    return create(metadata, vectorStorage, BucketGuard.inMemory());
  }

  /**
   * Create a service whose changes of a vector bucket run within the given guard.
   */
  static S3VectorsService create(LocalS3VectorsMetadata metadata, VectorStorage vectorStorage,
                                 BucketGuard bucketGuard) {
    return create(() -> metadata, () -> vectorStorage, bucketGuard);
  }

  /**
   * Create a service whose metadata and storage are looked up for every operation, so that a manager can replace
   * them, e.g. to reset the data of the service, within {@linkplain BucketGuard#exclusive}.
   */
  static S3VectorsService create(Supplier<LocalS3VectorsMetadata> metadata, Supplier<VectorStorage> vectorStorage,
                                 BucketGuard bucketGuard) {
    return new DefaultS3VectorsService(metadata, vectorStorage, bucketGuard);
  }

}