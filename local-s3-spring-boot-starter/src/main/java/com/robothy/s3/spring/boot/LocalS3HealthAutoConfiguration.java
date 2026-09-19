package com.robothy.s3.spring.boot;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.health.autoconfigure.contributor.ConditionalOnEnabledHealthIndicator;
import org.springframework.context.annotation.Bean;

/**
 * The Actuator health indicator of the embedded LocalS3 service on Spring Boot 4, named {@code localS3}; disable it
 * with {@code management.health.local-s3.enabled=false}.
 *
 * <p>An application on Spring Boot 3 gets {@linkplain LocalS3ActuatorHealthAutoConfiguration} instead: the condition
 * is on the name of the Health API of Spring Boot 4, rather than on the class itself, so that this class is skipped
 * without being loaded on Spring Boot 3, where {@code spring-boot-health} is absent. {@code
 * @ConditionalOnEnabledHealthIndicator} is a Spring Boot 4 annotation, and the JVM leaves out the annotations whose
 * type is missing, so it is the condition above that keeps this auto-configuration out of a Spring Boot 3 application.
 */
@AutoConfiguration(after = LocalS3AutoConfiguration.class)
@ConditionalOnClass(name = "org.springframework.boot.health.contributor.HealthIndicator")
@ConditionalOnBean(LocalS3Lifecycle.class)
@ConditionalOnEnabledHealthIndicator("local-s3")
public class LocalS3HealthAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean(name = "localS3HealthIndicator")
  public LocalS3HealthIndicator localS3HealthIndicator(LocalS3Lifecycle lifecycle) {
    return new LocalS3HealthIndicator(lifecycle);
  }

}
