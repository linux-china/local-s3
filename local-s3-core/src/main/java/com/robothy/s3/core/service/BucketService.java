package com.robothy.s3.core.service;

import com.robothy.s3.core.assertions.BucketAssertions;
import com.robothy.s3.core.constants.ServiceConstants;
import com.robothy.s3.core.exception.LocalS3InvalidArgumentException;
import com.robothy.s3.core.model.Bucket;
import com.robothy.s3.core.model.answers.ListBucketsAns;
import com.robothy.s3.core.model.internal.BucketMetadata;
import com.robothy.s3.core.model.request.ListBucketsOptions;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public interface BucketService extends CreateBucketService, BucketVersioningService, BucketTaggingService,
    BucketAclService, BucketPolicyService, BucketReplicationService,
    BucketEncryptionService, BucketPublicAccessBlockService, BucketPolicyStatusService, BucketCorsService,
    BucketLifecycleService, BucketObjectLockService, BucketNotificationService, BucketStoredConfigurationService,
    BucketPublicAccessService {

  Bucket deleteBucket(String bucketName);

  Bucket getBucket(String bucketName);

  default List<Bucket> listBuckets() {
    return localS3Metadata().listBuckets().stream()
        .map(Bucket::fromBucketMetadata).collect(Collectors.toList());
  }

  int MAX_BUCKETS = 10000;

  /** See {@code docs/apis.md#supported-amazon-s3-apis}. */
  default ListBucketsAns listBuckets(ListBucketsOptions options) {
    if (!options.isPaginated()) {
      return new ListBucketsAns(listBuckets(), null);
    }
    Integer maxBuckets = options.maxBuckets();
    if (maxBuckets != null && (maxBuckets < 1 || maxBuckets > MAX_BUCKETS)) {
      throw new LocalS3InvalidArgumentException("max-buckets", String.valueOf(maxBuckets),
          "Argument max-buckets must be an integer between 1 and " + MAX_BUCKETS + ".");
    }
    int pageSize = maxBuckets == null ? MAX_BUCKETS : maxBuckets;
    return listBucketsPage(options.continuationToken(), pageSize, bucket ->
        (options.prefix() == null || bucket.getBucketName().startsWith(options.prefix()))
            && (options.bucketRegion() == null
                || options.bucketRegion().equals(ServiceConstants.effectiveRegion(bucket.getRegion()))));
  }

  int MAX_DIRECTORY_BUCKETS = 1000;

  /** See {@code docs/apis.md#supported-amazon-s3-apis}. */
  default ListBucketsAns listDirectoryBuckets(String continuationToken, Integer maxDirectoryBuckets) {
    if (maxDirectoryBuckets != null && (maxDirectoryBuckets < 0 || maxDirectoryBuckets > MAX_DIRECTORY_BUCKETS)) {
      throw new LocalS3InvalidArgumentException("max-directory-buckets", String.valueOf(maxDirectoryBuckets),
          "Argument max-directory-buckets must be an integer between 0 and " + MAX_DIRECTORY_BUCKETS + ".");
    }
    int pageSize = maxDirectoryBuckets == null || maxDirectoryBuckets == 0 ? MAX_DIRECTORY_BUCKETS : maxDirectoryBuckets;
    return listBucketsPage(continuationToken, pageSize,
        bucket -> BucketAssertions.isDirectoryBucketName(bucket.getBucketName()));
  }

  private ListBucketsAns listBucketsPage(String continuationToken, int pageSize, Predicate<BucketMetadata> filter) {
    ListBucketsPosition after = ListBucketsPosition.decode(continuationToken);

    List<Bucket> page = new ArrayList<>();
    String nextContinuationToken = null;
    for (BucketMetadata bucket : localS3Metadata().listBuckets()) {
      if (after != null && !after.isBefore(bucket) || !filter.test(bucket)) {
        continue;
      }
      if (page.size() == pageSize) {
        // Another bucket follows the full page.
        nextContinuationToken = ListBucketsPosition.of(page.getLast()).encode();
        break;
      }
      page.add(Bucket.fromBucketMetadata(bucket));
    }
    return new ListBucketsAns(page, nextContinuationToken);
  }

}
