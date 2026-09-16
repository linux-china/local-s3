package com.robothy.s3.core.model;

import java.util.Objects;

/**
 * The lifecycle configuration of a bucket, as a client put it.
 *
 * <p>LocalS3 stores the configuration and returns it, but never applies its rules: no object expires, no object is
 * transitioned to another storage class, and no incomplete multipart upload is aborted because of it.
 *
 * @param configuration the {@code LifecycleConfiguration} XML document, as it was put.
 * @param transitionDefaultMinimumObjectSize the {@code x-amz-transition-default-minimum-object-size} of the
 *     configuration, e.g. {@code all_storage_classes_128K}.
 */
public record BucketLifecycleConfiguration(String configuration, String transitionDefaultMinimumObjectSize) {

  /**
   * Validate the components.
   *
   * @throws NullPointerException if a component is {@code null}.
   */
  public BucketLifecycleConfiguration {
    Objects.requireNonNull(configuration, "configuration");
    Objects.requireNonNull(transitionDefaultMinimumObjectSize, "transitionDefaultMinimumObjectSize");
  }

}
