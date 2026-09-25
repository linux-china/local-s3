package com.robothy.s3.testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.awspring.cloud.autoconfigure.core.AwsConnectionDetails;
import java.lang.reflect.Constructor;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.service.connection.ConnectionDetailsFactory;
import org.springframework.boot.docker.compose.core.ConnectionPorts;
import org.springframework.boot.docker.compose.core.ImageReference;
import org.springframework.boot.docker.compose.core.RunningService;
import org.springframework.boot.docker.compose.service.connection.DockerComposeConnectionSource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.support.SpringFactoriesLoader;


/**
 * The service connection of a LocalS3 service of Docker Compose, asserted on a {@linkplain RunningService} of its own,
 * without a Docker daemon.
 */
class LocalS3DockerComposeConnectionDetailsFactoryTest {

  private final LocalS3DockerComposeConnectionDetailsFactory factory = new LocalS3DockerComposeConnectionDetailsFactory();

  @Test
  void isRegisteredForSpringBootToFind() {
    assertTrue(SpringFactoriesLoader.loadFactories(ConnectionDetailsFactory.class, getClass().getClassLoader())
            .stream().anyMatch(LocalS3DockerComposeConnectionDetailsFactory.class::isInstance),
        "Spring Boot finds the connection details factories in META-INF/spring.factories.");
  }

  @Test
  void matchesTheImageOfLocalS3() throws Exception {
    assertNotNull(factory.getConnectionDetails(source("luofuxiang/local-s3:latest", Map.of(), Map.of())));
    assertNotNull(factory.getConnectionDetails(source("docker.io/luofuxiang/local-s3", Map.of(), Map.of())));
    assertNull(factory.getConnectionDetails(source("localstack/localstack", Map.of(), Map.of())));
  }

  @Test
  void matchesAnotherImageByItsLabel() throws Exception {
    Map<String, String> label = Map.of("org.springframework.boot.service-connection", "luofuxiang/local-s3");
    assertNotNull(factory.getConnectionDetails(source("mirror.example.com/s3/local-s3:1.0", Map.of(), label)));
    assertNotNull(factory.getConnectionDetails(source("mirror.example.com/s3/local-s3:1.0", Map.of(),
        Map.of("org.springframework.boot.service-connection", "local-s3"))));
  }

  @Test
  void pointsAtTheMappedPortWithTheDefaultKeyWhereTheServiceRequiresNone() throws Exception {
    AwsConnectionDetails details = factory.getConnectionDetails(source("luofuxiang/local-s3",
        Map.of("LOCAL_S3_CREDENTIALS_FROM_AWS_ENV", "true"), Map.of()));
    assertEquals(URI.create("http://127.0.0.1:32773"), details.getEndpoint());
    assertEquals("us-east-1", details.getRegion());
    assertEquals("local-s3", details.getAccessKey());
    assertEquals("local-s3", details.getSecretKey());
  }

  @Test
  void signsWithTheCredentialsOfTheService() throws Exception {
    AwsConnectionDetails fromAwsEnv = factory.getConnectionDetails(source("luofuxiang/local-s3",
        Map.of("LOCAL_S3_CREDENTIALS_FROM_AWS_ENV", "true", "AWS_ACCESS_KEY_ID", "aws-id",
            "AWS_SECRET_ACCESS_KEY", "aws-secret"), Map.of()));
    assertEquals("aws-id", fromAwsEnv.getAccessKey());
    assertEquals("aws-secret", fromAwsEnv.getSecretKey());

    AwsConnectionDetails localS3 = factory.getConnectionDetails(source("luofuxiang/local-s3",
        Map.of("LOCAL_S3_CREDENTIALS_FROM_AWS_ENV", "true", "AWS_ACCESS_KEY_ID", "aws-id",
            "AWS_SECRET_ACCESS_KEY", "aws-secret", "LOCAL_S3_ACCESS_KEY_ID", "id",
            "LOCAL_S3_SECRET_ACCESS_KEY", "secret"), Map.of()));
    assertEquals("id", localS3.getAccessKey());
    assertEquals("secret", localS3.getSecretKey());

    AwsConnectionDetails awsEnvNotUsed = factory.getConnectionDetails(source("luofuxiang/local-s3",
        Map.of("AWS_ACCESS_KEY_ID", "aws-id", "AWS_SECRET_ACCESS_KEY", "aws-secret"), Map.of()));
    assertEquals("local-s3", awsEnvNotUsed.getAccessKey());
  }

  @Test
  void followsThePortAndTlsOfTheService() throws Exception {
    AwsConnectionDetails details = factory.getConnectionDetails(source("luofuxiang/local-s3",
        Map.of("LOCAL_S3_PORT", "9000", "LOCAL_S3_TLS_SELF_SIGNED", "localhost,s3"), Map.of()));
    assertEquals(URI.create("https://127.0.0.1:39000"), details.getEndpoint());

    assertEquals("http", factory.getConnectionDetails(source("luofuxiang/local-s3",
        Map.of("LOCAL_S3_TLS_SELF_SIGNED", "false"), Map.of())).getEndpoint().getScheme());
  }

  private static DockerComposeConnectionSource source(String image, Map<String, String> env,
      Map<String, String> labels) throws Exception {
    Constructor<DockerComposeConnectionSource> constructor =
        DockerComposeConnectionSource.class.getDeclaredConstructor(RunningService.class, Environment.class);
    constructor.setAccessible(true);
    return constructor.newInstance(new Service(ImageReference.of(image), env, labels), new StandardEnvironment());
  }

  private record Service(ImageReference image, Map<String, String> env, Map<String, String> labels)
      implements RunningService {

    @Override
    public String name() {
      return "local-s3";
    }

    @Override
    public String host() {
      return "127.0.0.1";
    }

    @Override
    public ConnectionPorts ports() {
      return new ConnectionPorts() {

        @Override
        public int get(int containerPort) {
          // Docker maps 29090 to 32773, and 9000 to 39000.
          return containerPort == LocalS3Container.S3_PORT ? 32773 : 30000 + containerPort;
        }

        @Override
        public List<Integer> getAll() {
          return List.of();
        }

        @Override
        public List<Integer> getAll(String protocol) {
          return List.of();
        }

      };
    }

  }

}
