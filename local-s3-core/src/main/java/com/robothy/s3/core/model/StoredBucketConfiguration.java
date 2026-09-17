package com.robothy.s3.core.model;

import com.robothy.s3.core.exception.S3ErrorCode;
import java.util.Optional;

/**
 * The configurations of a bucket that LocalS3 <b>stores and returns but never applies</b>: nothing is accelerated,
 * logged, billed to the requester or hosted as a website, and ACLs aren't disabled by the ownership controls.
 *
 * <p>They exist so that the clients that read or write each of them, e.g. Terraform refreshing an
 * {@code aws_s3_bucket}, which reads every one of them, or CDK and SDK tool chains setting the ownership controls,
 * work against LocalS3 instead of failing with {@code 501 NotImplemented}.
 *
 * <p>A bucket that was never configured answers like Amazon S3 answers for a new bucket: an empty configuration, a
 * default one, or a {@code 404} error when Amazon S3 has none either.
 */
public enum StoredBucketConfiguration {

  /**
   * <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_GetBucketAccelerateConfiguration.html">GetBucketAccelerateConfiguration</a>:
   * a bucket that was never configured has an empty configuration, i.e. transfer acceleration is off.
   */
  ACCELERATE("AccelerateConfiguration",
      "<AccelerateConfiguration xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"/>", null),

  /**
   * <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_GetBucketLogging.html">GetBucketLogging</a>: a bucket
   * that was never configured has an empty logging status, i.e. access logging is off. Its namespace is not the one of
   * the other configurations, on Amazon S3 as well.
   */
  LOGGING("BucketLoggingStatus",
      "<BucketLoggingStatus xmlns=\"http://doc.s3.amazonaws.com/2006-03-01\"/>", null),

  /**
   * <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_GetBucketRequestPayment.html">GetBucketRequestPayment</a>:
   * the bucket owner pays for the requests unless requester pays was put.
   */
  REQUEST_PAYMENT("RequestPaymentConfiguration",
      "<RequestPaymentConfiguration xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"
          + "<Payer>BucketOwner</Payer></RequestPaymentConfiguration>", null),

  /**
   * <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_GetBucketWebsite.html">GetBucketWebsite</a>: a bucket
   * that isn't configured as a website answers {@code NoSuchWebsiteConfiguration}, which clients, e.g. Terraform, read
   * as "not a website". An empty configuration would be read as a website without an index document instead.
   */
  WEBSITE("WebsiteConfiguration", null, S3ErrorCode.NoSuchWebsiteConfiguration),

  /**
   * <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_GetBucketOwnershipControls.html">GetBucketOwnershipControls</a>:
   * a new bucket enforces the ownership of its owner, which is the default of Amazon S3 since April 2023. Once the
   * controls are deleted, the bucket answers {@code OwnershipControlsNotFoundError}, which is what clients, e.g.
   * Terraform destroying an {@code aws_s3_bucket_ownership_controls}, wait for.
   */
  OWNERSHIP_CONTROLS("OwnershipControls",
      "<OwnershipControls xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><Rule>"
          + "<ObjectOwnership>BucketOwnerEnforced</ObjectOwnership></Rule></OwnershipControls>",
      S3ErrorCode.OwnershipControlsNotFoundError);

  private final String rootElement;

  private final String defaultConfiguration;

  private final S3ErrorCode notFoundError;

  StoredBucketConfiguration(String rootElement, String defaultConfiguration, S3ErrorCode notFoundError) {
    this.rootElement = rootElement;
    this.defaultConfiguration = defaultConfiguration;
    this.notFoundError = notFoundError;
  }

  /**
   * The local name of the root element of the configuration document.
   */
  public String rootElement() {
    return rootElement;
  }

  /**
   * The configuration of a bucket that was never configured.
   *
   * @return the default configuration; empty if the bucket has none, see {@linkplain #notFoundError()}.
   */
  public Optional<String> defaultConfiguration() {
    return Optional.ofNullable(defaultConfiguration);
  }

  /**
   * The error answered to a bucket that has no configuration: one that was never configured and has no default, or
   * one whose configuration was deleted.
   *
   * @return the error; {@code null} if a bucket always has a configuration.
   */
  public S3ErrorCode notFoundError() {
    return notFoundError;
  }

}
