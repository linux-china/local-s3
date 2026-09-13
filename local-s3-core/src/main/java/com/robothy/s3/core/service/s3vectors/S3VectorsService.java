package com.robothy.s3.core.service.s3vectors;

import com.robothy.s3.core.service.BucketGuard;
import com.robothy.s3.core.model.internal.s3vectors.LocalS3VectorsMetadata;
import com.robothy.s3.core.storage.s3vectors.VectorStorage;

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
    return new DefaultS3VectorsService(metadata, vectorStorage, bucketGuard);
  }

}