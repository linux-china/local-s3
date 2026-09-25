package com.robothy.s3.jupiter.extensions;

import com.robothy.s3.jupiter.LocalS3;
import java.net.URI;
import org.junit.jupiter.api.extension.ExtensionContext;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;

/**
 * Injects an {@link S3AsyncClient} configured against the LocalS3 service that {@code @LocalS3} launched. The client
 * runs on Netty threads of its own, so it is closed when the context it was injected into ends.
 */
public class S3AsyncClientResolver extends AbstractLocalS3ParameterResolver {

  @Override
  protected String className() {
    return "software.amazon.awssdk.services.s3.S3AsyncClient";
  }

  @Override
  Object resolve(LocalS3Extension.Service service, ExtensionContext context) {
    return closeWith(context, (S3AsyncClient) resolve(service.port(), service.config()));
  }

  @Override
  protected Object resolve(int port, LocalS3 s3Config) {
    return S3AsyncClient.builder()
        .forcePathStyle(true)
        .endpointOverride(URI.create("http://localhost:" + port))
        .region(Region.of("local"))
        .credentialsProvider(credentialsProvider(s3Config))
        .build();
  }

}
