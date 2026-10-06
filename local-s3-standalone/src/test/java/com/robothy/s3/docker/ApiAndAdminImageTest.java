package com.robothy.s3.docker;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.testcontainers.LocalS3Container;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner;
import software.amazon.awssdk.http.auth.spi.signer.SignedRequest;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.ExpirationStatus;
import software.amazon.awssdk.services.s3.model.LifecycleRule;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.Tag;
import software.amazon.awssdk.services.s3tables.S3TablesClient;
import software.amazon.awssdk.services.s3tables.model.CreateTableResponse;
import software.amazon.awssdk.services.s3tables.model.IcebergMetadata;
import software.amazon.awssdk.services.s3tables.model.IcebergSchema;
import software.amazon.awssdk.services.s3tables.model.OpenTableFormat;
import software.amazon.awssdk.services.s3tables.model.SchemaField;
import software.amazon.awssdk.services.s3tables.model.TableMetadata;
import software.amazon.awssdk.services.s3vectors.S3VectorsClient;
import software.amazon.awssdk.services.s3vectors.model.DataType;
import software.amazon.awssdk.services.s3vectors.model.DistanceMetric;
import software.amazon.awssdk.services.s3vectors.model.QueryVectorsResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The S3, S3 Vectors, S3 Tables, Iceberg REST, admin and console endpoints of the image, one family of models per test.
 *
 * <p>Aimed at the native image above all: what it breaks is what it can't see at build time, i.e. the reflection of
 * Jackson on the XML and JSON models and the resources looked up by name, which surface as a bare {@code 500} of an
 * endpoint that works on the JVM, e.g. the {@code List<record>} of the console, which Jackson resolves through the
 * array class of the record.
 */
