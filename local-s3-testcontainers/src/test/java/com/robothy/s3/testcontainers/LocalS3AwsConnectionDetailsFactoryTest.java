package com.robothy.s3.testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.service.connection.ConnectionDetailsFactory;
import org.springframework.core.io.support.SpringFactoriesLoader;

/**
 * The {@code @ServiceConnection} of {@linkplain LocalS3Container}, asserted without a Docker daemon: the test that runs
 * it against the clients of Spring Cloud AWS is {@code ServiceConnectionTest} in {@code local-s3-standalone}.
 */
class LocalS3AwsConnectionDetailsFactoryTest {

  @Test
  void isRegisteredForSpringBootToFind() {
    assertTrue(SpringFactoriesLoader.loadFactories(ConnectionDetailsFactory.class, getClass().getClassLoader())
            .stream().anyMatch(LocalS3AwsConnectionDetailsFactory.class::isInstance),
        "Spring Boot finds the connection details factories in META-INF/spring.factories.");
  }

  @Test
  void pointsAtAnIpAddressForTheAwsSdkToAddressBucketsByPath() {
    try (LocalS3Container container = new LocalS3Container("latest").withHttpPort(8080)) {
      URI endpoint = LocalS3AwsConnectionDetailsFactory.endpoint(container);
      assertEquals("http", endpoint.getScheme());
      assertEquals(8080, endpoint.getPort());
      assertTrue(endpoint.getHost().matches("[0-9.]+|\\[[0-9a-fA-F:]+]"),
          endpoint + " is an IP address, not the host name " + container.getHost() + ".");

      container.withSelfSignedTls();
      assertEquals("https", LocalS3AwsConnectionDetailsFactory.endpoint(container).getScheme());
    }
  }

}
