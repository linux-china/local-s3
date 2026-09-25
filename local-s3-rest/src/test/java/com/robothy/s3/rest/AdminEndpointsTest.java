package com.robothy.s3.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.rest.bootstrap.LocalS3Mode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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
      assertEquals(0, stats.at("/data/loadedObjectMetadataBytes").asLong(),
          "An IN_MEMORY service never writes the metadata it holds, so there is no persisted form to measure.");
      assertEquals(0, stats.at("/vectors/vectorBuckets").asLong());
      assertTrue(stats.get("inFlightRequests").asInt() >= 1, "The request that asks is in flight.");
      assertEquals(3, stats.get("totalRequests").asLong(), "The health check isn't recorded: " + stats);
      assertEquals(2, stats.at("/operations/PutObject/count").asLong());
      assertTrue(stats.at("/operations/PutObject/requestsPerSecond").asDouble() > 0);
      assertTrue(stats.at("/operations/PutObject/maxMillis").asDouble() > 0);
      assertEquals(1, stats.at("/operations/GetObject/clientErrors").asLong());
      assertFalse(stats.get("operations").has("HealthCheck"));
      assertFalse(stats.get("operations").has("AdminStats"), "The admin endpoints don't record themselves.");
      assertTrue(stats.get("notImplemented").isEmpty(), "No request was answered 501: " + stats);

      JsonNode requests = objectMapper.readTree(send(localS3, "GET", "/_admin/requests?limit=2", null).body())
          .get("requests");
      assertEquals(2, requests.size());
      assertEquals("GetObject", requests.get(0).get("operation").asText(), "The most recent first.");
      assertEquals(404, requests.get(0).get("status").asInt());
      assertEquals("/bucket/missing.txt", requests.get(0).get("uri").asText());
      assertEquals("PutObject", requests.get(1).get("operation").asText());
      assertFalse(requests.get(1).get("requestId").asText().isEmpty());
      assertEquals(400, send(localS3, "GET", "/_admin/requests?limit=-1", null).statusCode());

      // The requests answered 501 tell which operations a client needs that LocalS3 lacks.
      assertEquals(501, send(localS3, "PATCH", "/bucket/a.txt", "x").statusCode());
      assertEquals(501, send(localS3, "GET", "/bucket/a.txt?torrent", null).statusCode());
      JsonNode notImplemented = statsWithRequests(localS3, 5).get("notImplemented");
      assertEquals(1, notImplemented.path("PATCH /{bucket}/{key}").asLong(), notImplemented.toString());
      assertEquals(1, notImplemented.path("GetObjectTorrent").asLong(), notImplemented.toString());
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
   * The snippets are written from the configuration of the service: the host the request addressed, plain HTTP, the
   * path-style addressing that a local endpoint needs, and the Iceberg catalog if the service serves one.
   */
  @Test
  void answersTheConnectionSnippetsOfTheService() throws Exception {
    LocalS3 localS3 = LocalS3.builder().port(-1).mode(LocalS3Mode.IN_MEMORY).icebergCatalog(true).build();
    localS3.start();
    try {
      String host = "127.0.0.1:" + localS3.getPort();
      HttpResponse<String> response = send(localS3, "GET", "/_admin/snippets", null);
      assertEquals(200, response.statusCode(), response.body());
      JsonNode body = objectMapper.readTree(response.body());
      assertEquals("http://" + host, body.get("endpoint").asText());
      assertTrue(body.get("icebergCatalog").asBoolean());
      assertFalse(body.has("duckdbQuery"), "No bucket was asked about.");
      List<String> ids = new ArrayList<>();
      body.get("snippets").forEach(snippet -> ids.add(snippet.get("id").asText()));
      assertEquals(List.of("duckdb", "env", "aws-cli", "boto3", "polars", "pyiceberg", "spark"), ids);

      // One snippet is text, to paste or to pipe into its client.
      HttpResponse<String> duckdb = send(localS3, "GET", "/_admin/snippets/duckdb", null);
      assertEquals(200, duckdb.statusCode(), duckdb.body());
      assertEquals("text/plain; charset=utf-8", duckdb.headers().firstValue("Content-Type").orElseThrow());
      String sql = duckdb.body();
      assertTrue(sql.contains("ENDPOINT '" + host + "'"), sql);
      assertTrue(sql.contains("URL_STYLE 'path'"), sql);
      assertTrue(sql.contains("USE_SSL false"), sql);
      assertTrue(sql.contains("ENDPOINT 'http://" + host + "/iceberg'"), sql);
      assertTrue(sql.contains("AUTHORIZATION_TYPE 'none'"), sql);
      assertTrue(sql.contains("-- ATTACH 'arn:aws:s3tables:us-east-1:000000000000:bucket/<table-bucket>' AS tb "
          + "(TYPE ICEBERG, ENDPOINT 'http://" + host + "/iceberg', AUTHORIZATION_TYPE 'none');"), sql);
      assertTrue(sql.contains("SESSION_TOKEN"), sql);
      assertTrue(send(localS3, "GET", "/_admin/snippets/pyiceberg", null).body()
          .contains("uri=\"http://" + host + "/iceberg\""));
      String env = send(localS3, "GET", "/_admin/snippets/env", null).body();
      assertTrue(env.contains("export AWS_ENDPOINT_URL='http://" + host + "'"), env);
      assertTrue(env.contains("export S3_ENDPOINT_URL='http://" + host + "'"), env);
      assertTrue(env.contains("export AWS_ALLOW_HTTP=true"), env);
      assertTrue(env.contains("\n# s5cmd ls\n"), env);
      String polars = send(localS3, "GET", "/_admin/snippets/polars", null).body();
      assertTrue(polars.contains("\"aws_endpoint_url\": \"http://" + host + "\""), polars);
      assertTrue(polars.contains("\"aws_allow_http\": \"true\""), polars);
      assertTrue(polars.contains("\"aws_virtual_hosted_style_request\": \"false\""), polars);
      assertEquals(404, send(localS3, "GET", "/_admin/snippets/unknown", null).statusCode(),
          "An unknown snippet is an object of a bucket named _admin, which doesn't exist.");
    } finally {
      localS3.shutdown();
    }
  }

  /**
   * The example query of an object reads its rows if DuckDB knows its format, and its files otherwise; a key is quoted
   * for each language it is written into.
   */
  @Test
  void writesTheQueryOfAnObjectOrAPrefix() throws Exception {
    LocalS3 localS3 = LocalS3.builder().port(-1).mode(LocalS3Mode.IN_MEMORY).build();
    localS3.start();
    try {
      assertEquals("SELECT * FROM 's3://lake/events/day=1/part-0.parquet' LIMIT 10;",
          query(localS3, "bucket=lake&key=events%2Fday%3D1%2Fpart-0.parquet"));
      assertEquals("SELECT * FROM glob('s3://lake/events/**') LIMIT 100;", query(localS3, "bucket=lake&key=events%2F"));
      assertEquals("SELECT * FROM glob('s3://lake/**') LIMIT 100;", query(localS3, "bucket=lake"));
      assertEquals("SELECT filename, size, last_modified FROM read_blob('s3://lake/model.bin');",
          query(localS3, "bucket=lake&key=model.bin"));
      assertEquals("SELECT * FROM 's3://lake/it''s.csv' LIMIT 10;", query(localS3, "bucket=lake&key=it%27s.csv"));

      String boto3 = send(localS3, "GET", "/_admin/snippets/boto3?bucket=lake&key=a%22b.csv", null).body();
      assertTrue(boto3.contains("Key=\"a\\\"b.csv\""), boto3);
      String cli = send(localS3, "GET", "/_admin/snippets/aws-cli?bucket=lake&key=it%27s.csv", null).body();
      assertTrue(cli.contains("aws s3 cp 's3://lake/it'\\''s.csv' -"), cli);
      String env = send(localS3, "GET", "/_admin/snippets/env?bucket=lake&key=events%2F", null).body();
      assertTrue(env.contains("# s5cmd ls 's3://lake/events/*'"), env);
      String polars = send(localS3, "GET", "/_admin/snippets/polars?bucket=lake&key=a%22b.csv", null).body();
      assertTrue(polars.contains("pl.scan_csv(\"s3://lake/a\\\"b.csv\", storage_options=storage_options)"), polars);
      polars = send(localS3, "GET", "/_admin/snippets/polars?bucket=lake&key=events%2F", null).body();
      assertTrue(polars.contains("pl.scan_parquet(\"s3://lake/events/**/*.parquet\""), polars);

    } finally {
      localS3.shutdown();
    }
  }

  private String query(LocalS3 localS3, String parameters) throws Exception {
    return objectMapper.readTree(send(localS3, "GET", "/_admin/snippets?" + parameters, null).body())
        .get("duckdbQuery").asText();
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
      assertEquals(403, send(localS3, "GET", "/_admin/snippets/duckdb", null).statusCode(),
          "The snippets carry the credentials of the service.");
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
