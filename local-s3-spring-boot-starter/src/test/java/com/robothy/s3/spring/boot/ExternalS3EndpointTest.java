package com.robothy.s3.spring.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;

/**
 * Which configured S3 endpoints the clients of the starter back off from: those that aren't the embedded service. The
 * environments are mock ones, so that the variables of the shell that runs the tests, e.g. an {@code AWS_ENDPOINT_URL},
 * don't take part.
 */
class ExternalS3EndpointTest {

  private static Optional<ExternalS3Endpoint> find(String... properties) {
    MockEnvironment environment = new MockEnvironment();
    for (String property : properties) {
      int separator = property.indexOf('=');
      environment.setProperty(property.substring(0, separator), property.substring(separator + 1));
    }
    return ExternalS3Endpoint.find(environment);
  }

  private static void assertExternal(String property, String endpoint, String... properties) {
    assertEquals(Optional.of(new ExternalS3Endpoint(property, endpoint)), find(properties));
  }

  private static void assertTheService(String... properties) {
    assertEquals(Optional.empty(), find(properties));
  }

  @Test
  void noEndpointIsNoExternalOne() {
    assertTheService();
    assertTheService("spring.cloud.aws.s3.endpoint=");
  }

  @Test
  void anEndpointOfAnotherHostIsExternal() {
    assertExternal("spring.cloud.aws.s3.endpoint", "https://s3.eu-west-1.amazonaws.com",
        "spring.cloud.aws.s3.endpoint=https://s3.eu-west-1.amazonaws.com");
    assertExternal("spring.cloud.aws.endpoint", "http://minio.internal:9000",
        "spring.cloud.aws.endpoint=http://minio.internal:9000");
    assertExternal("aws.endpoint-url-s3", "https://s3.example.com", "aws.endpoint-url-s3=https://s3.example.com");
    assertExternal("aws.endpoint-url", "https://s3.example.com", "aws.endpoint-url=https://s3.example.com");
  }

  @Test
  void theEndpointPlaceholdersOfTheServiceAreTheService() {
    assertTheService("spring.cloud.aws.s3.endpoint=${local.s3.endpoint}");
    assertTheService("spring.cloud.aws.s3.endpoint=http://localhost:${local.s3.port}");
    // Through a placeholder of the application, which is resolved without resolving the one of the service.
    assertTheService("app.s3=${local.s3.endpoint}", "spring.cloud.aws.s3.endpoint=${app.s3}");
  }

  @Test
  void aPlaceholderOfTheApplicationIsResolved() {
    assertExternal("spring.cloud.aws.s3.endpoint", "https://s3.example.com",
        "app.s3=https://s3.example.com", "spring.cloud.aws.s3.endpoint=${app.s3}");
  }

  @Test
  void aHostOfTheServiceAtItsPortIsTheService() {
    assertTheService("spring.cloud.aws.s3.endpoint=http://localhost:29090");
    assertTheService("spring.cloud.aws.s3.endpoint=http://127.0.0.1:29090");
    assertTheService("spring.cloud.aws.s3.endpoint=http://[::1]:29090");
    assertTheService("spring.cloud.aws.s3.endpoint=localhost:29090");
    assertTheService("spring.cloud.aws.s3.endpoint=http://bucket.localhost:29090");
    assertTheService("local-s3.port=9090", "spring.cloud.aws.s3.endpoint=http://127.0.0.1:9090");
    assertTheService("local-s3.bind-host=10.0.0.5", "spring.cloud.aws.s3.endpoint=http://10.0.0.5:29090");
    assertTheService("local-s3.virtual-host-domains=s3.local.dev",
        "spring.cloud.aws.s3.endpoint=http://s3.local.dev:29090");
  }

  @Test
  void aHostOfTheServiceAtAnotherPortIsExternal() {
    // E.g. a MinIO beside it.
    assertExternal("spring.cloud.aws.s3.endpoint", "http://localhost:9000",
        "spring.cloud.aws.s3.endpoint=http://localhost:9000");
    assertExternal("spring.cloud.aws.s3.endpoint", "http://localhost",
        "spring.cloud.aws.s3.endpoint=http://localhost");
  }

