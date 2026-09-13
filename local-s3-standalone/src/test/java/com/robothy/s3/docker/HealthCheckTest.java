package com.robothy.s3.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import com.robothy.s3.testcontainers.LocalS3Container;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Tag(ImageUnderTest.JUNIT_TAG)
@Testcontainers
class HealthCheckTest {

  /**
   * Waits for the health check, like a Kubernetes readiness probe, instead of the log message.
   */
  @Container
  private final LocalS3Container container = new LocalS3Container(ImageUnderTest.TAG)
      .withMode(LocalS3Container.Mode.IN_MEMORY)
      .withRandomHttpPort()
      .waitingFor(Wait.forHttp("/_health").forPort(29090).forStatusCode(200));

  @Test
  void answersHealthChecks() throws Exception {
    HttpResponse<String> response = HttpClient.newHttpClient().send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + container.getPort() + "/_health")).GET().build(),
        HttpResponse.BodyHandlers.ofString());

    assertEquals(200, response.statusCode());
    assertEquals("{\"status\":\"UP\"}", response.body());
  }

}