@org.junit.jupiter.api.Tag(ImageUnderTest.JUNIT_TAG)
@Testcontainers
@Timeout(value = 2, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class ApiAndAdminImageTest {

  private static final String ACCESS_KEY = "native-access-key";

  private static final String SECRET_KEY = "native-secret-key";

  // The default PERSISTENCE mode, so that the H2 MVStore of the image is written to as well.
  @Container
  static final LocalS3Container LOCAL_S3 = new LocalS3Container(ImageUnderTest.TAG)
      .withCredentials(ACCESS_KEY, SECRET_KEY)
      .withIcebergCatalog(true)
      // A MissingReflectionRegistrationError is logged by the service and answered with a bare 500.
      .withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("local-s3-image")));

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

  private static S3Client s3;

  private static S3VectorsClient vectors;

  private static S3TablesClient tables;

  @BeforeAll
  static void createClients() {
    StaticCredentialsProvider credentials =
        StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY));
    s3 = S3Client.builder()
        .endpointOverride(LOCAL_S3.getEndpointUri())
        .region(Region.US_EAST_1)
        .credentialsProvider(credentials)
        .forcePathStyle(true)
        .build();
    vectors = S3VectorsClient.builder()
        .endpointOverride(LOCAL_S3.getEndpointUri())
        .region(Region.US_EAST_1)
        .credentialsProvider(credentials)
        .build();
    tables = S3TablesClient.builder()
        .endpointOverride(LOCAL_S3.getS3TablesEndpointUri())
        .region(Region.US_EAST_1)
        .credentialsProvider(credentials)
        .build();
  }

  @AfterAll
  static void closeClients() {
    s3.close();
    vectors.close();
    tables.close();
  }

  @Test
  void objects_are_written_listed_copied_tagged_and_deleted() {
    String bucket = "native-objects";
    s3.createBucket(request -> request.bucket(bucket));
    assertTrue(s3.listBuckets().buckets().stream().anyMatch(b -> b.name().equals(bucket)));

    s3.putObject(request -> request.bucket(bucket).key("dir/a.txt").contentType("text/plain")
        .metadata(Map.of("owner", "native")), RequestBody.fromString("hello native"));
    s3.putObject(request -> request.bucket(bucket).key("dir/b.txt"), RequestBody.fromString("b"));

    ResponseBytes<GetObjectResponse> object = s3.getObjectAsBytes(request -> request.bucket(bucket).key("dir/a.txt"));
    assertEquals("hello native", object.asUtf8String());
    assertEquals("text/plain", object.response().contentType());
    assertEquals("native", object.response().metadata().get("owner"));
    assertEquals("ello", s3.getObjectAsBytes(request -> request.bucket(bucket).key("dir/a.txt").range("bytes=1-4"))
        .asUtf8String());

    s3.copyObject(request -> request.sourceBucket(bucket).sourceKey("dir/a.txt")
        .destinationBucket(bucket).destinationKey("copy/a.txt"));
    ListObjectsV2Response listed = s3.listObjectsV2(request -> request.bucket(bucket).delimiter("/"));
    assertEquals(List.of("copy/", "dir/"), listed.commonPrefixes().stream().map(p -> p.prefix()).toList());
    assertEquals(List.of("dir/a.txt", "dir/b.txt"), s3.listObjectsV2(request -> request.bucket(bucket)
        .prefix("dir/")).contents().stream().map(S3Object::key).toList());

    s3.putObjectTagging(request -> request.bucket(bucket).key("dir/a.txt")
        .tagging(tagging -> tagging.tagSet(Tag.builder().key("env").value("test").build())));
    assertEquals(List.of(Tag.builder().key("env").value("test").build()),
        s3.getObjectTagging(request -> request.bucket(bucket).key("dir/a.txt")).tagSet());

    s3.deleteObjects(request -> request.bucket(bucket).delete(delete -> delete.objects(
        ObjectIdentifier.builder().key("dir/a.txt").build(),
        ObjectIdentifier.builder().key("dir/b.txt").build(),
        ObjectIdentifier.builder().key("copy/a.txt").build())));
    assertTrue(s3.listObjectsV2(request -> request.bucket(bucket)).contents().isEmpty());
    s3.deleteBucket(request -> request.bucket(bucket));
  }

  @Test
  void errors_are_answered_with_the_error_document_of_s3() {
    String bucket = "native-errors";
    s3.createBucket(request -> request.bucket(bucket));
    NoSuchKeyException missing = assertThrows(NoSuchKeyException.class,
        () -> s3.getObject(request -> request.bucket(bucket).key("missing")));
    assertEquals("NoSuchKey", missing.awsErrorDetails().errorCode());

    S3Client wrongSecret = S3Client.builder()
        .endpointOverride(LOCAL_S3.getEndpointUri())
        .region(Region.US_EAST_1)
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, "wrong")))
        .forcePathStyle(true)
        .build();
    try (wrongSecret) {
      S3Exception refused = assertThrows(S3Exception.class, wrongSecret::listBuckets);
      assertEquals(403, refused.statusCode());
      assertEquals("SignatureDoesNotMatch", refused.awsErrorDetails().errorCode());
    }
  }

  @Test
  void a_multipart_upload_is_completed() {
    String bucket = "native-multipart";
    s3.createBucket(request -> request.bucket(bucket));
    String uploadId = s3.createMultipartUpload(request -> request.bucket(bucket).key("big.bin")).uploadId();

    byte[] first = new byte[5 * 1024 * 1024];
    java.util.Arrays.fill(first, (byte) 'a');
    byte[] second = "the last part".getBytes(StandardCharsets.UTF_8);
    List<CompletedPart> parts = new ArrayList<>();
    byte[][] contents = {first, second};
    for (int i = 0; i < contents.length; i++) {
      int partNumber = i + 1;
      byte[] content = contents[i];
      String etag = s3.uploadPart(request -> request.bucket(bucket).key("big.bin").uploadId(uploadId)
          .partNumber(partNumber), RequestBody.fromBytes(content)).eTag();
      parts.add(CompletedPart.builder().partNumber(partNumber).eTag(etag).build());
    }
    assertEquals(2, s3.listParts(request -> request.bucket(bucket).key("big.bin").uploadId(uploadId)).parts().size());

    String etag = s3.completeMultipartUpload(request -> request.bucket(bucket).key("big.bin").uploadId(uploadId)
        .multipartUpload(upload -> upload.parts(parts))).eTag();
    assertTrue(etag.endsWith("-2\""), etag);

    byte[] expected = new byte[first.length + second.length];
    System.arraycopy(first, 0, expected, 0, first.length);
    System.arraycopy(second, 0, expected, first.length, second.length);
    assertArrayEquals(expected, s3.getObjectAsBytes(request -> request.bucket(bucket).key("big.bin")).asByteArray());
  }

  @Test
  void a_versioned_bucket_keeps_every_version() {
    String bucket = "native-versioned";
    s3.createBucket(request -> request.bucket(bucket));
    s3.putBucketVersioning(request -> request.bucket(bucket)
        .versioningConfiguration(config -> config.status(BucketVersioningStatus.ENABLED)));
    assertEquals(BucketVersioningStatus.ENABLED, s3.getBucketVersioning(request -> request.bucket(bucket)).status());

    String v1 = s3.putObject(request -> request.bucket(bucket).key("k"), RequestBody.fromString("one")).versionId();
    s3.putObject(request -> request.bucket(bucket).key("k"), RequestBody.fromString("two"));
    s3.deleteObject(request -> request.bucket(bucket).key("k"));

    ListObjectVersionsResponse versions = s3.listObjectVersions(request -> request.bucket(bucket));
    assertEquals(2, versions.versions().size());
    assertEquals(1, versions.deleteMarkers().size());
    assertEquals("one", s3.getObjectAsBytes(request -> request.bucket(bucket).key("k").versionId(v1))
        .asUtf8String());
  }

  @Test
  void vectors_are_queried_by_distance() {
    String bucket = "native-vectors";
    vectors.createVectorBucket(request -> request.vectorBucketName(bucket));
    vectors.createIndex(request -> request.vectorBucketName(bucket).indexName("idx").dimension(3)
        .dataType(DataType.FLOAT32).distanceMetric(DistanceMetric.EUCLIDEAN));
    vectors.putVectors(request -> request.vectorBucketName(bucket).indexName("idx").vectors(
        v -> v.key("near").data(d -> d.float32(List.of(1f, 1f, 1f)))
            .metadata(Document.mapBuilder().putString("name", "near").build()),
        v -> v.key("far").data(d -> d.float32(List.of(9f, 9f, 9f)))));

    QueryVectorsResponse response = vectors.queryVectors(request -> request.vectorBucketName(bucket)
        .indexName("idx").queryVector(q -> q.float32(List.of(1f, 1f, 1.1f))).topK(2)
        .returnMetadata(true).returnDistance(true));
    assertEquals(List.of("near", "far"), response.vectors().stream().map(v -> v.key()).toList());
    assertEquals("near", response.vectors().get(0).metadata().asMap().get("name").asString());
    assertEquals(List.of("idx"), vectors.listIndexes(request -> request.vectorBucketName(bucket)).indexes().stream()
        .map(index -> index.indexName()).toList());
  }

  @Test
  void a_table_bucket_is_served_through_s3_tables_and_the_iceberg_rest_catalog() throws Exception {
    String arn = tables.createTableBucket(request -> request.name("native-tables")).arn();
    tables.createNamespace(request -> request.tableBucketARN(arn).namespace("db"));
    CreateTableResponse created = tables.createTable(request -> request.tableBucketARN(arn).namespace("db")
        .name("orders").format(OpenTableFormat.ICEBERG).metadata(TableMetadata.builder()
            .iceberg(IcebergMetadata.builder().schema(IcebergSchema.builder()
                .fields(SchemaField.builder().name("id").type("long").required(true).build())
                .build()).build())
            .build()));
    assertNotNull(created.versionToken());
    assertTrue(tables.getTable(request -> request.tableBucketARN(arn).namespace("db").name("orders"))
        .metadataLocation().endsWith(".metadata.json"));

    URI config = URI.create(LOCAL_S3.getEndpoint() + "/iceberg/v1/config?warehouse="
        + URLEncoder.encode(arn, StandardCharsets.UTF_8));
    HttpResponse<String> response = signed("GET", config);
    assertEquals(200, response.statusCode(), response.body());
    assertTrue(response.body().contains("\"prefix\":\"native-tables\""), response.body());
  }

  @Test
  void the_admin_endpoints_answer_json() throws Exception {
    String bucket = "native-admin";
    s3.createBucket(request -> request.bucket(bucket));
    s3.putObject(request -> request.bucket(bucket).key("old.txt"), RequestBody.fromString("old"));

    HttpResponse<String> stats = signed("GET", "/_admin/stats");
    assertEquals(200, stats.statusCode(), stats.body());
    assertTrue(JSON.readTree(stats.body()).has("operations"), stats.body());

    HttpResponse<String> requests = signed("GET", "/_admin/requests?limit=5");
    assertEquals(200, requests.statusCode(), requests.body());
    assertFalse(JSON.readTree(requests.body()).get("requests").isEmpty(), requests.body());

    HttpResponse<String> snippets = signed("GET", "/_admin/snippets?bucket=" + bucket + "&key=old.txt");
    assertEquals(200, snippets.statusCode(), snippets.body());
    assertFalse(JSON.readTree(snippets.body()).get("snippets").isEmpty(), snippets.body());

    HttpResponse<String> duckdb = signed("GET", "/_admin/snippets/duckdb?bucket=" + bucket);
    assertEquals(200, duckdb.statusCode(), duckdb.body());
    assertTrue(duckdb.body().contains("CREATE"), duckdb.body());

    // The persistent service of this class refuses a reset rather than deleting its data path.
    HttpResponse<String> reset = signed("POST", "/_admin/reset");
    assertEquals(409, reset.statusCode(), reset.body());
  }

  @Test
  void the_admin_lifecycle_endpoint_applies_the_rules_of_a_bucket() throws Exception {
    String bucket = "native-lifecycle";
    s3.createBucket(request -> request.bucket(bucket));
    s3.putBucketLifecycleConfiguration(request -> request.bucket(bucket).lifecycleConfiguration(config -> config
        .rules(LifecycleRule.builder().id("expire").status(ExpirationStatus.ENABLED)
            .filter(filter -> filter.prefix(""))
            .expiration(expiration -> expiration.days(1))
            .build())));
    s3.putObject(request -> request.bucket(bucket).key("expiring.txt"), RequestBody.fromString("bye"));

    HttpResponse<String> response = signed("POST", "/_admin/lifecycle?bucket=" + bucket + "&days=31");
    assertEquals(200, response.statusCode(), response.body());
    assertFalse(JSON.readTree(response.body()).get("actions").isEmpty(), response.body());
    assertTrue(s3.listObjectsV2(request -> request.bucket(bucket)).contents().isEmpty());
  }

  @Test
  void an_in_memory_service_is_reset() throws Exception {
    try (LocalS3Container inMemory = new LocalS3Container(ImageUnderTest.TAG)
        .withMode(LocalS3Container.Mode.IN_MEMORY)
        .withBuckets("seed")) {
      inMemory.start();
      HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(
          URI.create(inMemory.getEndpoint() + "/_admin/reset")).POST(HttpRequest.BodyPublishers.noBody()).build(),
          HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode(), response.body());
      assertEquals("RESET", JSON.readTree(response.body()).get("status").asString());
    }
  }

  @Test
  void the_console_page_and_its_reads_are_served() throws Exception {
    HttpResponse<String> unauthorized = HTTP.send(HttpRequest.newBuilder(
        URI.create(LOCAL_S3.getEndpoint() + "/_admin/ui")).GET().build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(401, unauthorized.statusCode(), unauthorized.body());

    HttpResponse<String> page = console("GET", "/_admin/ui", null);
    assertEquals(200, page.statusCode(), page.body());
    assertTrue(page.headers().firstValue("Content-Type").orElse("").startsWith("text/html"));

    String bucket = "native-console-read";
    s3.createBucket(request -> request.bucket(bucket));
    s3.putObject(request -> request.bucket(bucket).key("docs/readme.md").contentType("text/markdown"),
        RequestBody.fromString("# hello"));

    HttpResponse<String> buckets = console("GET", "/_admin/ui/buckets", null);
    assertEquals(200, buckets.statusCode(), buckets.body());
    assertTrue(names(JSON.readTree(buckets.body()).get("buckets"), "name").contains(bucket), buckets.body());

    HttpResponse<String> root = console("GET", "/_admin/ui/objects?bucket=" + bucket, null);
    assertEquals(200, root.statusCode(), root.body());
    assertEquals("docs/", JSON.readTree(root.body()).get("prefixes").get(0).asString(), root.body());

    HttpResponse<String> docs = console("GET", "/_admin/ui/objects?bucket=" + bucket + "&prefix=docs/", null);
    assertEquals(200, docs.statusCode(), docs.body());
    assertEquals(List.of("docs/readme.md"), names(JSON.readTree(docs.body()).get("objects"), "key"), docs.body());

    HttpResponse<String> object = console("GET", "/_admin/ui/object?bucket=" + bucket + "&key=docs/readme.md", null);
    assertEquals(200, object.statusCode(), object.body());
    assertEquals("# hello", object.body());

    HttpResponse<String> snippets = console("GET", "/_admin/ui/snippets?bucket=" + bucket, null);
    assertEquals(200, snippets.statusCode(), snippets.body());
    assertFalse(JSON.readTree(snippets.body()).get("snippets").isEmpty(), snippets.body());

    HttpResponse<String> presigned = console("GET",
        "/_admin/ui/presign?bucket=" + bucket + "&key=docs/readme.md&expires=600", null);
    assertEquals(200, presigned.statusCode(), presigned.body());
    JsonNode url = JSON.readTree(presigned.body());
    assertTrue(url.get("signed").asBoolean(), presigned.body());
    HttpResponse<String> shared = HTTP.send(HttpRequest.newBuilder(URI.create(url.get("url").asString())).GET()
        .build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(200, shared.statusCode(), shared.body());
    assertEquals("# hello", shared.body());
  }

  @Test
  void the_console_creates_uploads_and_deletes() throws Exception {
    String bucket = "native-console-write";
    HttpResponse<String> refused = console("PUT", "/_admin/ui/bucket?bucket=" + bucket, null, false);
    assertEquals(403, refused.statusCode(), refused.body());

    HttpResponse<String> created = console("PUT", "/_admin/ui/bucket?bucket=" + bucket, null);
    assertEquals(200, created.statusCode(), created.body());
    assertEquals(bucket, JSON.readTree(created.body()).get("name").asString(), created.body());

    HttpResponse<String> uploaded = console("PUT", "/_admin/ui/object?bucket=" + bucket + "&key=a.txt", "small");
    assertEquals(200, uploaded.statusCode(), uploaded.body());
    assertEquals(5, JSON.readTree(uploaded.body()).get("size").asLong(), uploaded.body());

    String multipart = "/_admin/ui/multipart?bucket=" + bucket + "&key=big.txt";
    HttpResponse<String> started = console("POST", multipart, null);
    assertEquals(200, started.statusCode(), started.body());
    String uploadId = JSON.readTree(started.body()).get("uploadId").asString();
    HttpResponse<String> part = console("PUT", multipart + "&uploadId=" + uploadId + "&partNumber=1", "in parts");
    assertEquals(200, part.statusCode(), part.body());
    String etag = JSON.readTree(part.body()).get("etag").asString();
    HttpResponse<String> completed = console("POST", "/_admin/ui/multipart/complete?bucket=" + bucket
        + "&key=big.txt&uploadId=" + uploadId, JSON.writeValueAsString(
        Map.of("parts", List.of(Map.of("partNumber", 1, "etag", etag)))));
    assertEquals(200, completed.statusCode(), completed.body());
    assertEquals("in parts", s3.getObjectAsBytes(request -> request.bucket(bucket).key("big.txt")).asUtf8String());

    HttpResponse<String> abandoned = console("POST", "/_admin/ui/multipart?bucket=" + bucket + "&key=gone.txt", null);
    String abandonedId = JSON.readTree(abandoned.body()).get("uploadId").asString();
    HttpResponse<String> aborted = console("DELETE", "/_admin/ui/multipart?bucket=" + bucket
        + "&key=gone.txt&uploadId=" + abandonedId, null);
    assertEquals(200, aborted.statusCode(), aborted.body());

    HttpResponse<String> deleted = console("DELETE", "/_admin/ui/object?bucket=" + bucket + "&key=a.txt", null);
    assertEquals(200, deleted.statusCode(), deleted.body());
    assertFalse(JSON.readTree(deleted.body()).get("deleteMarker").asBoolean(), deleted.body());
    assertEquals(List.of("big.txt"), s3.listObjectsV2(request -> request.bucket(bucket)).contents().stream()
        .map(S3Object::key).toList());
  }

  private static List<String> names(JsonNode array, String field) {
    List<String> names = new ArrayList<>();
    array.forEach(node -> names.add(node.get(field).asString()));
    return names;
  }

  private static HttpResponse<String> console(String method, String path, String body) throws Exception {
    return console(method, path, body, true);
  }

  /**
   * A request of the console, with the HTTP Basic authentication a browser sends and, unless told otherwise, the
   * header that the page adds to the requests that change the data.
   */
  private static HttpResponse<String> console(String method, String path, String body, boolean consoleHeader)
      throws Exception {
    HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(LOCAL_S3.getEndpoint() + path))
        .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
        .header("Authorization", "Basic " + Base64.getEncoder()
            .encodeToString((ACCESS_KEY + ":" + SECRET_KEY).getBytes(StandardCharsets.UTF_8)));
    if (consoleHeader) {
      builder.header("X-LocalS3-Console", "true");
    }
    return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<String> signed(String method, String path) throws Exception {
    return signed(method, URI.create(LOCAL_S3.getEndpoint() + path));
  }

  private static HttpResponse<String> signed(String method, URI uri) throws Exception {
    SignedRequest signed = AwsV4HttpSigner.create().sign(request -> request
        .identity(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY))
        .request(SdkHttpFullRequest.builder()
            .method(SdkHttpMethod.fromValue(method))
            .uri(uri)
            .appendHeader("Host", uri.getHost() + ":" + uri.getPort())
            .build())
        .putProperty(AwsV4HttpSigner.SERVICE_SIGNING_NAME, "s3")
        .putProperty(AwsV4HttpSigner.REGION_NAME, Region.US_EAST_1.id()));
    HttpRequest.Builder builder = HttpRequest.newBuilder(uri).method(method, HttpRequest.BodyPublishers.noBody());
    signed.request().headers().forEach((name, values) -> {
      if (!"Host".equalsIgnoreCase(name) && !"Content-Length".equalsIgnoreCase(name)) {
        values.forEach(value -> builder.header(name, value));
      }
    });
    return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

}