  @Test
  void anyPortOfAHostOfTheServiceIsTheServiceOnARandomPort() {
    assertTheService("local-s3.port=0", "aws.endpoint-url=http://127.0.0.1:29090");
    assertTheService("local-s3.port=-1", "spring.cloud.aws.s3.endpoint=http://localhost:9000");
    assertExternal("aws.endpoint-url", "https://s3.example.com", "local-s3.port=0",
        "aws.endpoint-url=https://s3.example.com");
  }

  /**
   * The endpoint of the AWS SDK, which is usually set in a shell for the AWS CLI, e.g. to a standalone LocalS3, is the
   * service at any port of a local host; the one that Spring Cloud AWS is configured with is another service there.
   */
  @Test
  void aLocalEndpointOfTheAwsSdkIsTheServiceAtAnyPort() {
    assertTheService("local-s3.port=9090", "aws.endpoint-url=http://127.0.0.1:29090");
    assertTheService("local-s3.port=9090", "aws.endpoint-url-s3=http://localhost:4566");
    assertExternal("aws.endpoint-url", "https://s3.example.com", "local-s3.port=9090",
        "aws.endpoint-url=https://s3.example.com");
    assertExternal("spring.cloud.aws.s3.endpoint", "http://127.0.0.1:29090", "local-s3.port=9090",
        "spring.cloud.aws.s3.endpoint=http://127.0.0.1:29090");
  }

  @Test
  void theEndpointsOfSpringCloudAwsAreLookedAtFirst() {
    assertExternal("spring.cloud.aws.s3.endpoint", "https://first.example.com",
        "aws.endpoint-url=https://second.example.com", "spring.cloud.aws.s3.endpoint=https://first.example.com");
    // One that is the service doesn't hide another that isn't.
    assertExternal("aws.endpoint-url", "https://second.example.com",
        "aws.endpoint-url=https://second.example.com", "spring.cloud.aws.s3.endpoint=${local.s3.endpoint}");
  }

  /**
   * The variables of the AWS SDK bind to its properties, like Spring Boot binds any variable.
   */
  @Test
  void theEnvironmentVariablesOfTheAwsSdkAreFound() {
    assertExternal("aws.endpoint-url", "https://s3.example.com",
        environment(Map.of("AWS_ENDPOINT_URL", "https://s3.example.com")));
    assertExternal("aws.endpoint-url-s3", "https://s3.example.com",
        environment(Map.of("AWS_ENDPOINT_URL_S3", "https://s3.example.com")));
    assertExternal("spring.cloud.aws.s3.endpoint", "https://s3.example.com",
        environment(Map.of("SPRING_CLOUD_AWS_S3_ENDPOINT", "https://s3.example.com")));
  }

  @Test
  void theSystemPropertiesOfTheAwsSdkAreFound() {
    MockEnvironment environment = new MockEnvironment();
    environment.getPropertySources().addFirst(new MapPropertySource(
        StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
        Map.of("aws.endpointUrl", "https://s3.example.com")));
    assertEquals(Optional.of(new ExternalS3Endpoint("aws.endpoint-url", "https://s3.example.com")),
        ExternalS3Endpoint.find(environment));
  }

  @Test
  void theServiceIsNotStartedToResolveItsPlaceholders() {
    MockEnvironment environment = new MockEnvironment();
    environment.getPropertySources().addFirst(new PropertySource<>(LocalS3PropertySource.NAME) {
      @Override
      public Object getProperty(String name) {
        if (name.startsWith("local.s3.")) {
          throw new AssertionError("Read " + name + ", which starts the service.");
        }
        return null;
      }
    });
    environment.setProperty("app.s3", "${local.s3.endpoint}");
    environment.setProperty("spring.cloud.aws.s3.endpoint", "${app.s3}");
    assertTrue(ExternalS3Endpoint.find(environment).isEmpty());
  }

  private static Optional<ExternalS3Endpoint> environment(Map<String, Object> variables) {
    MockEnvironment environment = new MockEnvironment();
    environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables));
    return ExternalS3Endpoint.find(environment);
  }

  private static void assertExternal(String property, String endpoint, Optional<ExternalS3Endpoint> found) {
    assertEquals(Optional.of(new ExternalS3Endpoint(property, endpoint)), found);
  }

}
