package com.robothy.s3.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.robothy.s3.rest.bootstrap.LocalS3Mode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * The optional features of a service: every one is served by default, and one that is turned off has no routes.
 */
class FeaturesTest {

  private final HttpClient client = HttpClient.newHttpClient();

  @Test
  void everyFeatureIsServedByDefault() throws Exception {
    LocalS3 localS3 = LocalS3.builder().port(-1).mode(LocalS3Mode.IN_MEMORY).build();
    assertEquals(LocalS3Features.all(), localS3.getConfig().features());
    localS3.start();
    try {
      for (Map.Entry<String, HttpRequest.Builder> request : requests(localS3).entrySet()) {
        HttpResponse<String> response = client.send(request.getValue().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), request.getKey() + ": " + response.body());
      }
    } finally {
      localS3.shutdown();
    }
  }

  @Test
  void aFeatureThatIsTurnedOffIsNotServed() throws Exception {
    LocalS3 localS3 = LocalS3.builder().port(-1).mode(LocalS3Mode.IN_MEMORY)
        .features(f -> f.vector(false).s3Tables(false).kms(false).sts(false).console(false))
        .build();
    localS3.start();
    try {
      for (Map.Entry<String, HttpRequest.Builder> request : requests(localS3).entrySet()) {
        HttpResponse<String> response = client.send(request.getValue().build(), HttpResponse.BodyHandlers.ofString());
        assertNotEquals(200, response.statusCode(), request.getKey() + ": " + response.body());
      }
    } finally {
      localS3.shutdown();
    }
  }

  @Test
  void theStatisticsNameTheFeaturesThatAreOn() throws Exception {
    LocalS3 localS3 = LocalS3.builder().port(-1).mode(LocalS3Mode.IN_MEMORY)
        .features(f -> f.kms(false).sts(false))
        .icebergCatalog(true)
        .build();
    localS3.start();
    try {
      List<String> expected = List.of("vector", "s3Tables", "console", "icebergCatalog", "website");
      assertEquals(expected, localS3.statistics().features());
      HttpResponse<String> response = client.send(HttpRequest.newBuilder(
          URI.create("http://localhost:" + localS3.getPort() + "/_admin/stats")).build(),
          HttpResponse.BodyHandlers.ofString());
      List<String> served = new ArrayList<>();
      new ObjectMapper().readTree(response.body()).get("features").forEach(name -> served.add(name.asText()));
      assertEquals(expected, served);
    } finally {
      localS3.shutdown();
    }
  }

  @Test
  void theFeaturesAreReadFromTheEnvironment() {
    Map<String, String> variables = Map.of(
        LocalS3Environment.LOCAL_S3_FEATURES_VECTOR, "false",
        LocalS3Environment.LOCAL_S3_FEATURES_KMS, "false",
        LocalS3Environment.LOCAL_S3_FEATURES_CONSOLE, "true");
    LocalS3Config config = LocalS3.builder().fromEnvironment(variables::get).buildConfig();
    assertEquals(new LocalS3Features(false, true, false, true, true), config.features());
  }

  /**
   * A request of each feature that succeeds when the feature is served, by the feature it addresses.
   */
  private static Map<String, HttpRequest.Builder> requests(LocalS3 localS3) {
    String endpoint = "http://localhost:" + localS3.getPort();
    return Map.of(
        "vector", HttpRequest.newBuilder(URI.create(endpoint + "/ListVectorBuckets"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{}")),
        "s3Tables", HttpRequest.newBuilder(URI.create(endpoint + "/s3tables/buckets")).GET(),
        "kms", HttpRequest.newBuilder(URI.create(endpoint + "/"))
            .header("Content-Type", "application/x-amz-json-1.1")
            .header("X-Amz-Target", "TrentService.GenerateRandom")
            .POST(HttpRequest.BodyPublishers.ofString("{\"NumberOfBytes\":16}")),
        "sts", HttpRequest.newBuilder(URI.create(endpoint + "/"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString("Action=GetCallerIdentity&Version=2011-06-15")),
        "console", HttpRequest.newBuilder(URI.create(endpoint + "/_admin/ui")).GET());
  }

}
