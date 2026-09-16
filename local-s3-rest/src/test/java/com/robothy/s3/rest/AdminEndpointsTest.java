package com.robothy.s3.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.robothy.s3.rest.bootstrap.LocalS3Mode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The {@code /_admin} endpoints of a running service: the statistics, the recent requests, and the reset of the data.
 */
class AdminEndpointsTest {

  private final HttpClient client = HttpClient.newHttpClient();

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void answersTheStatisticsOfTheDataAndTheRequests() throws Exception {
    LocalS3 localS3 = LocalS3.builder().port(-1).mode(LocalS3Mode.IN_MEMORY).buckets("bucket").build();
    localS3.start();
    try {
      assertEquals(200, send(localS3, "PUT", "/bucket/a.txt", "Hello").statusCode());
      assertEquals(200, send(localS3, "PUT", "/bucket/b.txt", "World!").statusCode());
      assertEquals(404, send(localS3, "GET", "/bucket/missing.txt", null).statusCode());
      assertEquals(200, send(localS3, "GET", "/_health", null).statusCode());

      HttpResponse<String> response = send(localS3, "GET", "/_admin/stats", null);
      assertEquals(200, response.statusCode(), response.body());
      assertEquals("application/json", response.headers().firstValue("Content-Type").orElseThrow());
      JsonNode stats = statsWithRequests(localS3, 3);
      assertEquals("IN_MEMORY", stats.get("mode").asText());
      assertEquals(1, stats.at("/data/buckets").asLong());
      assertEquals(2, stats.at("/data/objects").asLong());
      assertEquals(11, stats.at("/data/objectBytes").asLong());
      // What the service keeps in heap, which tells a user holding a large data path where the boundary is.
      assertEquals(stats.at("/data/objects").asLong(), stats.at("/data/loadedObjects").asLong(),
          "An IN_MEMORY service holds the metadata of every object it has.");
      assertTrue(stats.at("/data/loadedObjectMetadataBytes").asLong() > 0,
          "The metadata that is held is measured.");
      assertEquals(0, stats.at("/vectors/vectorBuckets").asLong());
      assertTrue(stats.get("inFlightRequests").asInt() >= 1, "The request that asks is in flight.");
      assertEquals(3, stats.get("totalRequests").asLong(), "The health check isn't recorded: " + stats);
      assertEquals(2, stats.at("/operations/PutObject/count").asLong());
      assertTrue(stats.at("/operations/PutObject/requestsPerSecond").asDouble() > 0);
      assertTrue(stats.at("/operations/PutObject/maxMillis").asDouble() > 0);
      assertEquals(1, stats.at("/operations/GetObject/clientErrors").asLong());
      assertFalse(stats.get("operations").has("HealthCheck"));
      assertFalse(stats.get("operations").has("AdminStats"), "The admin endpoints don't record themselves.");

      JsonNode requests = objectMapper.readTree(send(localS3, "GET", "/_admin/requests?limit=2", null).body())
          .get("requests");
      assertEquals(2, requests.size());
      assertEquals("GetObject", requests.get(0).get("operation").asText(), "The most recent first.");
      assertEquals(404, requests.get(0).get("status").asInt());
      assertEquals("/bucket/missing.txt", requests.get(0).get("uri").asText());
      assertEquals("PutObject", requests.get(1).get("operation").asText());
      assertFalse(requests.get(1).get("requestId").asText().isEmpty());
      assertEquals(400, send(localS3, "GET", "/_admin/requests?limit=-1", null).statusCode());
    } finally {
      localS3.shutdown();
    }
  }

  /**
   * A reset drops the data and the recorded requests, and creates the default buckets again; the service keeps
   * serving requests.
   */
  @Test
  void resetsTheDataOfAnInMemoryService() throws Exception {
    LocalS3 localS3 = LocalS3.builder().port(-1).mode(LocalS3Mode.IN_MEMORY).buckets("bucket").build();
    localS3.start();
    try {
      send(localS3, "PUT", "/bucket/a.txt", "Hello");
      send(localS3, "PUT", "/another-bucket", null);

      HttpResponse<String> reset = send(localS3, "POST", "/_admin/reset", null);
      assertEquals(200, reset.statusCode(), reset.body());

      assertEquals(404, send(localS3, "GET", "/bucket/a.txt", null).statusCode());
      assertEquals(404, send(localS3, "HEAD", "/another-bucket", null).statusCode());
      assertEquals(200, send(localS3, "HEAD", "/bucket", null).statusCode(), "The default bucket is created again.");
      JsonNode stats = statsWithRequests(localS3, 3);
      assertEquals(1, stats.at("/data/buckets").asLong());
      assertEquals(0, stats.at("/data/objects").asLong());
      assertEquals(3, stats.get("totalRequests").asLong(), "Only the requests after the reset: " + stats);

      assertEquals(200, send(localS3, "PUT", "/bucket/b.txt", "World").statusCode());
    } finally {
      localS3.shutdown();
    }
  }

  @Test
  void refusesToResetAPersistentService(@TempDir Path dataPath) throws Exception {
    LocalS3 localS3 = LocalS3.builder().port(-1).mode(LocalS3Mode.PERSISTENCE).dataPath(dataPath.toString())
        .buckets("bucket").build();
    localS3.start();
    try {
      send(localS3, "PUT", "/bucket/a.txt", "Hello");

      HttpResponse<String> reset = send(localS3, "POST", "/_admin/reset", null);
      assertEquals(409, reset.statusCode(), reset.body());
      assertTrue(objectMapper.readTree(reset.body()).get("error").asText().contains("IN_MEMORY"));
      assertEquals(200, send(localS3, "GET", "/bucket/a.txt", null).statusCode());
      assertEquals("PERSISTENCE", objectMapper.readTree(send(localS3, "GET", "/_admin/stats", null).body())
          .get("mode").asText());
    } finally {
      localS3.shutdown();
    }
  }

  /**
   * Unlike the health check, the admin endpoints must be signed if the service requires credentials.
   */
  @Test
  void requiresSignedRequestsIfCredentialsAreConfigured() throws Exception {
    LocalS3 localS3 = LocalS3.builder().port(-1).mode(LocalS3Mode.IN_MEMORY)
        .credentials("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY").build();
    localS3.start();
    try {
      assertEquals(200, send(localS3, "GET", "/_health", null).statusCode());
      assertEquals(403, send(localS3, "GET", "/_admin/stats", null).statusCode());
      assertEquals(403, send(localS3, "POST", "/_admin/reset", null).statusCode());
    } finally {
      localS3.shutdown();
    }
  }

  /**
   * The statistics once they count the given number of requests: a request is recorded once its response is written,
   * which the client may already have read.
   */
  private JsonNode statsWithRequests(LocalS3 localS3, long totalRequests) throws Exception {
    JsonNode stats = null;
    for (int attempt = 0; attempt < 50; attempt++) {
      stats = objectMapper.readTree(send(localS3, "GET", "/_admin/stats", null).body());
      if (stats.get("totalRequests").asLong() >= totalRequests) {
        break;
      }
      Thread.sleep(20);
    }
    return stats;
  }

  private HttpResponse<String> send(LocalS3 localS3, String method, String path, String body) throws Exception {
    HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + localS3.getPort() + path))
        .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

}
