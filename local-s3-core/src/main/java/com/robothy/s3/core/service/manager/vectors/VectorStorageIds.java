package com.robothy.s3.core.service.manager.vectors;

import com.robothy.s3.core.model.internal.s3vectors.LocalS3VectorsMetadata;
import com.robothy.s3.core.model.internal.s3vectors.VectorBucketMetadata;
import com.robothy.s3.core.model.internal.s3vectors.VectorIndexMetadata;
import com.robothy.s3.core.model.internal.s3vectors.VectorObjectMetadata;
import com.robothy.s3.core.util.IdUtils;
import java.util.Objects;

/**
 * Keeps the IDs of new vectors apart from the IDs of the vectors that were loaded.
 */
final class VectorStorageIds {

  private VectorStorageIds() {
  }

  /**
   * Make the shared ID generator generate IDs greater than the storage IDs of the loaded vectors, so that a vector
   * stored after a clock correction, or on a machine whose clock is behind the one that stored the loaded vectors,
   * doesn't get the ID of one of them. The storage IDs of the objects are kept apart the same way when their metadata
   * is loaded.
   *
   * @param metadata the loaded metadata.
   */
  static void seedGenerator(LocalS3VectorsMetadata metadata) {
    long maxId = -1L;
    for (VectorBucketMetadata bucket : metadata.getVectorBucketMetadataMap().values()) {
      for (VectorIndexMetadata index : bucket.getIndexes().values()) {
        for (VectorObjectMetadata vector : index.getVectorObjects().values()) {
          if (Objects.nonNull(vector.getStorageId())) {
            maxId = Math.max(maxId, vector.getStorageId());
          }
        }
      }
    }
    IdUtils.defaultGenerator().ensureGreaterThan(maxId);
  }

}
