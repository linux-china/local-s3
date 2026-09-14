package com.robothy.s3.core.service.s3vectors;

import com.robothy.s3.core.assertions.vectors.VectorBucketAssertions;
import com.robothy.s3.core.assertions.vectors.VectorIndexAssertions;
import com.robothy.s3.core.model.internal.s3vectors.VectorBucketMetadata;
import com.robothy.s3.core.model.internal.s3vectors.VectorIndexMetadata;
import com.robothy.s3.core.model.internal.s3vectors.VectorObjectMetadata;
import java.util.Objects;

public interface DeleteIndexService extends S3VectorsMetadataAware, S3VectorsStorageAware {

  /**
   * Delete an index and the data of its vectors, which a persistent storage deletes once the vector bucket is
   * persisted, so that no unreferenced vector data is left behind to be loaded again at startup.
   */
  default void deleteIndex(String vectorBucketName, String indexName) {
    changeBucket(vectorBucketName, () -> {
      VectorBucketMetadata bucketMetadata = VectorBucketAssertions.assertVectorBucketExists(this, vectorBucketName);
      VectorIndexMetadata indexMetadata = VectorIndexAssertions.assertVectorIndexExists(bucketMetadata, indexName);
      for (VectorObjectMetadata vector : indexMetadata.getVectorObjects().values()) {
        if (Objects.nonNull(vector.getStorageId())) {
          vectorStorage().deleteVectorData(vector.getStorageId());
        }
      }
      bucketMetadata.removeIndexMetadata(indexName);
    });
  }
}
