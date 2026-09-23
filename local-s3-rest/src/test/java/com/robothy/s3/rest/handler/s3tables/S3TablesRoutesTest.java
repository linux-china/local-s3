package com.robothy.s3.rest.handler.s3tables;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.netty.http.HttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.Assertions;

/**
 * The routing of the S3 Tables API: which operation each (method, path) of the API names, and how a request of the API
 * is told from an Amazon S3 one.
 *
 * <p>Both are worth pinning here rather than only through a client. The paths of this API <em>are</em> paths of Amazon
 * S3 — {@code PUT /buckets} is {@code CreateTableBucket} here and {@code CreateBucket} of a bucket named
 * {@code buckets} there — so a route that stopped matching wouldn't fail loudly: the request would be answered by the
 * S3 routes as something else entirely. And the ARN of a table bucket, which almost every path of the API carries,
 * holds a {@code /} that a client escapes, so a path that was decoded before it was split would lose the table bucket.
 *
 * <p>The list of operations is also compared with {@code docs/apis.md}, which is what a user decides by whether LocalS3
 * is enough for their tests.
 */
class S3TablesRoutesTest {

  private static final Path APIS = Path.of("../docs/apis.md");

  /**
   * An entry of a list of the documentation, e.g. {@code + CreateTable}.
   */
  private static final Pattern DOCUMENTED_OPERATION = Pattern.compile("^\\+ (\\w+)$", Pattern.MULTILINE);

  /**
   * The ARN of a table bucket as it arrives in a path: the AWS SDKs escape the {@code :} and the {@code /} of an ARN
   * that travels in a URI label, so it stays one segment.
   */
  private static final String ARN = "arn%3Aaws%3As3tables%3Aus-east-1%3A000000000000%3Abucket%2Froutes";

  private static final String DECODED_ARN = "arn:aws:s3tables:us-east-1:000000000000:bucket/routes";

  /**
   * Every operation of the API, by the request that names it. The value is the request as it arrives; the key is the
   * operation, without the {@code S3Tables} prefix that the statistics of the service record it under.
   */
  private static final Map<String, Request> ROUTES = routes();

