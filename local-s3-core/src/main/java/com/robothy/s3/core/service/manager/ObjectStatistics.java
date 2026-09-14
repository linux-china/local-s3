package com.robothy.s3.core.service.manager;

import com.robothy.s3.core.model.internal.BucketMetadata;
import com.robothy.s3.core.model.internal.ObjectMetadata;
import com.robothy.s3.core.model.internal.UploadMetadata;
import com.robothy.s3.core.model.internal.VersionedObjectMetadata;
import com.robothy.s3.core.service.BucketService;
import java.util.List;
import java.util.NavigableMap;

/**
 * The amount of Amazon S3 data of a LocalS3 service.
 *
 * @param buckets the number of buckets.
 * @param objects the number of objects, i.e. of keys whose latest version isn't a delete marker, which is what
 *     {@code ListObjects} lists.
 * @param objectVersions the number of versions of objects that aren't delete markers, noncurrent ones included.
 * @param deleteMarkers the number of delete markers.
 * @param objectBytes the size in bytes of the content of {@code objectVersions}.
 * @param multipartUploads the number of multipart uploads in progress.
 */
public record ObjectStatistics(long buckets, long objects, long objectVersions, long deleteMarkers, long objectBytes,
                               long multipartUploads) {

  /**
   * Count the data of a service. Each bucket is counted under its read lock, so that its numbers are consistent,
   * but the buckets are counted one after the other, so the totals may mix states of a service that is changing.
   *
   * @param bucketService the bucket service of the LocalS3 service.
   * @return the statistics.
   */
  static ObjectStatistics collect(BucketService bucketService) {
    long[] totals = new long[6];
    List<String> bucketNames = List.copyOf(bucketService.localS3Metadata().getBucketMetadataMap().keySet());
    for (String bucketName : bucketNames) {
      bucketService.withBucketReadLock(bucketName, () -> {
        // Looked up again under the lock: the bucket may be gone, or the data of the service replaced.
        bucketService.localS3Metadata().getBucketMetadata(bucketName).ifPresent(bucket -> count(bucket, totals));
        return null;
      });
    }
    return new ObjectStatistics(totals[0], totals[1], totals[2], totals[3], totals[4], totals[5]);
  }

  private static void count(BucketMetadata bucket, long[] totals) {
    totals[0]++;
    for (ObjectMetadata object : bucket.getObjectMap().values()) {
      if (!object.getLatest().isDeleted()) {
        totals[1]++;
      }
      for (VersionedObjectMetadata version : object.getVersionedObjectMap().values()) {
        if (version.isDeleted()) {
          totals[3]++;
        } else {
          totals[2]++;
          totals[4] += version.getSize();
        }
      }
    }
    for (NavigableMap<String, UploadMetadata> uploads : bucket.getUploads().values()) {
      totals[5] += uploads.size();
    }
  }

}
