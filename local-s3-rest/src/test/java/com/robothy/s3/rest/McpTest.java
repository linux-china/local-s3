package com.robothy.s3.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.rest.bootstrap.LocalS3Mode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The built-in MCP server, {@code POST /_mcp}: the handshake, the tools an agent stores and reads artifacts with, the
 * objects as resources, and the guards.
 */
class McpTest {

  private final HttpClient client = HttpClient.newHttpClient();

  private final ObjectMapper objectMapper = new ObjectMapper();

  private final AtomicInteger ids = new AtomicInteger();

  @Test
  void isOffUnlessTurnedOn() throws Exception {
    LocalS3 localS3 = LocalS3.builder().port(-1).mode(LocalS3Mode.IN_MEMORY).build();
    localS3.start();
    try {
      HttpResponse<String> response = post(localS3, request("ping", Map.of()), Map.of());
      assertNotEquals(200, response.statusCode(), response.body());
    } finally {
      localS3.shutdown();
    }
  }

  @Test
  void initializesAndListsItsTools() throws Exception {
    LocalS3 localS3 = start();
    try {
      JsonNode initialize = rpc(localS3, "initialize", Map.of("protocolVersion", "2025-06-18",
          "capabilities", Map.of(), "clientInfo", Map.of("name", "test", "version", "1")));
      assertEquals("2025-06-18", initialize.at("/result/protocolVersion").asString());
      assertEquals("LocalS3", initialize.at("/result/serverInfo/name").asString());
      assertTrue(initialize.at("/result/capabilities").has("tools"));
      assertTrue(initialize.at("/result/capabilities").has("resources"));

      // A version the server doesn't know is answered with the latest it does.
      assertEquals("2025-11-25", rpc(localS3, "initialize", Map.of("protocolVersion", "1999-01-01"))
          .at("/result/protocolVersion").asString());

      HttpResponse<String> initialized = post(localS3,
          Map.of("jsonrpc", "2.0", "method", "notifications/initialized"), Map.of());
      assertEquals(202, initialized.statusCode());
      assertEquals("", initialized.body());

      Set<String> tools = new HashSet<>();
      rpc(localS3, "tools/list", Map.of()).at("/result/tools").forEach(tool -> {
        tools.add(tool.get("name").asString());
        assertEquals("object", tool.at("/inputSchema/type").asString());
      });
      assertEquals(Set.of("list_buckets", "list_objects", "read_object", "put_object", "presign", "delete_object",
          "create_bucket"), tools);

      assertEquals(0, rpc(localS3, "ping", Map.of()).get("result").size());
      assertEquals(-32601, rpc(localS3, "sampling/createMessage", Map.of()).at("/error/code").asInt());
      assertEquals(-32602, rpc(localS3, "tools/call", Map.of("name", "nope")).at("/error/code").asInt());
    } finally {
      localS3.shutdown();
    }
  }

  @Test
  void storesListsReadsAndDeletesAnArtifact() throws Exception {
    LocalS3 localS3 = start();
    try {
      JsonNode created = call(localS3, "create_bucket", Map.of("bucket", "artifacts"));
      assertFalse(created.get("isError").asBoolean(), created.toString());

      JsonNode put = call(localS3, "put_object", Map.of("bucket", "artifacts", "key", "reports/summary.md",
          "content", "# Done\n"));
      assertFalse(put.get("isError").asBoolean(), put.toString());
      assertEquals(7, put.at("/structuredContent/size").asLong());
      assertEquals("s3://artifacts/reports/summary.md", put.at("/structuredContent/uri").asString());
      assertTrue(put.at("/structuredContent/contentType").asString().startsWith("text/markdown"));

      JsonNode buckets = call(localS3, "list_buckets", Map.of());
      assertEquals("artifacts", buckets.at("/structuredContent/buckets/0/name").asString());
      // The same result as text, for the clients that read text alone.
      assertEquals(buckets.get("structuredContent"),
          objectMapper.readTree(buckets.at("/content/0/text").asString()));

      JsonNode root = call(localS3, "list_objects", Map.of("bucket", "artifacts"));
      assertEquals("reports/", root.at("/structuredContent/commonPrefixes/0").asString());
      assertEquals(0, root.at("/structuredContent/objects").size());
      JsonNode all = call(localS3, "list_objects", Map.of("bucket", "artifacts", "delimiter", ""));
      assertEquals("reports/summary.md", all.at("/structuredContent/objects/0/key").asString());

      JsonNode read = call(localS3, "read_object", Map.of("bucket", "artifacts", "key", "reports/summary.md"));
      assertEquals("# Done\n", read.at("/content/0/text").asString());
      assertFalse(read.at("/structuredContent/truncated").asBoolean());

      JsonNode deleted = call(localS3, "delete_object", Map.of("bucket", "artifacts", "key", "reports/summary.md"));
      assertFalse(deleted.get("isError").asBoolean(), deleted.toString());
      JsonNode missing = call(localS3, "read_object", Map.of("bucket", "artifacts", "key", "reports/summary.md"));
      assertTrue(missing.get("isError").asBoolean());
      assertTrue(missing.at("/content/0/text").asString().startsWith("NoSuchKey"), missing.toString());

      // A tool failure is a result the model reads, not an error of the protocol.
      JsonNode invalid = call(localS3, "put_object", Map.of("bucket", "artifacts", "key", "a.txt"));
      assertTrue(invalid.get("isError").asBoolean());
      assertTrue(invalid.at("/content/0/text").asString().contains("content"));
    } finally {
      localS3.shutdown();
    }
  }