  private static Map<String, Request> routes() {
    Map<String, Request> routes = new LinkedHashMap<>();
    // Table buckets.
    routes.put("CreateTableBucket", new Request(HttpMethod.PUT, "/buckets"));
    routes.put("ListTableBuckets", new Request(HttpMethod.GET, "/buckets"));
    routes.put("GetTableBucket", new Request(HttpMethod.GET, "/buckets/" + ARN));
    routes.put("DeleteTableBucket", new Request(HttpMethod.DELETE, "/buckets/" + ARN));
    routes.put("PutTableBucketEncryption", new Request(HttpMethod.PUT, "/buckets/" + ARN + "/encryption"));
    routes.put("GetTableBucketEncryption", new Request(HttpMethod.GET, "/buckets/" + ARN + "/encryption"));
    routes.put("DeleteTableBucketEncryption", new Request(HttpMethod.DELETE, "/buckets/" + ARN + "/encryption"));
    routes.put("PutTableBucketPolicy", new Request(HttpMethod.PUT, "/buckets/" + ARN + "/policy"));
    routes.put("GetTableBucketPolicy", new Request(HttpMethod.GET, "/buckets/" + ARN + "/policy"));
    routes.put("DeleteTableBucketPolicy", new Request(HttpMethod.DELETE, "/buckets/" + ARN + "/policy"));
    routes.put("PutTableBucketMetricsConfiguration", new Request(HttpMethod.PUT, "/buckets/" + ARN + "/metrics"));
    routes.put("GetTableBucketMetricsConfiguration", new Request(HttpMethod.GET, "/buckets/" + ARN + "/metrics"));
    routes.put("DeleteTableBucketMetricsConfiguration",
        new Request(HttpMethod.DELETE, "/buckets/" + ARN + "/metrics"));
    routes.put("GetTableBucketMaintenanceConfiguration",
        new Request(HttpMethod.GET, "/buckets/" + ARN + "/maintenance"));
    routes.put("PutTableBucketMaintenanceConfiguration",
        new Request(HttpMethod.PUT, "/buckets/" + ARN + "/maintenance/icebergUnreferencedFileRemoval"));
    routes.put("PutTableBucketStorageClass", new Request(HttpMethod.PUT, "/buckets/" + ARN + "/storage-class"));
    routes.put("GetTableBucketStorageClass", new Request(HttpMethod.GET, "/buckets/" + ARN + "/storage-class"));

    // Namespaces.
    routes.put("CreateNamespace", new Request(HttpMethod.PUT, "/namespaces/" + ARN));
    routes.put("ListNamespaces", new Request(HttpMethod.GET, "/namespaces/" + ARN));
    routes.put("GetNamespace", new Request(HttpMethod.GET, "/namespaces/" + ARN + "/db"));
    routes.put("DeleteNamespace", new Request(HttpMethod.DELETE, "/namespaces/" + ARN + "/db"));

    // Tables.
    routes.put("ListTables", new Request(HttpMethod.GET, "/tables/" + ARN));
    routes.put("CreateTable", new Request(HttpMethod.PUT, "/tables/" + ARN + "/db"));
    routes.put("DeleteTable", new Request(HttpMethod.DELETE, "/tables/" + ARN + "/db/orders"));
    routes.put("GetTable", new Request(HttpMethod.GET, "/get-table?tableBucketARN=" + ARN + "&namespace=db&name=t"));
    routes.put("RenameTable", new Request(HttpMethod.PUT, "/tables/" + ARN + "/db/orders/rename"));
    routes.put("GetTableMetadataLocation",
        new Request(HttpMethod.GET, "/tables/" + ARN + "/db/orders/metadata-location"));
    routes.put("UpdateTableMetadataLocation",
        new Request(HttpMethod.PUT, "/tables/" + ARN + "/db/orders/metadata-location"));
    routes.put("GetTableEncryption", new Request(HttpMethod.GET, "/tables/" + ARN + "/db/orders/encryption"));
    routes.put("GetTableStorageClass", new Request(HttpMethod.GET, "/tables/" + ARN + "/db/orders/storage-class"));
    routes.put("GetTableMaintenanceConfiguration",
        new Request(HttpMethod.GET, "/tables/" + ARN + "/db/orders/maintenance"));
    routes.put("PutTableMaintenanceConfiguration",
        new Request(HttpMethod.PUT, "/tables/" + ARN + "/db/orders/maintenance/icebergCompaction"));
    routes.put("GetTableMaintenanceJobStatus",
        new Request(HttpMethod.GET, "/tables/" + ARN + "/db/orders/maintenance-job-status"));
    routes.put("PutTablePolicy", new Request(HttpMethod.PUT, "/tables/" + ARN + "/db/orders/policy"));
    routes.put("GetTablePolicy", new Request(HttpMethod.GET, "/tables/" + ARN + "/db/orders/policy"));
    routes.put("DeleteTablePolicy", new Request(HttpMethod.DELETE, "/tables/" + ARN + "/db/orders/policy"));

    // Tags.
    routes.put("ListTagsForResource", new Request(HttpMethod.GET, "/tag/" + ARN));
    routes.put("TagResource", new Request(HttpMethod.POST, "/tag/" + ARN));
    routes.put("UntagResource", new Request(HttpMethod.DELETE, "/tag/" + ARN + "?tagKeys=team"));

    // Replication and record expiration, which carry their resource in the query rather than in the path.
    routes.put("PutTableBucketReplication",
        new Request(HttpMethod.PUT, "/table-bucket-replication?tableBucketARN=" + ARN));
    routes.put("GetTableBucketReplication",
        new Request(HttpMethod.GET, "/table-bucket-replication?tableBucketARN=" + ARN));
    routes.put("DeleteTableBucketReplication",
        new Request(HttpMethod.DELETE, "/table-bucket-replication?tableBucketARN=" + ARN));
    routes.put("PutTableReplication", new Request(HttpMethod.PUT, "/table-replication?tableArn=" + ARN));
    routes.put("GetTableReplication", new Request(HttpMethod.GET, "/table-replication?tableArn=" + ARN));
    routes.put("DeleteTableReplication", new Request(HttpMethod.DELETE, "/table-replication?tableArn=" + ARN));
    routes.put("GetTableReplicationStatus", new Request(HttpMethod.GET, "/replication-status?tableArn=" + ARN));
    routes.put("PutTableRecordExpirationConfiguration",
        new Request(HttpMethod.PUT, "/table-record-expiration?tableArn=" + ARN));
    routes.put("GetTableRecordExpirationConfiguration",
        new Request(HttpMethod.GET, "/table-record-expiration?tableArn=" + ARN));
    routes.put("GetTableRecordExpirationJobStatus",
        new Request(HttpMethod.GET, "/table-record-expiration-job-status?tableArn=" + ARN));
    return routes;
  }

