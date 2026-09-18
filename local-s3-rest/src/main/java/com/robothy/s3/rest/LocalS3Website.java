package com.robothy.s3.rest;

import com.robothy.s3.datatypes.WebsiteConfiguration;
import java.util.Objects;

/**
 * The settings of the static website hosting that a {@linkplain LocalS3} serves beside its S3 API, on the same port:
 * a browser that opens {@code http://localhost:{port}/{bucket}/} gets the index document of the bucket rather than the
 * XML of {@code ListObjects}, and a missing key gets the error document of the bucket rather than an XML error.
 *
 * <p>Only a request that carries no credentials is served this way, so the requests of an S3 client, which are signed,
 * keep their S3 semantics. Which buckets answer such a request is what {@linkplain #allBuckets()} decides:
 *
 * <ul>
 *   <li>by default, only a <b>public</b> bucket does, i.e. one whose ACL grants {@code READ} to {@code AllUsers} or
 *       whose bucket policy allows {@code s3:GetObject} to every principal, see
 *       {@linkplain com.robothy.s3.core.util.BucketPublicAccess}. A private bucket is reached with a signed request,
 *       as before;</li>
 *   <li>with {@code allBuckets}, <b>every</b> bucket does, whether it was made public or not. It is meant for local
 *       development and tests, where publishing a bucket to check a page in a browser is busywork; it makes every
 *       object of the service readable without credentials, so it stays off by default.</li>
 * </ul>
 *
 * @param enabled whether unsigned requests are served as a static website at all. {@code false} leaves every request
 *     to the S3 API, i.e. LocalS3 behaves as it did before this feature.
 * @param allBuckets whether every bucket is served as a website, rather than the public ones alone. It also lifts the
 *     bucket's private status for reading objects without credentials.
 * @param indexDocument the index document of a bucket that has no {@code WebsiteConfiguration} of its own, e.g.
 *     {@code index.html}: the object that a request for a directory is answered with, if the bucket has one. A bucket
 *     that was configured with {@code PutBucketWebsite} uses the suffix of its own configuration instead.
 * @param errorDocument the error document of a bucket that has no {@code WebsiteConfiguration} of its own, e.g.
 *     {@code error.html}; {@code null}, the default, answers a generic error page.
 */
public record LocalS3Website(boolean enabled, boolean allBuckets, String indexDocument, String errorDocument) {

  /**
   * The index document that a bucket without a configuration of its own is probed for, {@code index.html}.
   */
  public static final String DEFAULT_INDEX_DOCUMENT = WebsiteConfiguration.DEFAULT_INDEX_DOCUMENT;

  /**
   * The settings of a service that serves its public buckets as static websites, which is the default.
   */
  private static final LocalS3Website DEFAULT =
      new LocalS3Website(true, false, DEFAULT_INDEX_DOCUMENT, null);

  /**
   * Validate the settings.
   *
   * @throws IllegalArgumentException if the index document is blank.
   */
  public LocalS3Website {
    Objects.requireNonNull(indexDocument, "indexDocument");
    if (indexDocument.isBlank()) {
      throw new IllegalArgumentException("The index document must not be blank, e.g. " + DEFAULT_INDEX_DOCUMENT + ".");
    }
    errorDocument = errorDocument == null || errorDocument.isBlank() ? null : errorDocument;
  }

  /**
   * The default settings: the public buckets are served as static websites, and their index document is
   * {@value #DEFAULT_INDEX_DOCUMENT} unless they were configured with one.
   *
   * @return the settings.
   */
  public static LocalS3Website defaults() {
    return DEFAULT;
  }

  /**
   * Settings that serve no static website: every request is answered by the S3 API.
   *
   * @return the settings.
   */
  public static LocalS3Website disabled() {
    return new LocalS3Website(false, false, DEFAULT_INDEX_DOCUMENT, null);
  }

  /**
   * These settings turned on or off, which keeps the documents and {@linkplain #allBuckets()} that were configured.
   *
   * @param enabled whether unsigned requests are served as a static website.
   * @return new settings.
   */
  public LocalS3Website withEnabled(boolean enabled) {
    return new LocalS3Website(enabled, allBuckets, indexDocument, errorDocument);
  }

  /**
   * These settings with {@linkplain #allBuckets()} set.
   *
   * @param allBuckets whether every bucket is served as a website, not the public ones alone.
   * @return new settings.
   */
  public LocalS3Website withAllBuckets(boolean allBuckets) {
    return new LocalS3Website(enabled, allBuckets, indexDocument, errorDocument);
  }

  /**
   * These settings with another default index document.
   *
   * @param indexDocument the index document, e.g. {@code index.html}.
   * @return new settings.
   */
  public LocalS3Website withIndexDocument(String indexDocument) {
    return new LocalS3Website(enabled, allBuckets, indexDocument, errorDocument);
  }

  /**
   * These settings with a default error document.
   *
   * @param errorDocument the error document, e.g. {@code error.html}; {@code null} for a generic error page.
   * @return new settings.
   */
  public LocalS3Website withErrorDocument(String errorDocument) {
    return new LocalS3Website(enabled, allBuckets, indexDocument, errorDocument);
  }

}
