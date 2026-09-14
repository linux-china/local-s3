package com.robothy.s3.spring.boot;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.health.autoconfigure.contributor.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;

/**
 * The Actuator health indicator of the embedded LocalS3 service, named {@code localS3}; disable it with
 * {@code management.health.local-s3.enabled=false}.
 */
@AutoConfiguration(after = LocalS3AutoConfiguration.class)
@ConditionalOnClass(HealthIndicator.class)
@ConditionalOnBean(LocalS3Lifecycle.class)
@ConditionalOnEnabledHealthIndicator("local-s3")
public class LocalS3HealthAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean(name = "localS3HealthIndicator")
  public LocalS3HealthIndicator localS3HealthIndicator(LocalS3Lifecycle lifecycle) {
    return new LocalS3HealthIndicator(lifecycle);
  }

}
