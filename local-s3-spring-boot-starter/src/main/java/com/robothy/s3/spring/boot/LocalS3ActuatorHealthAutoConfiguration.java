package com.robothy.s3.spring.boot;

import org.springframework.boot.actuate.autoconfigure.health.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * The Actuator health indicator of the embedded LocalS3 service on Spring Boot 3, named {@code localS3}; disable it
 * with {@code management.health.local-s3.enabled=false}.
 *
 * <p>An application on Spring Boot 4 gets {@linkplain LocalS3HealthAutoConfiguration} instead: the condition is on the
 * name of the Actuator Health API of Spring Boot 3, rather than on the class itself, so that this class is skipped
 * without being loaded on Spring Boot 4, where {@code spring-boot-actuator} is absent. Both auto-configurations define
 * the bean {@code localS3HealthIndicator}, and only one of them ever matches.
 */
@AutoConfiguration(after = LocalS3AutoConfiguration.class)
@ConditionalOnClass(name = "org.springframework.boot.actuate.health.HealthIndicator")
@ConditionalOnBean(LocalS3Lifecycle.class)
@ConditionalOnEnabledHealthIndicator("local-s3")
public class LocalS3ActuatorHealthAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean(name = "localS3HealthIndicator")
  public LocalS3ActuatorHealthIndicator localS3HealthIndicator(LocalS3Lifecycle lifecycle) {
    return new LocalS3ActuatorHealthIndicator(lifecycle);
  }

}
