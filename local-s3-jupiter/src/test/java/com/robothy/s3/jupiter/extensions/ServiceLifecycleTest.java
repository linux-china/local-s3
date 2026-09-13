package com.robothy.s3.jupiter.extensions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import com.robothy.s3.jupiter.LocalS3;
import com.robothy.s3.jupiter.LocalS3Endpoint;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.Socket;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Every service that {@code @LocalS3} launches serves the tests of the class or method it annotates, including the
 * {@code @Nested} classes of an annotated class, and is shut down when that class or method is done, whether the tests
 * run one after the other or in parallel.
 */
class ServiceLifecycleTest {

  private static volatile boolean runningFixture;

  @ParameterizedTest(name = "parallel: {0}")
  @ValueSource(booleans = {false, true})
  void eachServiceServesItsContextAndIsShutDownWithIt(boolean parallel) {
    Fixture.ports.clear();
    TestExecutionSummary summary = run(Fixture.class, parallel);

    assertEquals(0, summary.getTotalFailureCount(), () -> failures(summary));
    assertEquals(5, summary.getTestsSucceededCount());

    Map<String, Integer> ports = Fixture.ports;
    assertEquals(Set.of("outer", "outer from nested", "nested", "nested from method", "method"), ports.keySet());
    assertEquals(ports.get("outer"), ports.get("outer from nested"), "A @Nested class uses the service of its enclosing class.");
    assertNotEquals(ports.get("outer"), ports.get("nested"), "An annotated @Nested class has a service of its own.");
    assertEquals(ports.get("nested"), ports.get("nested from method"));
    assertNotEquals(ports.get("nested"), ports.get("method"), "An annotated method has a service of its own.");
    for (Map.Entry<String, Integer> port : ports.entrySet()) {
      assertThrows(IOException.class, () -> new Socket("127.0.0.1", port.getValue()).close(),
          "The service of \"" + port.getKey() + "\" is shut down.");
    }
  }

  private static TestExecutionSummary run(Class<?> testClass, boolean parallel) {
    LauncherDiscoveryRequestBuilder request = LauncherDiscoveryRequestBuilder.request().selectors(selectClass(testClass));
    if (parallel) {
      request.configurationParameter("junit.jupiter.execution.parallel.enabled", "true")
          .configurationParameter("junit.jupiter.execution.parallel.mode.default", "concurrent")
          .configurationParameter("junit.jupiter.execution.parallel.mode.classes.default", "concurrent");
    }
    LauncherDiscoveryRequest discoveryRequest = request.build();
    SummaryGeneratingListener listener = new SummaryGeneratingListener();
    runningFixture = true;
    try {
      LauncherFactory.create().execute(discoveryRequest, listener);
    } finally {
      runningFixture = false;
    }
    return listener.getSummary();
  }

  private static String failures(TestExecutionSummary summary) {
    StringWriter failures = new StringWriter();
    summary.printFailuresTo(new PrintWriter(failures), 20);
    return failures.toString();
  }

  static boolean runningFixture() {
    return runningFixture;
  }

  /**
   * Only runs when {@linkplain ServiceLifecycleTest} runs it.
   */
  @LocalS3
  @EnabledIf("com.robothy.s3.jupiter.extensions.ServiceLifecycleTest#runningFixture")
  static class Fixture {

    static final Map<String, Integer> ports = new ConcurrentHashMap<>();

    @BeforeAll
    static void rememberThePortOfTheClass(LocalS3Endpoint endpoint) {
      ports.put("outer", endpoint.port());
    }

    @Test
    void usesTheServiceOfTheClass(LocalS3Endpoint endpoint) {
      assertEquals(ports.get("outer"), endpoint.port());
    }

    @AfterAll
    static void theServiceOfTheClassServesUntilTheEnd(S3Client client) {
      client.listBuckets();
    }

    @Nested
    class NotAnnotated {

      @Test
      void usesTheServiceOfTheEnclosingClass(LocalS3Endpoint endpoint, S3Client client) {
        ports.put("outer from nested", endpoint.port());
        client.listBuckets();
      }

    }

    @Nested
    @LocalS3
    class Annotated {

      @Test
      void usesTheServiceOfTheNestedClass(LocalS3Endpoint endpoint, S3Client client) {
        ports.put("nested", endpoint.port());
        client.listBuckets();
      }

      @Test
      void usesTheServiceOfTheNestedClassAgain(LocalS3Endpoint endpoint) {
        ports.put("nested from method", endpoint.port());
      }

      @Test
      @LocalS3
      void usesTheServiceOfTheMethod(LocalS3Endpoint endpoint, S3Client client) {
        ports.put("method", endpoint.port());
        client.listBuckets();
      }

    }

  }

}
