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
    return listBucketsPage(options.continuationToken(), pageSize, bucket ->
        (options.prefix() == null || bucket.getBucketName().startsWith(options.prefix()))
            && (options.bucketRegion() == null
                || options.bucketRegion().equals(ServiceConstants.effectiveRegion(bucket.getRegion()))));
  }

  /**
   * The max number of buckets of a page of {@code ListDirectoryBuckets}, and the size of a page of a request that
   * doesn't set one.
   */
  int MAX_DIRECTORY_BUCKETS = 1000;

  /**
   * <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_ListDirectoryBuckets.html">ListDirectoryBuckets</a>
   * of S3 Express One Zone: the buckets whose names are the names of
   * {@linkplain BucketAssertions#isDirectoryBucketName directory buckets}, e.g. {@code my-bucket--usw2-az1--x-s3}, in the
   * order and with the continuation tokens of {@linkplain #listBuckets(ListBucketsOptions) ListBuckets}.
   *
   * @param continuationToken continues the listing after the last bucket of a previous page; {@code null} to start it.
   * @param maxDirectoryBuckets the max number of buckets of the page, between 0 and {@value #MAX_DIRECTORY_BUCKETS},
   *     where 0, like {@code null}, is the default of {@value #MAX_DIRECTORY_BUCKETS}.
   * @return a page of directory buckets.
   * @throws LocalS3InvalidArgumentException if {@code max-directory-buckets} is out of range, or the continuation
   *     token isn't one that a listing answered with.
   */
  default ListBucketsAns listDirectoryBuckets(String continuationToken, Integer maxDirectoryBuckets) {
    if (maxDirectoryBuckets != null && (maxDirectoryBuckets < 0 || maxDirectoryBuckets > MAX_DIRECTORY_BUCKETS)) {
      throw new LocalS3InvalidArgumentException("max-directory-buckets", String.valueOf(maxDirectoryBuckets),
          "Argument max-directory-buckets must be an integer between 0 and " + MAX_DIRECTORY_BUCKETS + ".");
    }
    int pageSize = maxDirectoryBuckets == null || maxDirectoryBuckets == 0 ? MAX_DIRECTORY_BUCKETS : maxDirectoryBuckets;
    return listBucketsPage(continuationToken, pageSize,
        bucket -> BucketAssertions.isDirectoryBucketName(bucket.getBucketName()));
  }

  /**
   * A page of the buckets that {@code filter} accepts, after the position of {@code continuationToken}, in the order
   * they were created, and buckets created in the same millisecond by name.
   */
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
        nextContinuationToken = ListBucketsPosition.of(page.get(page.size() - 1)).encode();
        break;
      }
      page.add(Bucket.fromBucketMetadata(bucket));
    }
    return new ListBucketsAns(page, nextContinuationToken);
  }

}
