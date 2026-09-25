package com.robothy.s3.core.model;

/**
 * The configurations of which a bucket has several, each named by an ID, that LocalS3 <b>stores and returns but never
 * applies</b>: no storage class analysis runs, no inventory report is written and no CloudWatch metric is published.
 *
 * <p>They exist so that the clients that write them, e.g. Terraform's {@code aws_s3_bucket_metric},
 * {@code aws_s3_bucket_inventory} and {@code aws_s3_bucket_analytics_configuration} or CDK stacks, work against LocalS3
 * instead of failing with {@code 501 NotImplemented}.
 *
 * <p>A bucket has none of them until one is put; reading or deleting an ID that the bucket doesn't have answers
 * {@code 404 NoSuchConfiguration}, like Amazon S3 does.
 */
public enum IdentifiedBucketConfiguration {

  /**
   * <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_PutBucketAnalyticsConfiguration.html">PutBucketAnalyticsConfiguration</a>
   * and the others of the {@code ?analytics} subresource.
   */
  ANALYTICS("AnalyticsConfiguration", "ListBucketAnalyticsConfigurationResult"),

  /**
   * <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_PutBucketInventoryConfiguration.html">PutBucketInventoryConfiguration</a>
   * and the others of the {@code ?inventory} subresource.
   */
  INVENTORY("InventoryConfiguration", "ListInventoryConfigurationsResult"),

  /**
   * <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_PutBucketMetricsConfiguration.html">PutBucketMetricsConfiguration</a>
   * and the others of the {@code ?metrics} subresource.
   */
  METRICS("MetricsConfiguration", "ListMetricsConfigurationsResult");

  private final String rootElement;

  private final String listRootElement;

  IdentifiedBucketConfiguration(String rootElement, String listRootElement) {
    this.rootElement = rootElement;
    this.listRootElement = listRootElement;
  }

  /**
   * The local name of the root element of a configuration document, which is also the name of each configuration in
   * the list of them.
   */
  public String rootElement() {
    return rootElement;
  }

  /**
   * The local name of the root element of the list of the configurations of a bucket.
   */
  public String listRootElement() {
    return listRootElement;
  }

}
