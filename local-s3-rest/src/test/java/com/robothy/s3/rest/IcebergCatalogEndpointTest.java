package com.robothy.s3.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.core.exception.BucketNotExistException;
import com.robothy.s3.core.iceberg.IcebergJson;
import com.robothy.s3.rest.handler.AwsSignatureV4RequestSigner;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The HTTP surface of the Iceberg REST catalog, driven with a plain HTTP client rather than the Iceberg one, so that
 * the catalog is covered by the build that runs on every pull request: the end-to-end tests that drive a real
 * {@code RESTCatalog} live in {@code local-s3-integration-test} and are tagged {@code data-tools}.
 *
 * <p>What is checked here is what the protocol says an answer must look like — the status, and the {@code error.type}
 * that a client maps back to an exception of its own — and that the catalog stays off unless it is asked for.
 */
class IcebergCatalogEndpointTest {

  private static final String SCHEMA = """
      {"type":"struct","schema-id":0,"fields":[{"id":1,"name":"id","required":true,"type":"long"}]}""";

  private static final HttpClient CLIENT = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(10)).build();

  @Test
  void the_catalog_is_not_served_unless_it_is_asked_for() throws Exception {
    try (LocalS3 localS3 = started(LocalS3.builder().port(-1))) {
      // Without a catalog, /iceberg/... is an ordinary bucket path, and there is no bucket named "iceberg".
      HttpResponse<String> response = get(localS3, "/iceberg/v1/config");
      assertNotEquals(200, response.statusCode(),
          "A service that wasn't configured with a catalog must not answer one.");
      assertThrows(BucketNotExistException.class,
          () -> localS3.getS3Manager().bucketService().getBucket("warehouse"),
          "Nor should it have created a warehouse bucket.");
    }
  }

  @Test
  void the_config_endpoint_names_the_warehouse_and_vends_the_settings_of_this_service() throws Exception {
    try (LocalS3 localS3 = started(LocalS3.builder().port(-1).icebergCatalog(true)
        .credentials("an-access-key", "a-secret-key"))) {
      HttpResponse<String> response = get(localS3, "/iceberg/v1/config");
      assertEquals(200, response.statusCode());
      ObjectNode config = IcebergJson.read(response.body());

      assertEquals("s3://warehouse", config.path("overrides").path("warehouse").asString());
      ObjectNode defaults = (ObjectNode) config.get("defaults");
      // The endpoint is the host the request arrived on, so the client is told a name that works for it.
      assertEquals("http://127.0.0.1:" + localS3.getPort(), defaults.path("s3.endpoint").asString());
      assertEquals("true", defaults.path("s3.path-style-access").asString());
      // The catalog answers anonymous requests, so the keys are vended with a loaded table alone.
      assertFalse(defaults.has("s3.access-key-id"), defaults.toString());
      assertFalse(defaults.has("s3.secret-access-key"), defaults.toString());
      post(localS3, "/iceberg/v1/namespaces", """
          {"namespace":["db"],"properties":{}}""");
      ObjectNode created = IcebergJson.read(post(localS3, "/iceberg/v1/namespaces/db/tables", """
          {"name":"orders","schema":%s}""".formatted(SCHEMA)).body());
      assertEquals("an-access-key", created.path("config").path("s3.access-key-id").asString());
      assertEquals("a-secret-key", created.path("config").path("s3.secret-access-key").asString());

      // The warehouse bucket is created with the service, so a table can be created without preparing anything.
      assertEquals("warehouse", localS3.getS3Manager().bucketService().getBucket("warehouse").getName());
    }
  }

  @Test
  void the_settings_are_vended_only_when_the_catalog_is_configured_to() throws Exception {
    try (LocalS3 localS3 = started(LocalS3.builder().port(-1)
        .icebergCatalog(iceberg -> iceberg.settings(new LocalS3IcebergCatalog("s3://warehouse/", true, false, false))))) {
      ObjectNode config = IcebergJson.read(get(localS3, "/iceberg/v1/config").body());
      assertEquals(0, config.path("defaults").size(), "Credential vending is off, so nothing is vended.");
      assertEquals("s3://warehouse", config.path("overrides").path("warehouse").asString());
    }
  }

  @Test
  void a_missing_namespace_and_a_missing_table_are_answered_with_the_types_a_client_reads() throws Exception {
    try (LocalS3 localS3 = started(LocalS3.builder().port(-1).icebergCatalog(true))) {
      HttpResponse<String> missingNamespace = get(localS3, "/iceberg/v1/namespaces/nope");
      assertEquals(404, missingNamespace.statusCode());
      assertEquals("NoSuchNamespaceException", errorType(missingNamespace));

      assertEquals(200, post(localS3, "/iceberg/v1/namespaces", """
          {"namespace":["db"],"properties":{}}""").statusCode());

      HttpResponse<String> missingTable = get(localS3, "/iceberg/v1/namespaces/db/tables/nope");
      assertEquals(404, missingTable.statusCode());
      assertEquals("NoSuchTableException", errorType(missingTable),
          "The type is how a client tells a missing table from a missing namespace.");

      // A namespace that exists answers 204 to HEAD, and one that doesn't answers 404.
      assertEquals(204, head(localS3, "/iceberg/v1/namespaces/db").statusCode());
      assertEquals(404, head(localS3, "/iceberg/v1/namespaces/nope").statusCode());
    }
  }

  @Test
  void a_table_is_created_and_a_second_create_is_an_already_exists() throws Exception {
    try (LocalS3 localS3 = started(LocalS3.builder().port(-1).icebergCatalog(true))) {
      post(localS3, "/iceberg/v1/namespaces", """
          {"namespace":["db"],"properties":{}}""");

      String createTable = """
          {"name":"orders","schema":%s}""".formatted(SCHEMA);
      HttpResponse<String> created = post(localS3, "/iceberg/v1/namespaces/db/tables", createTable);
      assertEquals(200, created.statusCode());
      ObjectNode result = IcebergJson.read(created.body());
      assertTrue(result.path("metadata-location").asString().startsWith("s3://warehouse/db/orders/metadata/"));
      // A loaded table carries the settings its reader needs, which is the credential vending of the protocol.
      assertEquals("true", result.path("config").path("s3.path-style-access").asString());

      HttpResponse<String> again = post(localS3, "/iceberg/v1/namespaces/db/tables", createTable);
      assertEquals(409, again.statusCode());
      assertEquals("AlreadyExistsException", errorType(again));

      assertEquals(204, head(localS3, "/iceberg/v1/namespaces/db/tables/orders").statusCode());
      assertEquals(1, IcebergJson.read(get(localS3, "/iceberg/v1/namespaces/db/tables").body())
          .path("identifiers").size());
    }
  }

  @Test
  void a_multi_level_namespace_is_addressed_with_the_unit_separator() throws Exception {
    try (LocalS3 localS3 = started(LocalS3.builder().port(-1).icebergCatalog(true))) {
      post(localS3, "/iceberg/v1/namespaces", """
          {"namespace":["db"],"properties":{}}""");
      post(localS3, "/iceberg/v1/namespaces", """
          {"namespace":["db","schema"],"properties":{"owner":"tester"}}""");

      // The levels of a namespace travel as one path segment separated by %1F, which is the unit separator.
      HttpResponse<String> loaded = get(localS3, "/iceberg/v1/namespaces/db%1Fschema");
      assertEquals(200, loaded.statusCode());
      ObjectNode namespace = IcebergJson.read(loaded.body());
      assertEquals("db", namespace.path("namespace").get(0).asString());
      assertEquals("schema", namespace.path("namespace").get(1).asString());
      assertEquals("tester", namespace.path("properties").path("owner").asString());

      // A nested namespace is listed under its parent, not beside it.
      assertEquals(1, IcebergJson.read(get(localS3, "/iceberg/v1/namespaces").body()).path("namespaces").size());
      assertEquals(1, IcebergJson.read(get(localS3, "/iceberg/v1/namespaces?parent=db").body())
          .path("namespaces").size());
    }
  }

  @Test
  void an_unknown_path_of_the_catalog_is_a_not_found_rather_than_a_bucket() throws Exception {
    try (LocalS3 localS3 = started(LocalS3.builder().port(-1).icebergCatalog(true))) {
      HttpResponse<String> response = get(localS3, "/iceberg/v1/nonsense");
      assertEquals(404, response.statusCode());
      assertEquals("NotFoundException", errorType(response));
    }
  }

  @Test
  void a_warehouse_that_is_not_a_bucket_of_this_service_is_refused_where_it_is_configured() {
    IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
        () -> LocalS3.builder().icebergCatalog(iceberg -> iceberg.warehouse("file:///tmp/warehouse")));
    assertTrue(refused.getMessage().contains("s3://"), refused.getMessage());
  }

  @Test
  void the_catalog_of_an_in_memory_service_is_emptied_by_a_reset() throws Exception {
    try (LocalS3 localS3 = started(LocalS3.builder().port(-1).icebergCatalog(true))) {
      post(localS3, "/iceberg/v1/namespaces", """
          {"namespace":["db"],"properties":{}}""");
      post(localS3, "/iceberg/v1/namespaces/db/tables", """
          {"name":"orders","schema":%s}""".formatted(SCHEMA));
      assertEquals(204, head(localS3, "/iceberg/v1/namespaces/db/tables/orders").statusCode());

      localS3.reset();

      assertEquals(404, head(localS3, "/iceberg/v1/namespaces/db").statusCode(),
          "A reset should have dropped the catalog with the rest of the data.");
      assertFalse(IcebergJson.read(get(localS3, "/iceberg/v1/namespaces").body())
          .path("namespaces").iterator().hasNext());
      // The warehouse bucket is created again, so the service is usable straight after a reset.
      assertEquals("warehouse", localS3.getS3Manager().bucketService().getBucket("warehouse").getName());
    }
  }

  @Test
  void a_request_signed_remotely_by_the_catalog_is_accepted_by_the_service() throws Exception {
    try (LocalS3 localS3 = started(LocalS3.builder().port(-1).icebergCatalog(true)
        .credentials("an-access-key", "a-secret-key"))) {
      String config = get(localS3, "/iceberg/v1/config").body();
      assertTrue(config.contains("POST /v1/{prefix}/namespaces/{namespace}/tables/{table}/sign"),
          "The remote signing route is declared, so a client that signs remotely may call it.");

      // A loaded table names the route that its S3 requests are signed at.
      post(localS3, "/iceberg/v1/namespaces", """
          {"namespace":["db"],"properties":{}}""");
      ObjectNode created = IcebergJson.read(post(localS3, "/iceberg/v1/namespaces/db/tables", """
          {"name":"orders","schema":%s}""".formatted(SCHEMA)).body());
      assertEquals("http://127.0.0.1:" + localS3.getPort() + "/iceberg",
          created.path("config").path("s3.signer.uri").asString());
      String signerEndpoint = created.path("config").path("s3.signer.endpoint").asString();
      assertEquals("v1/namespaces/db/tables/orders/sign", signerEndpoint);

      String uri = "http://127.0.0.1:" + localS3.getPort() + "/warehouse?list-type=2&prefix=db%2F";
      assertEquals(403, CLIENT.send(HttpRequest.newBuilder(URI.create(uri)).GET().build(),
          HttpResponse.BodyHandlers.ofString()).statusCode(), "An unsigned request is refused.");

      for (String route : new String[] {"/iceberg/" + signerEndpoint, "/iceberg/v1/aws/s3/sign"}) {
        HttpResponse<String> signed = post(localS3, route, """
            {"region":"us-east-1","method":"GET","uri":"%s","headers":{"Accept":["*/*"]},"provider":"s3"}"""
            .formatted(uri));
        assertEquals(200, signed.statusCode(), signed.body());
        assertEquals("no-cache", signed.headers().firstValue("Cache-Control").orElse(null));
        ObjectNode result = IcebergJson.read(signed.body());
        assertEquals(uri, result.path("uri").asString());

        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(uri)).GET();
        // The JDK client sends the Host header of the URI itself, and refuses to be given one.
        result.path("headers").properties().stream()
            .filter(header -> !"host".equalsIgnoreCase(header.getKey()))
            .forEach(header -> header.getValue().forEach(value -> request.header(header.getKey(), value.asString())));
        HttpResponse<String> listed = CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, listed.statusCode(), route + ": " + listed.body());
        assertTrue(listed.body().contains("db/orders/metadata/"), listed.body());
      }
    }
  }

  @Test
  void the_credentials_route_of_a_table_answers_temporary_credentials_that_the_service_accepts() throws Exception {
    try (LocalS3 localS3 = started(LocalS3.builder().port(-1).icebergCatalog(true)
        .credentials("an-access-key", "a-secret-key"))) {
      assertTrue(get(localS3, "/iceberg/v1/config").body()
          .contains("GET /v1/{prefix}/namespaces/{namespace}/tables/{table}/credentials"));

      post(localS3, "/iceberg/v1/namespaces", """
          {"namespace":["db"],"properties":{}}""");
      ObjectNode created = IcebergJson.read(post(localS3, "/iceberg/v1/namespaces/db/tables", """
          {"name":"orders","schema":%s}""".formatted(SCHEMA)).body());
      String credentialsEndpoint = created.path("config").path("client.refresh-credentials-endpoint").asString();
      assertEquals("v1/namespaces/db/tables/orders/credentials", credentialsEndpoint);

      HttpResponse<String> answered = get(localS3, "/iceberg/" + credentialsEndpoint);
      assertEquals(200, answered.statusCode(), answered.body());
      assertEquals("no-store", answered.headers().firstValue("Cache-Control").orElse(null));
      JsonNode storageCredentials = IcebergJson.read(answered.body()).path("storage-credentials");
      assertEquals(1, storageCredentials.size());
      assertEquals("s3://", storageCredentials.get(0).path("prefix").asString());
      JsonNode config = storageCredentials.get(0).path("config");
      String accessKeyId = config.path("s3.access-key-id").asString();
      String sessionToken = config.path("s3.session-token").asString();
      assertTrue(accessKeyId.startsWith("ASIA"), accessKeyId);
      assertNotEquals("a-secret-key", config.path("s3.secret-access-key").asString());
      long expiresAt = Long.parseLong(config.path("s3.session-token-expires-at-ms").asString());
      assertTrue(expiresAt > System.currentTimeMillis() + Duration.ofMinutes(30).toMillis(), "Valid for an hour.");

      // The temporary credentials sign an S3 request that the service verifies.
      String uri = "http://127.0.0.1:" + localS3.getPort() + "/warehouse?list-type=2&prefix=db%2F";
      Map<String, List<String>> headers = new LinkedHashMap<>();
      headers.put("X-Amz-Security-Token", List.of(sessionToken));
      Map<String, List<String>> signed = new AwsSignatureV4RequestSigner(accessKeyId,
          config.path("s3.secret-access-key").asString()).sign("us-east-1", "GET", uri, headers);
      HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(uri)).GET();
      signed.forEach((name, values) -> {
        if (!"host".equalsIgnoreCase(name)) {
          values.forEach(value -> request.header(name, value));
        }
      });
      HttpResponse<String> listed = CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(200, listed.statusCode(), listed.body());
      assertTrue(listed.body().contains("db/orders/metadata/"), listed.body());

      assertEquals("NoSuchTableException",
          errorType(get(localS3, "/iceberg/v1/namespaces/db/tables/missing/credentials")));
    }
  }

  @Test
  void a_service_that_takes_unsigned_requests_names_no_credentials_route() throws Exception {
    try (LocalS3 localS3 = started(LocalS3.builder().port(-1).icebergCatalog(true))) {
      post(localS3, "/iceberg/v1/namespaces", """
          {"namespace":["db"],"properties":{}}""");
      ObjectNode created = IcebergJson.read(post(localS3, "/iceberg/v1/namespaces/db/tables", """
          {"name":"orders","schema":%s}""".formatted(SCHEMA)).body());
      assertTrue(created.path("config").path("client.refresh-credentials-endpoint").isMissingNode());
    }
  }

  private static LocalS3 started(LocalS3Builder builder) {
    LocalS3 localS3 = builder.netty(netty -> netty.registerShutdownHook(false)).build();
    localS3.start();
    return localS3;
  }

  private static String errorType(HttpResponse<String> response) {
    return IcebergJson.read(response.body()).path("error").path("type").asString();
  }

  private static HttpResponse<String> get(LocalS3 localS3, String path) throws IOException, InterruptedException {
    return CLIENT.send(request(localS3, path).GET().build(), HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<String> head(LocalS3 localS3, String path) throws IOException, InterruptedException {
    return CLIENT.send(request(localS3, path).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<String> post(LocalS3 localS3, String path, String body)
      throws IOException, InterruptedException {
    return CLIENT.send(request(localS3, path)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
  }

  private static HttpRequest.Builder request(LocalS3 localS3, String path) {
    return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + localS3.getPort() + path))
        .timeout(Duration.ofSeconds(30));
  }

}
