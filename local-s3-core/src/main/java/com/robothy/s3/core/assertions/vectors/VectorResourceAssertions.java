package com.robothy.s3.core.assertions.vectors;

import com.robothy.s3.core.exception.vectors.LocalS3VectorException;
import com.robothy.s3.core.model.internal.s3vectors.VectorBucketMetadata;
import com.robothy.s3.core.model.internal.s3vectors.VectorResourceIdentifier;
import com.robothy.s3.core.service.s3vectors.S3VectorsMetadataAware;
import java.util.Map;

/**
 * Assertions of the resources that S3 Vectors tags: vector buckets and indexes.
 */
public class VectorResourceAssertions {

  /**
   * Assert that the resource exists, and get its tags.
   *
   * @param metadataAware service that provides metadata access
   * @param resource      the vector bucket or index
   * @return the tags of the resource, which a change of the resource changes.
   * @throws LocalS3VectorException if the vector bucket or the index doesn't exist
   */
  public static Map<String, String> assertResourceExists(S3VectorsMetadataAware metadataAware,
                                                         VectorResourceIdentifier resource) {
    VectorBucketMetadata bucketMetadata =
        VectorBucketAssertions.assertVectorBucketExists(metadataAware, resource.bucketName());
    return resource.isIndex()
        ? VectorIndexAssertions.assertVectorIndexExists(bucketMetadata, resource.indexName()).getTags()
        : bucketMetadata.getTags();
  }

}
