package com.robothy.s3.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.rest.bootstrap.LocalS3Mode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LocalS3ConfigTest {

  @Test
  void theBuilderBuildsTheDefaultsOfAnEmbeddedService() {
    LocalS3Config config = LocalS3.builder().buildConfig();

    assertEquals("127.0.0.1", config.bindHost());
    assertEquals(29090, config.port());
    assertNull(config.dataPath());
    assertEquals(LocalS3Mode.IN_MEMORY, config.mode());
    assertEquals(List.of(), config.buckets());
    assertTrue(config.virtualThreads());
    assertTrue(config.daemonThreads());
    assertTrue(config.registerShutdownHook());
    assertTrue(config.compositeMultipartEtags());
    assertFalse(config.authenticationEnabled());
    assertEquals(LocalS3Config.DEFAULT_MAX_REQUEST_BODY_SIZE, config.maxRequestBodySize());
    assertEquals(LocalS3Config.DEFAULT_REQUEST_BODY_FILE_THRESHOLD, config.requestBodyFileThreshold());
    assertEquals(LocalS3Config.DEFAULT_MAX_REQUEST_HEADER_SIZE, config.maxRequestHeaderSize());
    assertEquals(LocalS3Config.DEFAULT_IDLE_CONNECTION_TIMEOUT_SECONDS, config.idleConnectionTimeoutSeconds());
  }

  @Test
  void aConfigDoesNotChangeWithItsBuilder() {
    LocalS3Builder builder = LocalS3.builder().port(-1).buckets("first").virtualHostDomains("s3");
    LocalS3Config config = builder.buildConfig();
    builder.buckets("second").virtualHostDomains("s3.local").bindHost("0.0.0.0");

    assertEquals(0, config.port(), "A random port is configured as port 0.");
    assertEquals(List.of("first"), config.buckets());
    assertEquals(List.of("s3"), config.virtualHostDomains());
    assertEquals("127.0.0.1", config.bindHost());
    assertThrows(UnsupportedOperationException.class, () -> config.buckets().add("third"));
  }

  @Test
  void aServiceIsCreatedFromItsConfig() {
    LocalS3Config config = LocalS3.builder().port(-1).mode(LocalS3Mode.PERSISTENCE).dataPath("/tmp/local-s3")
        .strictBucketNames(true).buildConfig();
    LocalS3 localS3 = LocalS3.create(config);

    assertSame(config, localS3.getConfig());
    assertEquals(LocalS3Mode.PERSISTENCE, localS3.getMode());
    assertEquals(Path.of("/tmp/local-s3"), localS3.getDataPath());
    assertTrue(localS3.isStrictBucketNames());
    assertEquals(config, LocalS3.builder().port(-1).mode(LocalS3Mode.PERSISTENCE).dataPath("/tmp/local-s3")
        .strictBucketNames(true).buildConfig(), "Configs with the same values are equal.");
  }

  @Test
  void aConfigIsValidatedWhenItIsCreatedDirectly() {
    LocalS3Config valid = LocalS3.builder().buildConfig();

    assertThrows(IllegalArgumentException.class, () -> withPort(valid, 65536));
    assertThrows(IllegalArgumentException.class, () -> new LocalS3Config(valid.bindHost(), valid.port(),
        valid.dataPath(), valid.mode(), valid.buckets(), null, null, valid.eventListenerExecutor(),
        valid.initialDataCacheEnabled(), valid.daemonThreads(), valid.registerShutdownHook(),
        valid.nettyParentEventGroupThreadNum(), valid.nettyChildEventGroupThreadNum(), valid.s3ExecutorThreadNum(),
        valid.virtualThreads(), "access-key-id", null, valid.maxRequestBodySize(), valid.requestBodyFileThreshold(),
        valid.maxRequestHeaderSize(), valid.idleConnectionTimeoutSeconds(), valid.strictBucketNames(),
        valid.strictPartSizes(), valid.compositeMultipartEtags(), valid.virtualHostDomains()));

    List<String> buckets = new ArrayList<>(List.of("a"));
    LocalS3Config copied = new LocalS3Config(valid.bindHost(), valid.port(), valid.dataPath(), valid.mode(), buckets,
        null, null, valid.eventListenerExecutor(), valid.initialDataCacheEnabled(), valid.daemonThreads(),
        valid.registerShutdownHook(), valid.nettyParentEventGroupThreadNum(), valid.nettyChildEventGroupThreadNum(),
        valid.s3ExecutorThreadNum(), valid.virtualThreads(), null, null, valid.maxRequestBodySize(),
        valid.requestBodyFileThreshold(), valid.maxRequestHeaderSize(), valid.idleConnectionTimeoutSeconds(),
        valid.strictBucketNames(), valid.strictPartSizes(), valid.compositeMultipartEtags(),
        valid.virtualHostDomains());
    buckets.add("b");
    assertEquals(List.of("a"), copied.buckets());
  }

  @Test
  void theSecretAccessKeyIsNotPartOfTheStringOfAConfig() {
    LocalS3Config config = LocalS3.builder().credentials("access-key-id", "secret-access-key").buildConfig();

    assertTrue(config.authenticationEnabled());
    assertTrue(config.toString().contains("access-key-id"));
    assertFalse(config.toString().contains("secret-access-key"), config.toString());
  }

  private static LocalS3Config withPort(LocalS3Config config, int port) {
    return new LocalS3Config(config.bindHost(), port, config.dataPath(), config.mode(), config.buckets(),
        config.bucketEventListener(), config.objectEventListener(), config.eventListenerExecutor(),
        config.initialDataCacheEnabled(), config.daemonThreads(), config.registerShutdownHook(),
        config.nettyParentEventGroupThreadNum(), config.nettyChildEventGroupThreadNum(), config.s3ExecutorThreadNum(),
        config.virtualThreads(), config.accessKeyId(), config.secretAccessKey(), config.maxRequestBodySize(),
        config.requestBodyFileThreshold(), config.maxRequestHeaderSize(), config.idleConnectionTimeoutSeconds(),
        config.strictBucketNames(), config.strictPartSizes(), config.compositeMultipartEtags(),
        config.virtualHostDomains());
  }

}
