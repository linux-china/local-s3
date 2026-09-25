package com.robothy.s3.jupiter.extensions;

import com.robothy.s3.jupiter.LocalS3;
import java.net.URI;
import org.junit.jupiter.api.extension.ExtensionContext;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.transfer.s3.S3TransferManager;

/**
 * Injects an {@code S3TransferManager} configured against the LocalS3 service that {@code @LocalS3} launched, when the
 * test brings {@code software.amazon.awssdk:s3-transfer-manager}, which is an optional dependency: the parameter type
 * is matched by name, so this resolver is registered without it.
 *
 * <p>The transfer manager transfers through an {@link S3AsyncClient} of its own, with multipart uploads and downloads
 * enabled. It doesn't close a client it was given, so both are closed when the context it was injected into ends.
 */
public class S3TransferManagerResolver extends AbstractLocalS3ParameterResolver {

  @Override
  protected String className() {
    return "software.amazon.awssdk.transfer.s3.S3TransferManager";
  }

  @Override
  Object resolve(LocalS3Extension.Service service, ExtensionContext context) {
    return TransferManagers.create(service.port(), service.config(), context);
  }

  @Override
  protected Object resolve(int port, LocalS3 s3Config) {
    throw new UnsupportedOperationException("Resolved with the context, which closes the transfer manager.");
  }

  /**
   * Keeps the types of the transfer manager out of the resolver, so that loading the resolver, which
   * {@code @LocalS3} always registers, doesn't need them.
   */
  private static final class TransferManagers {

    static Object create(int port, LocalS3 s3Config, ExtensionContext context) {
      S3AsyncClient client = closeWith(context, S3AsyncClient.builder()
          .forcePathStyle(true)
          .endpointOverride(URI.create("http://localhost:" + port))
          .region(Region.of("local"))
          .credentialsProvider(credentialsProvider(s3Config))
          .multipartEnabled(true)
          .build());
      // Stored after its client, so that it is closed before it: the store closes its values in reverse order.
      return closeWith(context, S3TransferManager.builder().s3Client(client).build());
    }

  }

}
