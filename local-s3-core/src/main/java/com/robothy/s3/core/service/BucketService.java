package com.robothy.s3.core.service;

import com.robothy.s3.core.constants.ServiceConstants;
import com.robothy.s3.core.exception.LocalS3InvalidArgumentException;
import com.robothy.s3.core.model.Bucket;
import com.robothy.s3.core.model.answers.ListBucketsAns;
import com.robothy.s3.core.model.internal.BucketMetadata;
import com.robothy.s3.core.model.request.ListBucketsOptions;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public interface BucketService extends CreateBucketService, BucketVersioningService, BucketTaggingService,
    BucketAclService, BucketPolicyService, BucketReplicationService,
    BucketEncryptionService, BucketPublicAccessBlockService, BucketPolicyStatusService, BucketCorsService,
    BucketLifecycleService, BucketObjectLockService, BucketNotificationService, BucketStoredConfigurationService,
    BucketPublicAccessService {

  /**
   * Delete a bucket.
   */
  Bucket deleteBucket(String bucketName);

  /**
   * Get bucket info.
   */
  Bucket getBucket(String bucketName);

  /**
   * List all buckets.
   *
   * @return all buckets.
   */
  default List<Bucket> listBuckets() {
    return localS3Metadata().listBuckets().stream()
        .map(Bucket::fromBucketMetadata).collect(Collectors.toList());
  }

  /**
   * The max number of buckets of a page of {@code ListBuckets}, and the size of a page of a paginated request that
   * doesn't set one.
   */
  int MAX_BUCKETS = 10000;

  /**
   * <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_ListBuckets.html">ListBuckets</a> with pagination.
   *
   * <p>The buckets are listed in the order they were created, and buckets created in the same millisecond by name. A
   * request without parameters gets every bucket on one page, like Amazon S3 answers it; a request with any of them
   * gets at most {@code max-buckets}, or {@value #MAX_BUCKETS}, buckets, and a continuation token if more of them
   * follow. The token marks the last bucket of the page, rather than the number of buckets listed, so that a bucket
   * created or deleted between two pages neither repeats a bucket nor skips one. A bucket that was created without a
   * location constraint is in the region {@linkplain ServiceConstants#DEFAULT_REGION}.
   *
   * @param options the parameters of the request.
   * @return a page of buckets.
   * @throws LocalS3InvalidArgumentException if {@code max-buckets} isn't between 1 and {@value #MAX_BUCKETS}, or the
   *     continuation token isn't one that a listing answered with.
   */
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
    ListBucketsPosition after = ListBucketsPosition.decode(options.continuationToken());

    List<Bucket> page = new ArrayList<>();
    String continuationToken = null;
    for (BucketMetadata bucket : localS3Metadata().listBuckets()) {
      if (after != null && !after.isBefore(bucket)) {
        continue;
      }
      if (options.prefix() != null && !bucket.getBucketName().startsWith(options.prefix())) {
        continue;
      }
      String region = ServiceConstants.effectiveRegion(bucket.getRegion());
      if (options.bucketRegion() != null && !options.bucketRegion().equals(region)) {
        continue;
      }
      if (page.size() == pageSize) {
        // Another bucket follows the full page.
        continuationToken = ListBucketsPosition.of(page.get(page.size() - 1)).encode();
        break;
      }
      page.add(Bucket.fromBucketMetadata(bucket));
    }
    return new ListBucketsAns(page, continuationToken);
  }

}
