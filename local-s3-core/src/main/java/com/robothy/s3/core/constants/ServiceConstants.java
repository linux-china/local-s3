package com.robothy.s3.core.constants;

public class ServiceConstants {

  public static final String JSON_SUFFIX = ".json";

  /**
   * The region of a bucket that was created without a location constraint, like Amazon S3 does.
   */
  public static final String DEFAULT_REGION = "us-east-1";

  /**
   * The default region of the buckets that earlier versions of LocalS3 saved, which isn't a valid region.
   */
  public static final String LEGACY_DEFAULT_REGION = "local";

  /**
   * Get the region that a bucket saved with the given region is in.
   *
   * @param region the saved region of a bucket; {@code null} if it has none.
   * @return {@linkplain #DEFAULT_REGION} if the bucket has no region, or it has the legacy default one; otherwise the
   *     region itself.
   */
  public static String effectiveRegion(String region) {
    if (region == null || region.isBlank() || LEGACY_DEFAULT_REGION.equals(region)) {
      return DEFAULT_REGION;
    }
    return region;
  }

}
