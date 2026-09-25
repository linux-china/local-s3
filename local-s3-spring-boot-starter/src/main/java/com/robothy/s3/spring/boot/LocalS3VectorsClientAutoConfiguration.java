package com.robothy.s3.spring.boot;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import software.amazon.awssdk.services.s3vectors.S3VectorsClient;
import software.amazon.awssdk.services.s3vectors.S3VectorsClientBuilder;

/**
 * The {@linkplain S3VectorsClient} that points at the embedded service, built with the {@code S3VectorsClientBuilder}
 * of {@linkplain LocalS3AutoConfiguration}.
 *
 * <p>Ordered after the S3 Vectors auto-configuration of Spring Cloud AWS, which defines an {@code s3VectorsClient}
 * whatever the application defines, from that builder: this one then backs off, rather than clash with its name. The
 * class is named, rather than referenced, since Spring Cloud AWS is optional.
 */
@AutoConfiguration(after = LocalS3AutoConfiguration.class,
    afterName = "io.awspring.cloud.autoconfigure.s3vectors.S3VectorClientAutoConfiguration")
@ConditionalOnClass(S3VectorsClient.class)
@ConditionalOnBean({LocalS3Lifecycle.class, S3VectorsClientBuilder.class})
public class LocalS3VectorsClientAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  S3VectorsClient s3VectorsClient(S3VectorsClientBuilder builder) {
    return builder.build();
  }

}
