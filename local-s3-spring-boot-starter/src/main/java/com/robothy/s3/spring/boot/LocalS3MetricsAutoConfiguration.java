package com.robothy.s3.spring.boot;

import com.robothy.s3.rest.LocalS3;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * The Micrometer metrics of the embedded LocalS3 service. Spring Boot binds the {@linkplain LocalS3Metrics} to the meter
 * registries of the application, and {@linkplain LocalS3AutoConfiguration} records the requests of the service to it.
 */
@AutoConfiguration(before = LocalS3AutoConfiguration.class)
@ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
@ConditionalOnProperty(name = "local-s3.enabled", havingValue = "true", matchIfMissing = true)
public class LocalS3MetricsAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public LocalS3Metrics localS3Metrics(ObjectProvider<LocalS3> localS3) {
    // Looked up when recorded: the service is created with the metrics as its request recorder.
    return new LocalS3Metrics(localS3::getIfAvailable);
  }

}
