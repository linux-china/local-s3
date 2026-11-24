package com.robothy.s3.core.model;

import java.util.Objects;

/**
 * The lifecycle configuration of a bucket, as a client put it.
 *
 * <p>Stored as it was put; see {@code docs/semantics.md#lifecycle-configuration} for how its rules apply.
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
