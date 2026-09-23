package com.robothy.s3.testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The configuration that {@linkplain LocalS3Container} builds, asserted without a Docker daemon: the tests that run
 * the image are in {@code local-s3-standalone}.
 */
class LocalS3ContainerTest {

  @Test
  void exposesTheS3PortForDockerToAllocateAHostPortFor() {
    try (LocalS3Container container = new LocalS3Container("latest")) {
      assertEquals(List.of(LocalS3Container.S3_PORT), container.getExposedPorts(),
          "Docker allocates the host port while it creates the container, so two test classes that run at the"
              + " same time can't pick the same one.");
      assertTrue(container.getPortBindings().isEmpty(), "No host port is picked in advance.");
    }
  }

  @Test
  void refusesToGuessTheHostPortBeforeTheContainerHasStarted() {
    try (LocalS3Container container = new LocalS3Container("latest")) {
      IllegalStateException e = assertThrows(IllegalStateException.class, container::getPort);
      assertTrue(e.getMessage().contains("withHttpPort"), "The message says how to get a port known in advance.");
      assertThrows(IllegalStateException.class, container::getEndpoint);
    }
  }

  @Test
  void bindsTheHostPortThatWithHttpPortAsksFor() {
    try (LocalS3Container container = new LocalS3Container("latest").withHttpPort(8080)) {
      assertEquals(8080, container.getPort(), "A fixed port is known before the container starts.");
      assertEquals(List.of("8080:29090/tcp"), container.getPortBindings());
      assertTrue(container.getExposedPorts().isEmpty(),
          "The container port has one host binding, so getMappedPort() can't answer with another one.");
    }
  }

  @Test
  void bindsTheLastHostPortThatWasAskedFor() {
    try (LocalS3Container container = new LocalS3Container("latest").withHttpPort(8080).withHttpPort(8081)) {
      assertEquals(8081, container.getPort());
      assertEquals(List.of("8081:29090/tcp"), container.getPortBindings(), "The first binding is replaced.");
    }
  }

  @Test
  void withRandomHttpPortUndoesAFixedPort() {
    try (LocalS3Container container = new LocalS3Container("latest").withHttpPort(8080).withRandomHttpPort()) {
      assertTrue(container.getPortBindings().isEmpty());
      assertEquals(List.of(LocalS3Container.S3_PORT), container.getExposedPorts());
      assertThrows(IllegalStateException.class, container::getPort);
    }
  }

  @Test
  void rejectsAPortThatIsntATcpPort() {
    try (LocalS3Container container = new LocalS3Container("latest")) {
      assertThrows(IllegalArgumentException.class, () -> container.withHttpPort(0));
      assertThrows(IllegalArgumentException.class, () -> container.withHttpPort(65536));
    }
  }

  @Test
  void bindsTheDataPathAndSetsTheMode() {
    try (LocalS3Container container = new LocalS3Container("latest")
        .withDataPath("/data/local-s3")
        .withMode(LocalS3Container.Mode.IN_MEMORY)
        .withPersistencePolicy(LocalS3Container.PersistencePolicy.FAST)
        .withInMemoryMaxBytes("512m")) {
      assertTrue(container.getBinds().stream().anyMatch(bind -> bind.getPath().equals("/data/local-s3")
          && bind.getVolume().getPath().equals("/data")));
      Map<String, String> env = container.getEnvMap();
      assertEquals("IN_MEMORY", env.get("LOCAL_S3_MODE"));
      assertEquals("FAST", env.get("LOCAL_S3_PERSISTENCE_POLICY"));
      assertEquals("512m", env.get("LOCAL_S3_IN_MEMORY_MAX_BYTES"));
    }
  }

  @Test
  void bindsTheDataPathOfAPath(@TempDir Path directory) {
    try (LocalS3Container container = new LocalS3Container("latest").withDataPath(directory)) {
      assertTrue(container.getBinds().stream()
          .anyMatch(bind -> bind.getPath().equals(directory.toAbsolutePath().toString())
              && bind.getVolume().getPath().equals("/data")));
    }
  }

