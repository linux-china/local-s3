package com.robothy.s3.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.core.event.S3Change;
import com.robothy.s3.core.event.S3ChangeType;
import com.robothy.s3.rest.bootstrap.LocalS3Mode;
import java.io.ByteArrayOutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The built-in console, {@code GET /_admin/ui}: the page itself, the buckets and the objects it lists, the bucket it
 * creates, the content it previews, the uploads and deletes it makes, and the credentials it asks a browser for.
 */
class ConsoleTest {

  /**
   * The value of the header that the page sends with a write; any value does, the header itself is the point.
   */
  private static final String CONSOLE_HEADER = "1";

  private final HttpClient client = HttpClient.newHttpClient();

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void servesThePageAndListsTheDataOfTheService() throws Exception {
    LocalS3 localS3 = LocalS3.builder().port(-1).mode(LocalS3Mode.IN_MEMORY).buckets("bucket").build();
    localS3.start();
    try {
      assertEquals(200, send(localS3, "PUT", "/bucket/a.txt", null, "Hello").statusCode());
      assertEquals(200, send(localS3, "PUT", "/bucket/reports/b.txt", null, "World!").statusCode());

      HttpResponse<String> page = send(localS3, "GET", "/_admin/ui", null, null);
      assertEquals(200, page.statusCode());
      assertEquals("text/html; charset=utf-8", page.headers().firstValue("Content-Type").orElseThrow());
      assertTrue(page.body().contains("LocalS3 console"), "The page is served from the classpath.");
      assertEquals(200, send(localS3, "GET", "/_admin/ui/", null, null).statusCode(), "With a trailing slash too.");

      JsonNode buckets = json(send(localS3, "GET", "/_admin/ui/buckets", null, null)).get("buckets");
      assertEquals(1, buckets.size());
      assertEquals("bucket", buckets.get(0).get("name").asText());
      assertFalse(buckets.get(0).get("creationDate").asText().isEmpty());

      // The root of the bucket: the object directly under it, and the directory of the other one.
      JsonNode root = json(send(localS3, "GET", "/_admin/ui/objects?bucket=bucket", null, null));
      assertEquals("bucket", root.get("bucket").asText());
      assertEquals(1, root.get("prefixes").size());
      assertEquals("reports/", root.get("prefixes").get(0).asText());
      assertEquals(1, root.get("objects").size());
      assertEquals("a.txt", root.get("objects").get(0).get("key").asText());
      assertEquals(5, root.get("objects").get(0).get("size").asLong());
      assertFalse(root.get("truncated").asBoolean());

      JsonNode reports = json(send(localS3, "GET", "/_admin/ui/objects?bucket=bucket&prefix=reports%2F", null, null));
      assertEquals(0, reports.get("prefixes").size());
      assertEquals("reports/b.txt", reports.get("objects").get(0).get("key").asText());

      // The console browsing isn't traffic of the service under test, so it isn't recorded.
      JsonNode stats = json(send(localS3, "GET", "/_admin/stats", null, null));
      assertFalse(stats.get("operations").has("ConsoleListObjects"), "The console doesn't record itself: " + stats);
    } finally {
      localS3.shutdown();
    }
  }

  @Test
  void previewsAndDownloadsTheContentOfAnObject() throws Exception {
    LocalS3 localS3 = LocalS3.builder().port(-1).mode(LocalS3Mode.IN_MEMORY).buckets("bucket").build();
    localS3.start();
    try {
      send(localS3, "PUT", "/bucket/reports/report.html", null, "<h1>Done</h1>");

      HttpResponse<String> preview =
          send(localS3, "GET", "/_admin/ui/object?bucket=bucket&key=reports%2Freport.html", null, null);
      assertEquals(200, preview.statusCode());
      assertEquals("<h1>Done</h1>", preview.body());
      // Stored without a content type, it is guessed from the extension, so that a browser renders it.
      assertEquals("text/html; charset=utf-8", preview.headers().firstValue("Content-Type").orElseThrow());
      assertTrue(preview.headers().firstValue("Content-Disposition").orElseThrow()
          .startsWith("inline; filename=\"report.html\""));
      // The content of a bucket is the user's, and never the console's own scripts.
      assertTrue(preview.headers().firstValue("Content-Security-Policy").orElseThrow().startsWith("sandbox"));

      HttpResponse<String> download =
          send(localS3, "GET", "/_admin/ui/object?bucket=bucket&key=reports%2Freport.html&download=1", null, null);
      assertTrue(download.headers().firstValue("Content-Disposition").orElseThrow().startsWith("attachment;"));
    } finally {
      localS3.shutdown();
    }
  }

