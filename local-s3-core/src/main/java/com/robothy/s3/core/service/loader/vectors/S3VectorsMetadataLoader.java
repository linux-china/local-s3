package com.robothy.s3.core.service.loader.vectors;

import com.robothy.s3.core.model.internal.s3vectors.LocalS3VectorsMetadata;
import com.robothy.s3.core.model.internal.s3vectors.VectorBucketMetadata;
import com.robothy.s3.core.service.loader.MetadataLoader;
import com.robothy.s3.core.storage.LocalS3Store;
import com.robothy.s3.core.storage.MetadataStore;
import com.robothy.s3.core.storage.s3vectors.MVStoreVectorBucketMetadataStore;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Loads the vector buckets of a data directory from the {@linkplain LocalS3Store} that holds them, the same store that
 * the S3 buckets of the directory are read from.
 */
public class S3VectorsMetadataLoader implements MetadataLoader<LocalS3VectorsMetadata> {

  /**
   * Load the vector buckets of a data directory, whose store is opened and closed again.
   *
   * @param dataPath the data directory.
   * @return the loaded metadata; empty if the directory holds no store.
   */
  @Override
  public LocalS3VectorsMetadata load(Path dataPath) {
    Objects.requireNonNull(dataPath);
    // Read-only, so that loading the initial data of a path doesn't lock it against the services that start from it.
    try (LocalS3Store store = LocalS3Store.readOnly(dataPath)) {
      return load(MVStoreVectorBucketMetadataStore.create(store));
    }
  }

  /**
   * Load the vector buckets from an open metadata store, e.g. the one that a manager writes to, which must not be
   * opened a second time.
   *
   * @param vectorBucketMetaStore the store that holds the vector buckets.
   * @return the loaded metadata.
   */
  public LocalS3VectorsMetadata load(MetadataStore<VectorBucketMetadata> vectorBucketMetaStore) {
    Objects.requireNonNull(vectorBucketMetaStore);
    LocalS3VectorsMetadata vectorsMetadata = new LocalS3VectorsMetadata();
    vectorBucketMetaStore.fetchAll().forEach(vectorsMetadata::addVectorBucketMetadata);
    return vectorsMetadata;
  }

}
