package com.robothy.s3.core.service.manager.vectors;

import com.robothy.s3.core.model.internal.s3vectors.VectorBucketMetadata;
import com.robothy.s3.core.model.internal.s3vectors.VectorIndexMetadata;
import com.robothy.s3.core.service.s3vectors.S3VectorsService;
import java.util.List;

/**
 * The amount of S3 Vectors data of a LocalS3 service.
 *
 * @param vectorBuckets the number of vector buckets.
 * @param indexes the number of vector indexes.
 * @param vectors the number of vectors.
 */
public record VectorStatistics(long vectorBuckets, long indexes, long vectors) {

  /**
   * Count the data of a service, each vector bucket under its read lock.
   *
   * @param service the S3 Vectors service of the LocalS3 service.
   * @return the statistics.
   */
  static VectorStatistics collect(S3VectorsService service) {
    long[] totals = new long[3];
    List<String> bucketNames = List.copyOf(service.metadata().getVectorBucketMetadataMap().keySet());
    for (String bucketName : bucketNames) {
      service.withBucketReadLock(bucketName, () -> {
        VectorBucketMetadata bucket = service.metadata().getVectorBucketMetadataMap().get(bucketName);
        if (bucket != null) {
          totals[0]++;
          for (VectorIndexMetadata index : bucket.getIndexes().values()) {
            totals[1]++;
            totals[2] += index.getVectorObjects().size();
          }
        }
        return null;
      });
    }
    return new VectorStatistics(totals[0], totals[1], totals[2]);
  }

}