  /**
   * What a file dropped on the page is uploaded with, and what the {@code Delete} of a row does: the object is
   * stored and deleted through the same services the S3 API uses, so a client reads and misses it in the same way.
   */
  @Test
  void uploadsAndDeletesAnObject() throws Exception {
    List<S3Change> changes = new CopyOnWriteArrayList<>();
    LocalS3 localS3 = LocalS3.builder().port(-1).mode(LocalS3Mode.IN_MEMORY).buckets("bucket")
        .changeListener(changes::add).build();
    localS3.start();
    try {
      HttpResponse<String> upload = send(localS3, "PUT", "/_admin/ui/object?bucket=bucket&key=reports%2Fq1.csv",
          null, "a,b\n1,2\n", CONSOLE_HEADER);
      assertEquals(200, upload.statusCode(), upload.body());
      assertEquals("reports/q1.csv", json(upload).get("key").asText());
      assertEquals(8, json(upload).get("size").asLong());

      // The S3 API sees the object, with the content type of its extension, which the browser didn't send.
      HttpResponse<String> read = send(localS3, "GET", "/bucket/reports/q1.csv", null, null);
      assertEquals(200, read.statusCode());
      assertEquals("a,b\n1,2\n", read.body());
      assertEquals("text/csv; charset=utf-8", read.headers().firstValue("Content-Type").orElseThrow());
      // And the console lists it under its prefix.
      JsonNode listing = json(send(localS3, "GET", "/_admin/ui/objects?bucket=bucket&prefix=reports%2F", null, null));
      assertEquals("reports/q1.csv", listing.get("objects").get(0).get("key").asText());

      // An empty file, which a dropped folder may well hold, is an object like any other.
      HttpResponse<String> empty = send(localS3, "PUT", "/_admin/ui/object?bucket=bucket&key=reports%2Fempty.txt",
          null, "", CONSOLE_HEADER);
      assertEquals(200, empty.statusCode(), empty.body());
      assertEquals(0, json(empty).get("size").asLong());
      assertEquals(200, send(localS3, "GET", "/bucket/reports/empty.txt", null, null).statusCode());
      assertEquals(200, send(localS3, "DELETE", "/_admin/ui/object?bucket=bucket&key=reports%2Fempty.txt", null,
          null, CONSOLE_HEADER).statusCode());

      HttpResponse<String> delete = send(localS3, "DELETE", "/_admin/ui/object?bucket=bucket&key=reports%2Fq1.csv",
          null, null, CONSOLE_HEADER);
      assertEquals(200, delete.statusCode(), delete.body());
      assertEquals("reports/q1.csv", json(delete).get("key").asText());
      assertEquals(404, send(localS3, "GET", "/bucket/reports/q1.csv", null, null).statusCode());

      // What the console changes is a change of the service like any other, so a listener is told of it, with the
      // operation that a client would have made it with. The default bucket was created before all of them.
      List<S3Change> objectChanges = changes.stream().filter(change -> change.key() != null).toList();
      assertEquals(List.of(S3ChangeType.OBJECT_CREATED, S3ChangeType.OBJECT_CREATED, S3ChangeType.OBJECT_DELETED,
          S3ChangeType.OBJECT_DELETED), objectChanges.stream().map(S3Change::type).toList(), changes.toString());
      assertEquals(List.of("PutObject", "PutObject", "DeleteObject", "DeleteObject"),
          objectChanges.stream().map(S3Change::operation).toList());
      assertEquals("reports/q1.csv", objectChanges.get(0).key());
    } finally {
      localS3.shutdown();
    }
  }