  @Test
  void configuresTheCredentialsAndTheBucketsOfTheService() {
    try (LocalS3Container container = new LocalS3Container("latest")
        .withCredentials("local-s3", "local-s3-secret")
        .withBuckets("one", "two")) {
      Map<String, String> env = container.getEnvMap();
      assertEquals("local-s3", env.get("AWS_ACCESS_KEY_ID"));
      assertEquals("local-s3-secret", env.get("AWS_SECRET_ACCESS_KEY"));
      assertEquals("one,two", env.get("AWS_BUCKETS"));
      assertEquals("local-s3", container.getAccessKey());
      assertEquals("local-s3-secret", container.getSecretKey());
    }
  }

  @Test
  void hasNoCredentialsUntilTheyAreConfigured() {
    try (LocalS3Container container = new LocalS3Container("latest")) {
      assertNull(container.getAccessKey());
      assertNull(container.getSecretKey());
      assertFalse(container.getEnvMap().containsKey("AWS_ACCESS_KEY_ID"));
    }
  }

  @Test
  void configuresTheIcebergCatalogTheVirtualHostsAndTheWebsite() {
    try (LocalS3Container container = new LocalS3Container("latest")
        .withIcebergCatalog(true)
        .withIcebergWarehouse("s3://warehouse/")
        .withVirtualHostDomains("s3", "s3.local")
        .withWebsite(false)
        .withWebsiteAllBuckets(true)) {
      Map<String, String> env = container.getEnvMap();
      assertEquals("true", env.get("LOCAL_S3_ICEBERG_CATALOG"));
      assertEquals("s3://warehouse/", env.get("LOCAL_S3_ICEBERG_WAREHOUSE"));
      assertEquals("s3,s3.local", env.get("LOCAL_S3_VIRTUAL_HOST_DOMAINS"));
      assertEquals("false", env.get("LOCAL_S3_WEBSITE"));
      assertEquals("true", env.get("LOCAL_S3_WEBSITE_ALL_BUCKETS"));
    }
  }

  @Test
  void configuresTheGeneratedCertificate() {
    try (LocalS3Container container = new LocalS3Container("latest").withSelfSignedTls()) {
      assertEquals("true", container.getEnvMap().get("LOCAL_S3_TLS_SELF_SIGNED"));
    }

    try (LocalS3Container container = new LocalS3Container("latest")
        .withSelfSignedTls("localhost", "127.0.0.1")
        .withTlsRequired(true)) {
      Map<String, String> env = container.getEnvMap();
      assertEquals("localhost,127.0.0.1", env.get("LOCAL_S3_TLS_SELF_SIGNED"));
      assertEquals("true", env.get("LOCAL_S3_TLS_REQUIRED"));
    }
  }

  @Test
  void answersAnHttpsEndpointForAServiceThatServesHttps() {
    try (LocalS3Container container = new LocalS3Container("latest").withHttpPort(8080)) {
      assertTrue(container.getEndpoint().startsWith("http://"), "Plain HTTP by default.");
      assertTrue(container.getEndpoint().endsWith(":8080"));
      assertEquals(container.getEndpoint(), container.getEndpointUri().toString());

      container.withSelfSignedTls();
      assertTrue(container.getEndpoint().startsWith("https://"));
    }
  }

  @Test
  void rejectsBlankConfiguration() {
    try (LocalS3Container container = new LocalS3Container("latest")) {
      assertThrows(IllegalArgumentException.class, () -> container.withCredentials(" ", "secret"));
      assertThrows(IllegalArgumentException.class, () -> container.withBuckets());
      assertThrows(IllegalArgumentException.class, () -> container.withBuckets("one", " "));
      assertThrows(IllegalArgumentException.class, () -> container.withIcebergWarehouse(""));
    }
  }

}