  @Test
  void readsALargeObjectARangeAtATime() throws Exception {
    LocalS3 localS3 = start();
    try {
      call(localS3, "put_object", Map.of("bucket", "bucket", "key", "log.txt", "content", "0123456789"));

      JsonNode head = call(localS3, "read_object", Map.of("bucket", "bucket", "key", "log.txt", "max_bytes", 4));
      assertEquals("0123", head.at("/content/0/text").asString());
      assertTrue(head.at("/structuredContent/truncated").asBoolean());
      assertEquals("bytes 0-3/10", head.at("/structuredContent/range").asString());
      assertTrue(head.at("/content/1/text").asString().contains("bytes=4-"), head.toString());

      JsonNode rest = call(localS3, "read_object", Map.of("bucket", "bucket", "key", "log.txt", "range", "bytes=4-"));
      assertEquals("456789", rest.at("/content/0/text").asString());
      JsonNode tail = call(localS3, "read_object", Map.of("bucket", "bucket", "key", "log.txt", "range", "bytes=-2"));
      assertEquals("89", tail.at("/content/0/text").asString());
    } finally {
      localS3.shutdown();
    }
  }

  @Test
  void readsBinaryContentAsBase64() throws Exception {
    LocalS3 localS3 = start();
    try {
      byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0, 1, 2};
      String data = Base64.getEncoder().encodeToString(png);
      JsonNode put = call(localS3, "put_object", Map.of("bucket", "bucket", "key", "chart.png", "content", data,
          "encoding", "base64"));
      assertEquals(png.length, put.at("/structuredContent/size").asLong());

      JsonNode read = call(localS3, "read_object", Map.of("bucket", "bucket", "key", "chart.png"));
      assertEquals("image", read.at("/content/1/type").asString());
      assertEquals("image/png", read.at("/content/1/mimeType").asString());
      assertEquals(data, read.at("/content/1/data").asString());
      assertEquals("base64", read.at("/structuredContent/encoding").asString());
    } finally {
      localS3.shutdown();
    }
  }

  @Test
  void presignsAUrlThatReadsTheObjectWithoutCredentials() throws Exception {
    String accessKeyId = "AKIAIOSFODNN7EXAMPLE";
    String secretAccessKey = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";
    LocalS3 localS3 = LocalS3.builder().port(-1).mode(LocalS3Mode.IN_MEMORY).buckets("bucket")
        .credentials(accessKeyId, secretAccessKey).features(f -> f.mcp(true)).build();
    localS3.start();
    try {
      Map<String, String> bearer = Map.of("Authorization", "Bearer " + secretAccessKey);
      call(localS3, bearer, "put_object", Map.of("bucket", "bucket", "key", "report.html", "content", "<p>ok</p>"));
      JsonNode presigned = call(localS3, bearer, "presign", Map.of("bucket", "bucket", "key", "report.html",
          "expires_in", 600));
      assertTrue(presigned.at("/structuredContent/signed").asBoolean());
      assertEquals(600, presigned.at("/structuredContent/expiresIn").asLong());

      String url = presigned.at("/structuredContent/url").asString();
      HttpResponse<String> object = client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
          HttpResponse.BodyHandlers.ofString());
      assertEquals(200, object.statusCode(), object.body());
      assertEquals("<p>ok</p>", object.body());
    } finally {
      localS3.shutdown();
    }
  }

  @Test
  void listsAndReadsTheObjectsAsResources() throws Exception {
    LocalS3 localS3 = LocalS3.builder().port(-1).mode(LocalS3Mode.IN_MEMORY).buckets("aaa", "bbb")
        .features(f -> f.mcp(true)).build();
    localS3.start();
    try {
      for (int i = 0; i < 120; i++) {
        call(localS3, "put_object", Map.of("bucket", "aaa", "key", String.format("k%03d.txt", i), "content", "a" + i));
      }
      call(localS3, "put_object", Map.of("bucket", "bbb", "key", "dir/my file.json", "content", "{}"));

      List<String> uris = new ArrayList<>();
      String cursor = null;
      int pages = 0;
      do {
        JsonNode page = rpc(localS3, "resources/list", cursor == null ? Map.of() : Map.of("cursor", cursor));
        page.at("/result/resources").forEach(resource -> uris.add(resource.get("uri").asString()));
        cursor = page.at("/result/nextCursor").isMissingNode() ? null : page.at("/result/nextCursor").asString();
        pages++;
      } while (cursor != null && pages < 10);
      assertEquals(121, uris.size());
      assertEquals(2, pages);
      assertEquals("s3://aaa/k000.txt", uris.getFirst());
      assertEquals("s3://bbb/dir/my%20file.json", uris.getLast());

      JsonNode read = rpc(localS3, "resources/read", Map.of("uri", "s3://bbb/dir/my%20file.json"));
      assertEquals("{}", read.at("/result/contents/0/text").asString());
      assertTrue(read.at("/result/contents/0/mimeType").asString().startsWith("application/json"));
      // An agent may write the key as it is.
      assertEquals("{}", rpc(localS3, "resources/read", Map.of("uri", "s3://bbb/dir/my file.json"))
          .at("/result/contents/0/text").asString());

      assertEquals(-32002, rpc(localS3, "resources/read", Map.of("uri", "s3://bbb/missing.txt"))
          .at("/error/code").asInt());
      assertEquals("s3://{bucket}/{key}", rpc(localS3, "resources/templates/list", Map.of())
          .at("/result/resourceTemplates/0/uriTemplate").asString());
    } finally {
      localS3.shutdown();
    }
  }

  @Test
  void wantsTheCredentialsOfTheServiceAndALocalOrigin() throws Exception {
    String accessKeyId = "AKIAIOSFODNN7EXAMPLE";
    String secretAccessKey = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";
    LocalS3 localS3 = LocalS3.builder().port(-1).mode(LocalS3Mode.IN_MEMORY)
        .credentials(accessKeyId, secretAccessKey).features(f -> f.mcp(true)).build();
    localS3.start();
    try {
      Map<String, Object> ping = request("ping", Map.of());
      HttpResponse<String> anonymous = post(localS3, ping, Map.of());
      assertEquals(401, anonymous.statusCode());
      assertTrue(anonymous.headers().firstValue("WWW-Authenticate").isPresent());
      assertEquals(401, post(localS3, ping, Map.of("Authorization", "Bearer wrong")).statusCode());

      assertEquals(200, post(localS3, ping, Map.of("Authorization", "Bearer " + secretAccessKey)).statusCode());
      String basic = "Basic " + Base64.getEncoder()
          .encodeToString((accessKeyId + ":" + secretAccessKey).getBytes(StandardCharsets.UTF_8));
      assertEquals(200, post(localS3, ping, Map.of("Authorization", basic)).statusCode());

      Map<String, String> bearer = Map.of("Authorization", "Bearer " + secretAccessKey);
      assertEquals(403, post(localS3, ping, Map.of("Authorization", "Bearer " + secretAccessKey,
          "Origin", "https://evil.example")).statusCode());
      assertEquals(200, post(localS3, ping, Map.of("Authorization", "Bearer " + secretAccessKey,
          "Origin", "http://localhost:5173")).statusCode());

      HttpResponse<String> stream = client.send(HttpRequest.newBuilder(
          URI.create("http://127.0.0.1:" + localS3.getPort() + "/_mcp")).header("Authorization",
          bearer.get("Authorization")).GET().build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(405, stream.statusCode());

      assertEquals(400, post(localS3, ping, Map.of("Authorization", "Bearer " + secretAccessKey,
          "MCP-Protocol-Version", "1999-01-01")).statusCode());
    } finally {
      localS3.shutdown();
    }
  }

  private static LocalS3 start() {
    LocalS3 localS3 = LocalS3.builder().port(-1).mode(LocalS3Mode.IN_MEMORY).buckets("bucket")
        .features(f -> f.mcp(true)).build();
    localS3.start();
    return localS3;
  }

  private JsonNode call(LocalS3 localS3, String tool, Map<String, Object> arguments) throws Exception {
    return call(localS3, Map.of(), tool, arguments);
  }

  private JsonNode call(LocalS3 localS3, Map<String, String> headers, String tool, Map<String, Object> arguments)
      throws Exception {
    JsonNode response = rpc(localS3, headers, "tools/call", Map.of("name", tool, "arguments", arguments));
    assertTrue(response.has("result"), response.toString());
    return response.get("result");
  }

  private JsonNode rpc(LocalS3 localS3, String method, Map<String, Object> params) throws Exception {
    return rpc(localS3, Map.of(), method, params);
  }

  private JsonNode rpc(LocalS3 localS3, Map<String, String> headers, String method, Map<String, Object> params)
      throws Exception {
    HttpResponse<String> response = post(localS3, request(method, params), headers);
    assertEquals(200, response.statusCode(), response.body());
    assertEquals("application/json", response.headers().firstValue("Content-Type").orElseThrow());
    return objectMapper.readTree(response.body());
  }

  private Map<String, Object> request(String method, Map<String, Object> params) {
    return Map.of("jsonrpc", "2.0", "id", ids.incrementAndGet(), "method", method, "params", params);
  }

  private HttpResponse<String> post(LocalS3 localS3, Map<String, Object> message, Map<String, String> headers)
      throws Exception {
    HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + localS3.getPort() + "/_mcp"))
        .header("Content-Type", "application/json")
        .header("Accept", "application/json, text/event-stream")
        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(message)));
    headers.forEach(request::header);
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

}