  /**
   * The bucket that {@code + NEW} creates: an ordinary bucket of the service, in the default region, whose name
   * follows the naming rules of Amazon S3 like {@code CreateBucket} requires.
   */
  @Test
  void createsABucket() throws Exception {
    List<S3Change> changes = new CopyOnWriteArrayList<>();
    LocalS3 localS3 = LocalS3.builder().port(-1).mode(LocalS3Mode.IN_MEMORY).changeListener(changes::add).build();
    localS3.start();
    try {
      HttpResponse<String> created =
          send(localS3, "PUT", "/_admin/ui/bucket?bucket=my-bucket", null, null, CONSOLE_HEADER);
      assertEquals(200, created.statusCode(), created.body());
      assertEquals("my-bucket", json(created).get("name").asText());
      assertEquals("us-east-1", json(created).get("region").asText());

      // The S3 API and the console both see it, and an object can be stored in it right away.
      assertEquals(200, send(localS3, "HEAD", "/my-bucket", null, null).statusCode());
      assertEquals("my-bucket", json(send(localS3, "GET", "/_admin/ui/buckets", null, null))
          .get("buckets").get(0).get("name").asText());
      assertEquals(200, send(localS3, "PUT", "/_admin/ui/object?bucket=my-bucket&key=a.txt", null, "Hello",
          CONSOLE_HEADER).statusCode());
      assertEquals(List.of("CreateBucket", "PutObject"), changes.stream().map(S3Change::operation).toList());

      // A name that is taken, one that breaks the naming rules, and no name at all.
      HttpResponse<String> taken = send(localS3, "PUT", "/_admin/ui/bucket?bucket=my-bucket", null, null,
          CONSOLE_HEADER);
      assertEquals(409, taken.statusCode(), taken.body());
      assertEquals("BucketAlreadyExists", json(taken).get("code").asText());
      HttpResponse<String> invalid = send(localS3, "PUT", "/_admin/ui/bucket?bucket=My_Bucket", null, null,
          CONSOLE_HEADER);
      assertEquals(400, invalid.statusCode(), invalid.body());
      assertEquals("InvalidBucketName", json(invalid).get("code").asText());
      assertEquals(400, send(localS3, "PUT", "/_admin/ui/bucket", null, null, CONSOLE_HEADER).statusCode());

      // The console creates a bucket and deletes none, and a write still has to come from the console.
      assertEquals(404, send(localS3, "DELETE", "/_admin/ui/bucket?bucket=my-bucket", null, null, CONSOLE_HEADER)
          .statusCode());
      assertEquals(403, send(localS3, "PUT", "/_admin/ui/bucket?bucket=other", null, null, null).statusCode());
    } finally {
      localS3.shutdown();
    }
  }