  @Test
  void every_path_of_the_api_names_the_operation_it_is() {
    Assertions.assertAll(ROUTES.entrySet().stream().map(route -> (Executable) () ->
        assertEquals("S3Tables" + route.getKey(),
            S3TablesController.operation(route.getValue().toHttpRequest()),
            route.getValue().method() + " " + route.getValue().uri())));
  }

  @Test
  void a_path_that_names_no_operation_of_the_api_is_recorded_as_unknown() {
    assertEquals(S3TablesController.UNKNOWN_OPERATION,
        S3TablesController.operation(new Request(HttpMethod.GET, "/nothing-of-this-api").toHttpRequest()));
    // A method the resource doesn't answer, e.g. a POST of a table bucket.
    assertEquals(S3TablesController.UNKNOWN_OPERATION,
        S3TablesController.operation(new Request(HttpMethod.POST, "/buckets/" + ARN).toHttpRequest()));
    // One segment too many.
    assertEquals(S3TablesController.UNKNOWN_OPERATION,
        S3TablesController.operation(new Request(HttpMethod.GET, "/buckets/" + ARN + "/policy/extra").toHttpRequest()));
  }

  @Test
  void the_escaped_arn_of_a_path_stays_one_segment() {
    // The ARN holds a '/', so a path that was decoded before it was split would read 'bucket' as the namespace and
    // 'routes' as the table, and the operation would be a different one — or none.
    assertEquals("S3TablesGetTableBucket",
        S3TablesController.operation(new Request(HttpMethod.GET, "/buckets/" + ARN).toHttpRequest()));
    assertEquals("S3TablesGetNamespace",
        S3TablesController.operation(new Request(HttpMethod.GET, "/namespaces/" + ARN + "/db").toHttpRequest()));
    // The unescaped ARN is what a hand-written client might send, and it is not one segment: it names no operation.
    assertEquals(S3TablesController.UNKNOWN_OPERATION, S3TablesController.operation(
        new Request(HttpMethod.GET, "/namespaces/" + DECODED_ARN + "/db").toHttpRequest()));
  }

  @Test
  void the_path_prefix_of_an_unsigned_client_is_read_past() {
    assertEquals("S3TablesListTableBuckets",
        S3TablesController.operation(new Request(HttpMethod.GET, "/s3tables/buckets").toHttpRequest()));
    assertEquals("S3TablesCreateTable",
        S3TablesController.operation(new Request(HttpMethod.PUT, "/s3tables/tables/" + ARN + "/db").toHttpRequest()));
  }

