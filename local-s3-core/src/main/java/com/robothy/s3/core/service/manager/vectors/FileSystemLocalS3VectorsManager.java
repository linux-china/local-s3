package com.robothy.s3.core.service.manager.vectors;

import com.robothy.s3.core.model.internal.s3vectors.LocalS3VectorsMetadata;
import com.robothy.s3.core.model.internal.s3vectors.VectorBucketMetadata;
import com.robothy.s3.core.service.BucketGuard;
import com.robothy.s3.core.service.DefaultBucketGuard;
import com.robothy.s3.core.service.loader.vectors.S3VectorsMetadataLoader;
import com.robothy.s3.core.service.locks.BucketLock;
import com.robothy.s3.core.service.s3vectors.S3VectorsService;
import com.robothy.s3.core.storage.LocalS3Store;
import com.robothy.s3.core.storage.MetadataStore;
import com.robothy.s3.core.storage.PersistencePolicy;
import com.robothy.s3.core.storage.s3vectors.MVStoreVectorBucketMetadataStore;
import com.robothy.s3.core.storage.s3vectors.TransactionalVectorStorage;
import com.robothy.s3.core.storage.s3vectors.VectorStorage;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * Persistent implementation of {@linkplain LocalS3VectorsManager}, which keeps the vector buckets and vectors in a data
 * directory.
 *
 * <p>The metadata of the vector buckets is written to the {@linkplain LocalS3Store} of the data directory, the one that
 * the S3 buckets of the same directory are written to: both halves of a service commit to one file, so a copy of a data
 * directory is a consistent point of the whole service. The store of a directory is shared, so opening it here takes
 * another hold of the store that the {@link com.robothy.s3.core.service.manager.LocalS3Manager} of the same directory
 * opened, and {@linkplain #close()} releases that hold.
 *
 * <p>The service is created once, with the manager: a service holds the metadata loaded from the directory, so a second
 * one would keep a copy of its own and write the same records as the first.
 */
final class FileSystemLocalS3VectorsManager implements LocalS3VectorsManager {

  private final LocalS3VectorsMetadata vectorsMetadata;

  /**
   * The key-value store of the data directory, shared with the S3 buckets it holds.
   */
  private final LocalS3Store localS3Store;

  private final MetadataStore<VectorBucketMetadata> metadataStore;

  private final S3VectorsService s3VectorsService;

  FileSystemLocalS3VectorsManager(Path dataPath, PersistencePolicy persistencePolicy) {
    Objects.requireNonNull(dataPath, "Data directory is required to create a persistent LocalS3 Vectors service.");
    Objects.requireNonNull(persistencePolicy, "persistencePolicy");
    // One store per data directory, which holds the metadata and stays open: it is both loaded from and written to.
    this.localS3Store = LocalS3Store.persistent(dataPath, persistencePolicy);
    this.metadataStore = MVStoreVectorBucketMetadataStore.create(localS3Store);
    this.vectorsMetadata = new S3VectorsMetadataLoader().load(metadataStore);
    VectorStorageIds.seedGenerator(vectorsMetadata);

    // Vector data belongs to the data path, not to the working directory, which may not even be writable.
    // The vectors that a change deletes are deleted once the vector bucket is persisted, and the vectors that it
    // writes are deleted if it fails.
    TransactionalVectorStorage vectorStorage = new TransactionalVectorStorage(
        VectorStorage.createFileSystem(LocalS3VectorsManager.vectorStorageDirectory(dataPath)));
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
   * Release the hold this manager has on the store of its data directory, which closes the store once the S3 buckets
   * of the same directory have released it too. The service of the manager can't be used afterwards.
   */
  @Override
  public void close() {
    localS3Store.close();
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