  /**
   * A write must carry the header that only the console sends, so that a page the browser has open on another origin
   * can't write into the buckets through it. The content type the browser read off the file is kept.
   */
  @Test
  void refusesAWriteThatIsNotFromTheConsole() throws Exception {
    LocalS3 localS3 = LocalS3.builder().port(-1).mode(LocalS3Mode.IN_MEMORY).buckets("bucket").build();
    localS3.start();
    try {
      HttpResponse<String> forged =
          send(localS3, "PUT", "/_admin/ui/object?bucket=bucket&key=a.txt", null, "Hello", null);
      assertEquals(403, forged.statusCode());
      assertEquals("AccessDenied", json(forged).get("code").asText());
      assertEquals(403, send(localS3, "DELETE", "/_admin/ui/object?bucket=bucket&key=a.txt", null, null, null)
          .statusCode());
      assertEquals(404, send(localS3, "GET", "/bucket/a.txt", null, null).statusCode(), "Nothing was stored.");

      // A write of a path that isn't the object endpoint is no endpoint of the console at all.
      assertEquals(404, send(localS3, "PUT", "/_admin/ui/buckets", null, "{}", CONSOLE_HEADER).statusCode());

      // The type a browser reads off the file is what the object is stored with.
      HttpResponse<String> typed = sendTyped(localS3, "/_admin/ui/object?bucket=bucket&key=note",
          "text/plain; charset=utf-8", "Hello");
      assertEquals(200, typed.statusCode(), typed.body());
      assertEquals("text/plain; charset=utf-8",
          send(localS3, "GET", "/bucket/note", null, null).headers().firstValue("Content-Type").orElseThrow());
    } finally {
      localS3.shutdown();
    }
  }

  /**
   * A service with credentials wants them for a write as well, and a write of the console is never answered
   * without a signature by the S3 routes.
   */
  @Test
  void asksForTheCredentialsOfTheServiceBeforeWriting() throws Exception {
    String accessKeyId = "AKIAIOSFODNN7EXAMPLE";
    String secretAccessKey = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";
    LocalS3 localS3 = LocalS3.builder().port(-1).mode(LocalS3Mode.IN_MEMORY).buckets("bucket")
        .credentials(accessKeyId, secretAccessKey).build();
    localS3.start();
    try {
      assertEquals(401, send(localS3, "PUT", "/_admin/ui/object?bucket=bucket&key=a.txt", null, "Hello",
          CONSOLE_HEADER).statusCode());
      assertEquals(401, send(localS3, "DELETE", "/_admin/ui/object?bucket=bucket&key=a.txt", null, null,
          CONSOLE_HEADER).statusCode());

      String credentials = basic(accessKeyId, secretAccessKey);
      assertEquals(200, send(localS3, "PUT", "/_admin/ui/object?bucket=bucket&key=a.txt", credentials, "Hello",
          CONSOLE_HEADER).statusCode());
      assertEquals(200, send(localS3, "DELETE", "/_admin/ui/object?bucket=bucket&key=a.txt", credentials, null,
          CONSOLE_HEADER).statusCode());
    } finally {
      localS3.shutdown();
    }
  }

  /**
   * What a request that names no bucket, an unknown bucket or an unknown key is answered with: the console reads
   * JSON, so its errors are JSON as well, rather than the XML of the S3 API.
   */
  @Test
  void answersItsErrorsAsJson() throws Exception {
    LocalS3 localS3 = LocalS3.builder().port(-1).mode(LocalS3Mode.IN_MEMORY).buckets("bucket").build();
    localS3.start();
    try {
      HttpResponse<String> noBucket = send(localS3, "GET", "/_admin/ui/objects", null, null);
      assertEquals(400, noBucket.statusCode());
      assertEquals("InvalidRequest", json(noBucket).get("code").asText());

      HttpResponse<String> unknownBucket = send(localS3, "GET", "/_admin/ui/objects?bucket=nope", null, null);
      assertEquals(404, unknownBucket.statusCode());
      assertEquals("NoSuchBucket", json(unknownBucket).get("code").asText());

      HttpResponse<String> unknownKey =
          send(localS3, "GET", "/_admin/ui/object?bucket=bucket&key=nope.txt", null, null);
      assertEquals(404, unknownKey.statusCode());
      assertEquals("NoSuchKey", json(unknownKey).get("code").asText());

      assertEquals(404, send(localS3, "GET", "/_admin/ui/nope", null, null).statusCode());
    } finally {
      localS3.shutdown();
    }
  }