  @Test
  void a_request_signed_for_s3tables_is_one_of_this_api() {
    HttpRequest signed = new Request(HttpMethod.PUT, "/buckets").toHttpRequest();
    signed.getHeaders().put("authorization", "AWS4-HMAC-SHA256"
        + " Credential=a-key/20260101/us-east-1/s3tables/aws4_request,"
        + " SignedHeaders=host;x-amz-date, Signature=" + "0".repeat(64));
    assertTrue(S3TablesController.isS3TablesRequest(signed));
  }

  @Test
  void a_request_signed_for_s3_is_not_one_of_this_api() {
    // The same path: PUT /buckets is CreateBucket of a bucket named 'buckets', and it must stay that.
    HttpRequest signed = new Request(HttpMethod.PUT, "/buckets").toHttpRequest();
    signed.getHeaders().put("authorization", "AWS4-HMAC-SHA256"
        + " Credential=a-key/20260101/us-east-1/s3/aws4_request,"
        + " SignedHeaders=host;x-amz-date, Signature=" + "0".repeat(64));
    assertFalse(S3TablesController.isS3TablesRequest(signed));
  }

  @Test
  void a_presigned_url_is_told_apart_by_its_credential_too() {
    HttpRequest presigned = new Request(HttpMethod.GET, "/buckets?X-Amz-Algorithm=AWS4-HMAC-SHA256"
        + "&X-Amz-Credential=a-key%2F20260101%2Fus-east-1%2Fs3tables%2Faws4_request").toHttpRequest();
    assertTrue(S3TablesController.isS3TablesRequest(presigned));
  }

  @Test
  void an_unsigned_request_is_one_of_this_api_only_under_its_path() {
    // Nothing in an unsigned request says which of the two APIs it means, so the bare path stays an S3 one.
    assertFalse(S3TablesController.isS3TablesRequest(new Request(HttpMethod.PUT, "/buckets").toHttpRequest()));
    assertTrue(S3TablesController.isS3TablesRequest(
        new Request(HttpMethod.PUT, "/s3tables/buckets").toHttpRequest()));
    assertTrue(S3TablesController.isS3TablesRequest(new Request(HttpMethod.GET, "/s3tables").toHttpRequest()));
    // A bucket whose name starts with 's3tables' is not the API.
    assertFalse(S3TablesController.isS3TablesRequest(
        new Request(HttpMethod.GET, "/s3tables-of-mine/key").toHttpRequest()));
  }

  @Test
  void the_documentation_lists_every_operation_of_the_api() throws IOException {
    assertEquals(new TreeSet<>(ROUTES.keySet()), documented("Supported Amazon S3 Tables APIs"),
        "The 'Supported Amazon S3 Tables APIs' list of docs/apis.md and the routed operations differ.");
  }

  /**
   * The operations that a {@code ## } section of the documentation lists by name, up to the next such section.
   */
  private static Set<String> documented(String sectionTitle) throws IOException {
    assertTrue(Files.exists(APIS), APIS.toAbsolutePath() + " doesn't exist.");
    String apis = Files.readString(APIS).replace("\r\n", "\n");
    String heading = "\n## " + sectionTitle + "\n";
    int start = apis.indexOf(heading);
    assertTrue(start >= 0, APIS + " has no '" + sectionTitle + "' section.");
    int end = apis.indexOf("\n## ", start + heading.length());
    end = end < 0 ? apis.length() : end;

    Set<String> operations = new LinkedHashSet<>();
    Matcher entries = DOCUMENTED_OPERATION.matcher(apis.substring(start + heading.length(), end));
    while (entries.find()) {
      operations.add(entries.group(1));
    }
    return new TreeSet<>(operations);
  }

  /**
   * A request as it arrives: its method and its URI, which is the path with the escapes a client sent and the query it
   * carried.
   */
  private record Request(HttpMethod method, String uri) {

    HttpRequest toHttpRequest() {
      int query = uri.indexOf('?');
      return HttpRequest.builder()
          .method(method)
          .uri(uri)
          .path(query < 0 ? uri : uri.substring(0, query))
          .build();
    }
  }

}
