package com.robothy.s3.spring.boot;

import org.springframework.boot.actuate.autoconfigure.endpoint.condition.ConditionalOnAvailableEndpoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * The Actuator endpoint {@code locals3} of the embedded LocalS3 service, see {@linkplain LocalS3Endpoint}; expose it
 * over HTTP with {@code management.endpoints.web.exposure.include=health,locals3}, and turn it off with
 * {@code management.endpoint.locals3.access=none} ({@code management.endpoint.locals3.enabled=false} before Spring Boot
 * 3.4).
 *
 * <p>The endpoint API and {@code @ConditionalOnAvailableEndpoint} are in the same packages in Spring Boot 3 and Spring
 * Boot 4, so one auto-configuration serves both. The condition is on the name of the endpoint API, so that this class is
 * skipped without being loaded in an application without Actuator.
 */
@AutoConfiguration(after = LocalS3AutoConfiguration.class)
@ConditionalOnClass(name = {"org.springframework.boot.actuate.endpoint.annotation.Endpoint",
    "org.springframework.boot.actuate.autoconfigure.endpoint.condition.ConditionalOnAvailableEndpoint"})
@ConditionalOnBean(LocalS3Lifecycle.class)
public class LocalS3EndpointAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnAvailableEndpoint(LocalS3Endpoint.class)
  public LocalS3Endpoint localS3Endpoint(LocalS3Lifecycle lifecycle) {
    return new LocalS3Endpoint(lifecycle);
  }

}