  /**
   * A browser can't sign a request with AWS Signature Version 4, so a service that requires credentials asks the
   * console for them as HTTP Basic authentication: the access key ID and the secret access key.
   */
  @Test
  void asksABrowserForTheCredentialsOfTheService() throws Exception {
    String accessKeyId = "AKIAIOSFODNN7EXAMPLE";
    String secretAccessKey = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";
    LocalS3 localS3 = LocalS3.builder().port(-1).mode(LocalS3Mode.IN_MEMORY)
        .credentials(accessKeyId, secretAccessKey).build();
    localS3.start();
    try {
      HttpResponse<String> anonymous = send(localS3, "GET", "/_admin/ui", null, null);
      assertEquals(401, anonymous.statusCode());
      assertTrue(anonymous.headers().firstValue("WWW-Authenticate").orElseThrow().startsWith("Basic realm="),
          "A browser shows its credentials prompt.");
      assertEquals(401, send(localS3, "GET", "/_admin/ui/buckets", basic(accessKeyId, "wrong"), null).statusCode());
      assertEquals(401, send(localS3, "GET", "/_admin/ui/buckets", "Basic not-base64", null).statusCode());

      String credentials = basic(accessKeyId, secretAccessKey);
      assertEquals(200, send(localS3, "GET", "/_admin/ui", credentials, null).statusCode());
      assertEquals(200, send(localS3, "GET", "/_admin/ui/buckets", credentials, null).statusCode());
      // The S3 API of the same service keeps asking for a signature.
      assertEquals(403, send(localS3, "GET", "/", null, null).statusCode());
      // A virtual-hosted request asks for the object _admin/ui of a bucket, so it is verified like every object
      // read rather than answered by the console: the console opens no unsigned way into a bucket.
      String virtualHosted = exchange(localS3, "GET /_admin/ui HTTP/1.1\r\nHost: my-bucket.localhost\r\n"
          + "Connection: close\r\n\r\n");
      assertTrue(virtualHosted.startsWith("HTTP/1.1 403 "), virtualHosted);
    } finally {
      localS3.shutdown();
    }
  }

  /**
   * Send a raw HTTP request on a connection of its own, e.g. one that names a {@code Host} that the HTTP client of
   * the JDK refuses to set, and read the whole response.
   */
  private static String exchange(LocalS3 localS3, String request) throws Exception {
    try (Socket socket = new Socket("127.0.0.1", localS3.getPort())) {
      socket.setSoTimeout(30_000);
      socket.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
      socket.getOutputStream().flush();
      ByteArrayOutputStream response = new ByteArrayOutputStream();
      socket.getInputStream().transferTo(response);
      return response.toString(StandardCharsets.UTF_8);
    }
  }

  private static String basic(String user, String password) {
    return "Basic " + Base64.getEncoder()
        .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
  }

  private JsonNode json(HttpResponse<String> response) {
    assertEquals("application/json", response.headers().firstValue("Content-Type").orElseThrow(), response.body());
    return objectMapper.readTree(response.body());
  }

  private HttpResponse<String> send(LocalS3 localS3, String method, String path, String authorization, String body)
      throws Exception {
    return send(localS3, method, path, authorization, body, null);
  }

  /**
   * @param consoleHeader the value of the header that the page sends with a write; {@code null} to send none.
   */
  private HttpResponse<String> send(LocalS3 localS3, String method, String path, String authorization, String body,
                                    String consoleHeader) throws Exception {
    HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + localS3.getPort() + path))
        .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
    if (authorization != null) {
      request.header("Authorization", authorization);
    }
    if (consoleHeader != null) {
      request.header("X-LocalS3-Console", consoleHeader);
    }
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  /**
   * Upload with the content type that a browser read off the file.
   */
  private HttpResponse<String> sendTyped(LocalS3 localS3, String path, String contentType, String body)
      throws Exception {
    HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + localS3.getPort() + path))
        .header("X-LocalS3-Console", CONSOLE_HEADER)
        .header("Content-Type", contentType)
        .PUT(HttpRequest.BodyPublishers.ofString(body))
        .build();
    return client.send(request, HttpResponse.BodyHandlers.ofString());
  }

}
