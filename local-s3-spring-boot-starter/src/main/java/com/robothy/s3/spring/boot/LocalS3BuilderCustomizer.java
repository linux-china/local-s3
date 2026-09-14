package com.robothy.s3.spring.boot;

import com.robothy.s3.rest.LocalS3Builder;

/**
 * Customizes the {@linkplain LocalS3Builder} of the auto-configured LocalS3 service after the {@code local-s3.*}
 * properties are applied, e.g. to set an event listener executor. Every bean of this type is applied, in their order.
 */
@FunctionalInterface
public interface LocalS3BuilderCustomizer {

  /**
   * Customize the builder.
   *
   * @param builder the builder, with the properties applied.
   */
  void customize(LocalS3Builder builder);

}
