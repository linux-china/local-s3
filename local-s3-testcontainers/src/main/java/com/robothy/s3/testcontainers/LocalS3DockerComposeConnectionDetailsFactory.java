package com.robothy.s3.testcontainers;

import io.awspring.cloud.autoconfigure.core.AwsConnectionDetails;
import java.net.URI;
import java.util.Map;
import org.springframework.boot.docker.compose.core.RunningService;
import org.springframework.boot.docker.compose.service.connection.DockerComposeConnectionDetailsFactory;
import org.springframework.boot.docker.compose.service.connection.DockerComposeConnectionSource;

/**
 * The service connection of a LocalS3 service of Docker Compose: the {@linkplain AwsConnectionDetails} of Spring Cloud
 * AWS, which points the clients it auto-configures, e.g. {@code S3Client}, {@code S3Template} and
 * {@code S3Presigner}, at the service that {@code spring-boot-docker-compose} starts from the {@code compose.yaml} of
 * the application.
 *
 * <pre>{@code
 * services:
 *   local-s3:
 *     image: luofuxiang/local-s3
 *     ports:
 *       - "29090"
 * }</pre>
 *
 * <p>It matches a service of the image {@value LocalS3Container#IMAGE_NAME}, and a service of any other image, e.g. of
 * a mirror, that has the label {@code org.springframework.boot.service-connection: luofuxiang/local-s3}. The endpoint,
 * credentials and TLS come from the environment of the service, as the service reads them: {@code LOCAL_S3_PORT},
 * {@code LOCAL_S3_ACCESS_KEY_ID} and {@code LOCAL_S3_SECRET_ACCESS_KEY}, or {@code AWS_ACCESS_KEY_ID} and
 * {@code AWS_SECRET_ACCESS_KEY} where {@code LOCAL_S3_CREDENTIALS_FROM_AWS_ENV} is {@code true}, as the image sets it,
 * and {@code LOCAL_S3_TLS_CERT} or {@code LOCAL_S3_TLS_SELF_SIGNED}.
 *
 * <p>It is registered in {@code META-INF/spring.factories}, and takes effect where the application has
 * {@code spring-boot-docker-compose} and {@code spring-cloud-aws-autoconfigure}, i.e. a Spring Cloud AWS starter;
 * Spring Boot skips it otherwise.
 */
public class LocalS3DockerComposeConnectionDetailsFactory
    extends DockerComposeConnectionDetailsFactory<AwsConnectionDetails> {

  /**
   * The names that a service is matched by: the image, and {@code local-s3} for the label, the short name of it.
   */
  private static final String[] CONNECTION_NAMES = {LocalS3Container.IMAGE_NAME, "local-s3"};

  /**
   * Construct the factory, which Spring Boot does; it requires Spring Cloud AWS.
   */
  public LocalS3DockerComposeConnectionDetailsFactory() {
    super(CONNECTION_NAMES, "io.awspring.cloud.autoconfigure.core.AwsConnectionDetails");
  }

  @Override
  protected AwsConnectionDetails getDockerComposeConnectionDetails(DockerComposeConnectionSource source) {
    return new LocalS3DockerComposeConnectionDetails(source.getRunningService());
  }

  /**
   * The port that the service listens on in its container: {@code LOCAL_S3_PORT}, or {@value LocalS3Container#S3_PORT}.
   */
  static int containerPort(Map<String, String> env) {
    String port = variable(env, "LOCAL_S3_PORT");
    return port == null ? LocalS3Container.S3_PORT : Integer.parseInt(port);
  }

  /**
   * Whether the service serves HTTPS: with a certificate of {@code LOCAL_S3_TLS_CERT}, or one it generates for
   * {@code LOCAL_S3_TLS_SELF_SIGNED}, which {@code false} turns off.
   */
  static boolean tls(Map<String, String> env) {
    String selfSigned = variable(env, "LOCAL_S3_TLS_SELF_SIGNED");
    return variable(env, "LOCAL_S3_TLS_CERT") != null
        || (selfSigned != null && !"false".equalsIgnoreCase(selfSigned));
  }

  /**
   * The access key ID and secret access key that the service requires, as it reads them from its environment; the
   * {@linkplain LocalS3AwsConnectionDetailsFactory#DEFAULT_CLIENT_KEY default key} for both where it requires none.
   */
  static String[] credentials(Map<String, String> env) {
    String accessKey = variable(env, "LOCAL_S3_ACCESS_KEY_ID");
    String secretKey = variable(env, "LOCAL_S3_SECRET_ACCESS_KEY");
    if (accessKey == null && secretKey == null
        && Boolean.parseBoolean(variable(env, "LOCAL_S3_CREDENTIALS_FROM_AWS_ENV"))) {
      accessKey = variable(env, "AWS_ACCESS_KEY_ID");
      secretKey = variable(env, "AWS_SECRET_ACCESS_KEY");
    }
    if (accessKey == null || secretKey == null) {
      return new String[] {LocalS3AwsConnectionDetailsFactory.DEFAULT_CLIENT_KEY,
          LocalS3AwsConnectionDetailsFactory.DEFAULT_CLIENT_KEY};
    }
    return new String[] {accessKey, secretKey};
  }

  private static String variable(Map<String, String> env, String name) {
    String value = env.get(name);
    return value == null || value.isBlank() ? null : value.trim();
  }

  static final class LocalS3DockerComposeConnectionDetails extends DockerComposeConnectionDetails
      implements AwsConnectionDetails {

    private final URI endpoint;

    private final String accessKey;

    private final String secretKey;

    private LocalS3DockerComposeConnectionDetails(RunningService service) {
      super(service);
      Map<String, String> env = service.env();
      this.endpoint = LocalS3AwsConnectionDetailsFactory.endpoint(tls(env) ? "https" : "http", service.host(),
          service.ports().get(containerPort(env)));
      String[] credentials = credentials(env);
      this.accessKey = credentials[0];
      this.secretKey = credentials[1];
    }

    @Override
    public URI getEndpoint() {
      return endpoint;
    }

    @Override
    public String getRegion() {
      return LocalS3AwsConnectionDetailsFactory.DEFAULT_REGION;
    }

    @Override
    public String getAccessKey() {
      return accessKey;
    }

    @Override
    public String getSecretKey() {
      return secretKey;
    }

  }

}
