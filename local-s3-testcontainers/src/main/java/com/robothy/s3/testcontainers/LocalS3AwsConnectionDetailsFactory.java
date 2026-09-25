package com.robothy.s3.testcontainers;

import io.awspring.cloud.autoconfigure.core.AwsConnectionDetails;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import org.springframework.boot.testcontainers.service.connection.ContainerConnectionDetailsFactory;
import org.springframework.boot.testcontainers.service.connection.ContainerConnectionSource;

/**
 * The {@code @ServiceConnection} of a {@linkplain LocalS3Container}: the {@linkplain AwsConnectionDetails} of Spring
 * Cloud AWS, which points the clients it auto-configures, e.g. {@code S3Client}, {@code S3Template} and
 * {@code S3Presigner}, at the container, as the {@code LocalStackContainer} of Spring Cloud AWS does.
 *
 * <pre>{@code
 * @SpringBootTest
 * @Testcontainers
 * class MyTest {
 *
 *   @Container
 *   @ServiceConnection
 *   static LocalS3Container localS3 = new LocalS3Container("latest");
 *
 * }
 * }</pre>
 *
 * <p>It is registered in {@code META-INF/spring.factories}, and takes effect where the application has
 * {@code spring-boot-testcontainers} and {@code spring-cloud-aws-autoconfigure}, i.e. a Spring Cloud AWS starter;
 * Spring Boot skips it otherwise.
 */
public class LocalS3AwsConnectionDetailsFactory
    extends ContainerConnectionDetailsFactory<LocalS3Container, AwsConnectionDetails> {

  /**
   * The region that the clients sign for: the service accepts any, and the AWS SDK needs one.
   */
  static final String DEFAULT_REGION = "us-east-1";

  /**
   * The key that the clients sign with when the container doesn't require credentials: the service accepts any
   * signature then, and without credentials the AWS SDK would look for those of the machine, and fail where there
   * are none. The same one as the clients of the Spring Boot starter of LocalS3.
   */
  static final String DEFAULT_CLIENT_KEY = "local-s3";

  /**
   * Construct the factory, which Spring Boot does; it matches any connection name, and requires Spring Cloud AWS.
   */
  public LocalS3AwsConnectionDetailsFactory() {
    super(ANY_CONNECTION_NAME, "io.awspring.cloud.autoconfigure.core.AwsConnectionDetails");
  }

  @Override
  protected AwsConnectionDetails getContainerConnectionDetails(ContainerConnectionSource<LocalS3Container> source) {
    return new LocalS3AwsConnectionDetails(source);
  }

  /**
   * The endpoint of the container with its host resolved to an IP address, e.g. {@code http://127.0.0.1:32773} for
   * {@code http://localhost:32773}. The AWS SDK addresses a bucket by path on an endpoint that is an IP address, and by
   * host name, i.e. {@code my-bucket.localhost}, on any other, which the service doesn't serve unless configured with
   * virtual host domains. Spring Cloud AWS only uses path-style requests where a property says so, so the endpoint
   * alone makes the clients work out of the box.
   *
   * @param container the running container.
   * @return the endpoint URI.
   */
  static URI endpoint(LocalS3Container container) {
    URI endpoint = container.getEndpointUri();
    String host;
    try {
      InetAddress address = InetAddress.getByName(endpoint.getHost());
      host = address instanceof Inet6Address ? "[" + address.getHostAddress() + "]" : address.getHostAddress();
    } catch (UnknownHostException e) {
      // A host the JVM can't resolve can't be reached either; the client reports that on its first request.
      return endpoint;
    }
    return URI.create(endpoint.getScheme() + "://" + host + ":" + endpoint.getPort());
  }

  static final class LocalS3AwsConnectionDetails extends ContainerConnectionDetails<LocalS3Container>
      implements AwsConnectionDetails {

    private LocalS3AwsConnectionDetails(ContainerConnectionSource<LocalS3Container> source) {
      super(source);
    }

    @Override
    public URI getEndpoint() {
      return endpoint(getContainer());
    }

    @Override
    public String getRegion() {
      return DEFAULT_REGION;
    }

    @Override
    public String getAccessKey() {
      String accessKey = getContainer().getAccessKey();
      return accessKey == null ? DEFAULT_CLIENT_KEY : accessKey;
    }

    @Override
    public String getSecretKey() {
      String secretKey = getContainer().getSecretKey();
      return secretKey == null ? DEFAULT_CLIENT_KEY : secretKey;
    }

  }

}
