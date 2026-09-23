package com.robothy.s3.jupiter.extensions;

import com.robothy.s3.jupiter.LocalS3;
import java.net.URI;
import lombok.SneakyThrows;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3tables.S3TablesClient;

/**
 * Injects an {@link S3TablesClient} configured against the LocalS3 service that {@code @LocalS3} launched, so that a
 * test of the S3 Tables API takes one as a parameter and nothing else.
 *
 * <p>The endpoint depends on what the client carries, because that is what the service tells a request of this API
 * from an Amazon S3 one by: the two APIs share their paths. A client with credentials signs for the {@code s3tables}
 * service, which is unambiguous, so it is pointed straight at the service; a client without them signs nothing, so it
 * is pointed at the path the API also answers under. See
 * {@code com.robothy.s3.rest.handler.s3tables.S3TablesController}.
 */
public class LocalS3TablesClientResolver extends AbstractLocalS3ParameterResolver {

  /**
   * The path that an unsigned client reaches the S3 Tables API under, which is
   * {@code S3TablesController.PATH_PREFIX}.
   */
  private static final String PATH_PREFIX = "/s3tables";

  /**
   * The region an injected client signs for. It is a real region name rather than the {@code local} of the other
   * clients: the S3 Tables client resolves its endpoint through the region even when the endpoint is overridden.
   */
  private static final Region REGION = Region.US_EAST_1;

  @Override
  protected String className() {
    return "software.amazon.awssdk.services.s3tables.S3TablesClient";
  }

  @SneakyThrows
  @Override
  protected Object resolve(int port, LocalS3 s3Config) {
    boolean signed = s3Config != null && LocalS3Extension.verifiesSignatures(s3Config);
    String endpoint = "http://localhost:" + port + (signed ? "" : PATH_PREFIX);
    return S3TablesClient.builder()
        .endpointOverride(new URI(endpoint))
        .region(REGION)
        .credentialsProvider(credentialsProvider(s3Config))
        .build();
  }

}
