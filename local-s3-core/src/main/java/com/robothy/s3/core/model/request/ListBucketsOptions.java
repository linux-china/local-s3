package com.robothy.s3.core.model.request;

/**
 * The parameters of a <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_ListBuckets.html">ListBuckets</a>
 * request.
 *
 * @param prefix lists only the buckets whose names begin with it; {@code null} for all names.
 * @param bucketRegion lists only the buckets of this region; {@code null} for all regions.
 * @param continuationToken continues the listing after the last bucket of a previous page; {@code null} to start it.
 * @param maxBuckets the max number of buckets of the page, between 1 and 10000; {@code null} for the default.
 */
public record ListBucketsOptions(String prefix, String bucketRegion, String continuationToken, Integer maxBuckets) {

  private static final ListBucketsOptions NONE = new ListBucketsOptions(null, null, null, null);

  /**
   * The options of a request without parameters, which lists every bucket on a single page.
   *
   * @return the options.
   */
  public static ListBucketsOptions none() {
    return NONE;
  }

  /**
   * Whether the request is paginated, i.e. carries any of the parameters. Amazon S3 answers a request without them with
   * every bucket, and a request with any of them with pages of at most 10000 buckets.
   *
   * @return {@code true} if the request carries a parameter.
   */
  public boolean isPaginated() {
    return prefix != null || bucketRegion != null || continuationToken != null || maxBuckets != null;
  }

}
