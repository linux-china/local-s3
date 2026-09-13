package com.robothy.s3.core.service.manager.vectors;

import com.robothy.s3.core.model.internal.s3vectors.LocalS3VectorsMetadata;
import com.robothy.s3.core.model.internal.s3vectors.VectorBucketMetadata;
import com.robothy.s3.core.service.loader.MetadataLoader;
import com.robothy.s3.core.service.locks.BucketLock;
import com.robothy.s3.core.service.BucketGuard;
import com.robothy.s3.core.service.DefaultBucketGuard;
import com.robothy.s3.core.service.s3vectors.S3VectorsService;
import com.robothy.s3.core.storage.MetadataStore;
import com.robothy.s3.core.storage.s3vectors.FileSystemVectorBucketMetadataStore;
import com.robothy.s3.core.storage.s3vectors.TransactionalVectorStorage;
import com.robothy.s3.core.storage.s3vectors.VectorStorage;
import java.nio.file.Path;
import java.util.Map;

/**
 * Persistent implementation of {@linkplain LocalS3VectorsManager}, which keeps the vector buckets and vectors in a data
 * path.
 *
 * <p>The service is created once, with the manager: a service holds the metadata loaded from the path, so a second one
 * would keep a copy of its own and write the same files as the first.
 */
final class FileSystemLocalS3VectorsManager implements LocalS3VectorsManager {

  private final LocalS3VectorsMetadata vectorsMetadata;

  private final MetadataStore<VectorBucketMetadata> metadataStore;

  private final S3VectorsService s3VectorsService;

  public FileSystemLocalS3VectorsManager(Path s3VectorsDataPath) {
    this.vectorsMetadata = MetadataLoader.create(LocalS3VectorsMetadata.class).load(s3VectorsDataPath);
    VectorStorageIds.seedGenerator(vectorsMetadata);

    // Vector data belongs to the data path, not to the working directory, which may not even be writable.
    // The vectors that a change deletes are deleted once the vector bucket is persisted, and the vectors that it
    // writes are deleted if it fails.
    TransactionalVectorStorage vectorStorage = new TransactionalVectorStorage(VectorStorage.createFileSystem(
        s3VectorsDataPath.resolve(VECTOR_STORAGE_DIRECTORY), MAX_CACHED_VECTOR_COUNT));
    this.metadataStore = FileSystemVectorBucketMetadataStore.create(s3VectorsDataPath);
    BucketGuard bucketGuard = new DefaultBucketGuard<>(BucketLock.create(),
        bucketName -> vectorsMetadata.getVectorBucketMetadata(bucketName).get(), metadataStore, vectorStorage,
        this::reloadVectorBucketMetadata);
    this.s3VectorsService = S3VectorsService.create(vectorsMetadata, vectorStorage, bucketGuard);
  }

  @Override
  public S3VectorsService s3VectorsService() {
    return s3VectorsService;
  }

  /**
   * Replace the in-memory metadata of a vector bucket with the persisted one, after a change of the bucket failed.
   */
  private void reloadVectorBucketMetadata(String vectorBucketName) {
    Map<String, VectorBucketMetadata> buckets = vectorsMetadata.getVectorBucketMetadataMap();
    if (metadataStore.exists(vectorBucketName)) {
      buckets.put(vectorBucketName, metadataStore.fetch(vectorBucketName));
    } else {
      buckets.remove(vectorBucketName);
    }
  }

}
