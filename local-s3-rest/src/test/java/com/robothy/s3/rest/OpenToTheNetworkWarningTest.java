package com.robothy.s3.rest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

/**
 * A service that answers unsigned requests from other machines says so when it starts: without credentials and
 * without a loopback bind host, everyone who reaches the port reads and writes every bucket. The Docker image binds
 * {@code 0.0.0.0}, so publishing its port without credentials is what this warns about.
 */
class OpenToTheNetworkWarningTest {

  private static final String WARNING = "without authentication";

  @ParameterizedTest
  @ValueSource(strings = {"127.0.0.1", "127.0.0.2", "localhost", "::1"})
  void aLoopbackBindHostIsReachableFromThisMachineAlone(String bindHost) {
    assertFalse(LocalS3.builder().bindHost(bindHost).buildConfig().reachableFromOtherHosts(), bindHost);
  }

  @ParameterizedTest
  @ValueSource(strings = {"0.0.0.0", "::"})
  void aWildcardBindHostIsReachableFromOtherMachines(String bindHost) {
    assertTrue(LocalS3.builder().bindHost(bindHost).buildConfig().reachableFromOtherHosts(), bindHost);
  }

  @Test
  void aServiceWithoutCredentialsOnAWildcardBindHostWarns() {
    String log = logOf(LocalS3.create(LocalS3.builder().bindHost("0.0.0.0").port(29090).buildConfig())
        ::warnIfOpenToTheNetwork);

    assertTrue(log.contains("WARN"), log);
    assertTrue(log.contains(WARNING), log);
    assertTrue(log.contains("0.0.0.0:29090"), log);
    assertTrue(log.contains("read, write and delete every bucket"), log);
    assertTrue(log.contains("Any signature is accepted"), log);
    assertTrue(log.contains("/_admin/reset"), "The warning names the anonymous admin endpoints: " + log);
    assertTrue(log.contains("LOCAL_S3_ACCESS_KEY_ID"), "The warning says how to turn it off: " + log);
  }

  @Test
  void credentialsTurnTheWarningOff() {
    String log = logOf(LocalS3.create(LocalS3.builder().bindHost("0.0.0.0").credentials("ak", "sk").buildConfig())
        ::warnIfOpenToTheNetwork);

    assertFalse(log.contains(WARNING), log);
  }

  @Test
  void aLoopbackBindHostTurnsTheWarningOff() {
    String log = logOf(LocalS3.create(LocalS3.builder().bindHost("127.0.0.1").buildConfig())
        ::warnIfOpenToTheNetwork);

    assertFalse(log.contains(WARNING), log);
  }

  @Test
  void aCatalogThatVendsCredentialsOnAWildcardBindHostWarns() {
    String log = logOf(LocalS3.create(LocalS3.builder().bindHost("0.0.0.0").port(29090).credentials("ak", "sk")
        .icebergCatalog(true).buildConfig())::warnIfCatalogVendsCredentialsToTheNetwork);

    assertTrue(log.contains("WARN"), log);
    assertTrue(log.contains("0.0.0.0:29090"), log);
    assertTrue(log.contains("vends the credentials of this service"), log);
    assertTrue(log.contains("credentialVending(false)"), "The warning says how to turn it off: " + log);
  }

  @ParameterizedTest
  @ValueSource(strings = {"no-vending", "no-credentials", "loopback"})
  void aCatalogThatCannotLeakCredentialsToTheNetworkDoesNotWarn(String setup) {
    LocalS3Builder builder = LocalS3.builder().bindHost("loopback".equals(setup) ? "127.0.0.1" : "0.0.0.0");
    if (!"no-credentials".equals(setup)) {
      builder.credentials("ak", "sk");
    }
    builder.icebergCatalog(iceberg -> iceberg.credentialVending(!"no-vending".equals(setup)));
    String log = logOf(LocalS3.create(builder.buildConfig())::warnIfCatalogVendsCredentialsToTheNetwork);

    assertFalse(log.contains("vends the credentials"), log);
  }

  /**
   * The defaults of an embedded service, which is what the tests of an application start: no credentials, and the
   * loopback bind host. Starting one doesn't warn, so the warning doesn't turn into noise that is scrolled past.
   */
  @Test
  void theDefaultsOfAnEmbeddedServiceDoNotWarnWhenItStarts() {
    LocalS3 localS3 = LocalS3.builder().port(-1).build();
    String log = logOf(() -> {
      try {
        localS3.start();
      } finally {
        localS3.shutdown();
      }
    });

    assertTrue(log.contains("LocalS3 started."), log);
    assertFalse(log.contains(WARNING), log);
  }

  /**
   * Run an action with an appender on the logger of {@linkplain LocalS3}, and return what it logged, one event per
   * line, prefixed by its level.
   */
  private static String logOf(Runnable action) {
    Logger logger = (Logger) LoggerFactory.getLogger(LocalS3.class);
    Level previousLevel = logger.getLevel();
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    logger.setLevel(Level.INFO);
    try {
      action.run();
    } finally {
      logger.detachAppender(appender);
      logger.setLevel(previousLevel);
    }

    List<ILoggingEvent> events = List.copyOf(appender.list);
    return events.stream().map(event -> event.getLevel() + " " + event.getFormattedMessage())
        .collect(Collectors.joining("\n"));
  }

}
