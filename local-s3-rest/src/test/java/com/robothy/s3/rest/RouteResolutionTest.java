package com.robothy.s3.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The routes of the service tell apart the operations whose requests differ only by a parameter, and reject a request
 * that combines the subresources of several operations, rather than answering it by one of them.
 */
class RouteResolutionTest {

  private static LocalS3 localS3;

  private final HttpClient client = HttpClient.newHttpClient();

  @BeforeAll
  static void start() {
    localS3 = LocalS3.builder().port(-1).buckets("bucket").build();
    localS3.start();
  }

  @AfterAll
  static void stop() {
    localS3.shutdown();
  }

  @Test
  void theIdOfAConfigurationTellsGetFromList() throws Exception {
    HttpResponse<String> list = get("/bucket?analytics");
    assertEquals(200, list.statusCode(), list.body());
    assertTrue(list.body().contains("<ListBucketAnalyticsConfigurationResult"), list.body());

    HttpResponse<String> get = get("/bucket?analytics&id=report");
    assertEquals(404, get.statusCode(), get.body());
    assertTrue(get.body().contains("<Code>NoSuchConfiguration</Code>"), get.body());

    HttpResponse<String> tiering = get("/bucket?intelligent-tiering&id=archive");
    assertEquals(501, tiering.statusCode());
    assertTrue(tiering.body().contains("'GetBucketIntelligentTieringConfiguration'"), tiering.body());
  }

  @Test
  void aRequestThatCombinesSubresourcesIsRejected() throws Exception {
    HttpResponse<String> response = get("/bucket?acl&tagging");
    assertEquals(400, response.statusCode(), response.body());
    assertTrue(response.body().contains("<Code>InvalidRequest</Code>"), response.body());
    assertTrue(response.body().contains("GetBucketAcl") && response.body().contains("GetBucketTagging"),
        response.body());

    assertEquals(200, get("/bucket?acl").statusCode());
  }

  private HttpResponse<String> get(String pathAndQuery) throws Exception {
    return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + localS3.getPort() + pathAndQuery))
        .GET().build(), HttpResponse.BodyHandlers.ofString());
  }

}
