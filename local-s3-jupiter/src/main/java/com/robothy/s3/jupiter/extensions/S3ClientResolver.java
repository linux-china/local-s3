package com.robothy.s3.jupiter.extensions;

import com.robothy.s3.jupiter.LocalS3;
import lombok.SneakyThrows;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;

public class S3ClientResolver extends AbstractLocalS3ParameterResolver {

  @Override
  protected String className() {
    return "software.amazon.awssdk.services.s3.S3Client";
  }

  @SneakyThrows
  @Override
  protected Object resolve(int port, LocalS3 s3Config) {
    String endpoint = "http://localhost:" + port;

    return S3Client.builder()
      .forcePathStyle(true)
      .endpointOverride(new URI(endpoint))
      .region(Region.of("local"))
      .credentialsProvider(credentialsProvider(s3Config))
      .build();
  }

}
