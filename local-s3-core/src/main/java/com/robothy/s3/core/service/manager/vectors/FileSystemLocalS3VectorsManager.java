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

final class FileSystemLocalS3VectorsManager implements LocalS3VectorsManager {

  private final Path s3VectorsDataPath;

  /**
   * Locks of the vector buckets of this service.
   */
  private final BucketLock bucketLock = BucketLock.create();

  public FileSystemLocalS3VectorsManager(Path s3VectorsDataPath) {
    this.s3VectorsDataPath = s3VectorsDataPath;
  }

  @Override
  public S3VectorsService s3VectorsService() {
    LocalS3VectorsMetadata vectorsMetadata = MetadataLoader.create(LocalS3VectorsMetadata.class)
        .load(s3VectorsDataPath);
    VectorStorageIds.seedGenerator(vectorsMetadata);

    // Vector data belongs to the data path, not to the working directory, which may not even be writable.
    // The vectors that a change deletes are deleted once the vector bucket is persisted.
    TransactionalVectorStorage vectorStorage = new TransactionalVectorStorage(VectorStorage.createFileSystem(
        s3VectorsDataPath.resolve(VECTOR_STORAGE_DIRECTORY), MAX_CACHED_VECTOR_COUNT));
    MetadataStore<VectorBucketMetadata> metadataStore = FileSystemVectorBucketMetadataStore.create(this.s3VectorsDataPath);
    BucketGuard bucketGuard = new DefaultBucketGuard<>(bucketLock,
        bucketName -> vectorsMetadata.getVectorBucketMetadata(bucketName).get(), metadataStore, vectorStorage, null);
    return S3VectorsService.create(vectorsMetadata, vectorStorage, bucketGuard);
  }

}
