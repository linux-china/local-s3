package com.robothy.s3.jupiter.extensions;

import com.robothy.s3.jupiter.LocalS3;
import java.net.URI;
import org.junit.jupiter.api.extension.ExtensionContext;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Injects an {@link S3Presigner} configured against the LocalS3 service that {@code @LocalS3} launched, with
 * path-style URLs, so that a presigned URL reaches the service without a virtual host. It is closed when the context
 * it was injected into ends.
 *
 * <p>A presigner can't sign anonymously, so against a service that verifies no signature it signs with placeholder
 * credentials, which that service accepts like any others.
 */
public class S3PresignerResolver extends AbstractLocalS3ParameterResolver {

  private static final String PLACEHOLDER_KEY = "local-s3";

  @Override
  protected String className() {
    return "software.amazon.awssdk.services.s3.presigner.S3Presigner";
  }

  @Override
  Object resolve(LocalS3Extension.Service service, ExtensionContext context) {
    return closeWith(context, (S3Presigner) resolve(service.port(), service.config()));
  }

  @Override
  protected Object resolve(int port, LocalS3 s3Config) {
    return S3Presigner.builder()
        .endpointOverride(URI.create("http://localhost:" + port))
        .region(Region.of("local"))
        .credentialsProvider(s3Config != null && LocalS3Extension.verifiesSignatures(s3Config)
            ? credentialsProvider(s3Config)
            : StaticCredentialsProvider.create(AwsBasicCredentials.create(PLACEHOLDER_KEY, PLACEHOLDER_KEY)))
        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
        .build();
  }

}
