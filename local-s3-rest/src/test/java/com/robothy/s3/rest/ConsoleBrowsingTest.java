package com.robothy.s3.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.rest.bootstrap.LocalS3Mode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * What the console shows beyond the listing of a bucket: the metadata, the tags and the versions of an object, the
 * namespaces, tables and snapshots of the Iceberg catalogs, and the indexes and vectors of the vector buckets.
 */
class ConsoleBrowsingTest {

  private static final String SCHEMA = """
      {"type":"struct","schema-id":0,"fields":[{"id":1,"name":"id","required":true,"type":"long"},
      {"id":2,"name":"day","required":false,"type":"date"}]}""";

  /**
   * Larger than the largest integer a number of JavaScript holds exactly, like the random IDs Iceberg gives snapshots.
   */
  private static final String SNAPSHOT_ID = "9007199254740993";

  private final HttpClient client = HttpClient.newHttpClient();

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void answersTheMetadataTagsAndVersionsOfAnObject() throws Exception {
    LocalS3 localS3 = LocalS3.builder().port(-1).mode(LocalS3Mode.IN_MEMORY).buckets("bucket").build();
    localS3.start();
    try {
      assertEquals(200, send(localS3, "PUT", "/bucket?versioning",
          "<VersioningConfiguration><Status>Enabled</Status></VersioningConfiguration>").statusCode());
      assertEquals(200, send(localS3, "PUT", "/bucket/report.csv", "a,b\n", "Content-Type", "text/csv",
          "x-amz-meta-owner", "agent-7", "Cache-Control", "no-cache", "x-amz-tagging", "stage=draft").statusCode());
      assertEquals(200, send(localS3, "PUT", "/bucket/report.csv", "a,b\n1,2\n", "x-amz-tagging",
          "stage=final&team=data").statusCode());
      // A key that the key is a prefix of, whose versions aren't the versions of the key.
      assertEquals(200, send(localS3, "PUT", "/bucket/report.csv.bak", "old").statusCode());

      JsonNode versions = json(send(localS3, "GET", "/_admin/ui/versions?bucket=bucket&key=report.csv", null));
      assertEquals("Enabled", versions.get("versioning").asString());
      assertEquals(2, versions.get("versions").size(), versions.toString());
      JsonNode latest = versions.get("versions").get(0);
      JsonNode first = versions.get("versions").get(1);
      assertTrue(latest.get("latest").asBoolean());
      assertEquals(8, latest.get("size").asLong());
      assertFalse(first.get("latest").asBoolean());
      assertEquals(4, first.get("size").asLong());

      JsonNode current = json(send(localS3, "GET", "/_admin/ui/metadata?bucket=bucket&key=report.csv", null));
      assertEquals(latest.get("versionId").asString(), current.get("versionId").asString());
      assertEquals(8, current.get("size").asLong());
      assertEquals("STANDARD", current.get("storageClass").asString());
      assertEquals(2, current.get("tags").size());
      assertEquals("stage", current.get("tags").get(0).get("key").asString());
      assertEquals("final", current.get("tags").get(0).get("value").asString());

      // The first version keeps its own metadata and tags, and its content is previewed by its version ID.
      String firstId = first.get("versionId").asString();
      JsonNode old = json(send(localS3, "GET",
          "/_admin/ui/metadata?bucket=bucket&key=report.csv&versionId=" + firstId, null));
      assertEquals("text/csv", old.get("contentType").asString());
      assertEquals("agent-7", old.get("userMetadata").get("owner").asString());
      assertEquals("no-cache", old.get("headers").get("Cache-Control").asString());
      assertEquals("draft", old.get("tags").get(0).get("value").asString());
      assertEquals("a,b\n", send(localS3, "GET",
          "/_admin/ui/object?bucket=bucket&key=report.csv&versionId=" + firstId, null).body());

      // A delete puts a delete marker, which is listed and described as one.
      send(localS3, "DELETE", "/bucket/report.csv", null);
      JsonNode afterDelete = json(send(localS3, "GET", "/_admin/ui/versions?bucket=bucket&key=report.csv", null));
      assertEquals(3, afterDelete.get("versions").size());
      JsonNode marker = afterDelete.get("versions").get(0);
      assertTrue(marker.get("deleteMarker").asBoolean());
      JsonNode markerMetadata = json(send(localS3, "GET", "/_admin/ui/metadata?bucket=bucket&key=report.csv"
          + "&versionId=" + marker.get("versionId").asString(), null));
      assertTrue(markerMetadata.get("deleteMarker").asBoolean());

      // An object of a bucket that was never versioned has the one version, null.
      send(localS3, "PUT", "/bucket2", null);
      send(localS3, "PUT", "/bucket2/a.txt", "a");
      JsonNode unversioned = json(send(localS3, "GET", "/_admin/ui/versions?bucket=bucket2&key=a.txt", null));
      assertEquals("Unversioned", unversioned.get("versioning").asString());
      assertEquals(1, unversioned.get("versions").size());
    } finally {
      localS3.shutdown();
    }
  }

  @Test
  void walksTheNamespacesTablesAndSnapshotsOfTheCatalogs() throws Exception {
    LocalS3 localS3 = LocalS3.builder().port(-1).mode(LocalS3Mode.IN_MEMORY).icebergCatalog(true).build();
    localS3.start();
    try {
      JsonNode features = json(send(localS3, "GET", "/_admin/ui/features", null));
      assertTrue(features.get("tables").asBoolean());
      assertTrue(features.get("vectors").asBoolean());

      assertEquals(200, post(localS3, "/iceberg/v1/namespaces", """
          {"namespace":["db"],"properties":{"owner":"etl"}}""").statusCode());
      assertEquals(200, post(localS3, "/iceberg/v1/namespaces", """
          {"namespace":["db","staging"]}""").statusCode());
      assertEquals(200, post(localS3, "/iceberg/v1/namespaces/db/tables", """
          {"name":"orders","schema":%s}""".formatted(SCHEMA)).statusCode());
      HttpResponse<String> commit = post(localS3, "/iceberg/v1/namespaces/db/tables/orders", """
          {"requirements":[],"updates":[
            {"action":"add-snapshot","snapshot":{"snapshot-id":%1$s,"timestamp-ms":1700000000000,
             "manifest-list":"s3://warehouse/db/orders/metadata/snap-1.avro","schema-id":0,
             "summary":{"operation":"append","added-records":"3","total-records":"3"}}},
            {"action":"set-snapshot-ref","ref-name":"main","type":"branch","snapshot-id":%1$s}]}"""
          .formatted(SNAPSHOT_ID));
      assertEquals(200, commit.statusCode(), commit.body());
      assertEquals(200, send(localS3, "PUT", "/s3tables/buckets", "{\"name\":\"analytics\"}",
          "Content-Type", "application/json").statusCode());

      JsonNode catalogs = json(send(localS3, "GET", "/_admin/ui/catalogs", null)).get("catalogs");
      assertEquals(2, catalogs.size(), catalogs.toString());
      assertEquals("iceberg", catalogs.get(0).get("type").asString());
      assertEquals("s3://warehouse", catalogs.get(0).get("warehouse").asString());
      assertEquals("analytics", catalogs.get(1).get("tableBucket").asString());

      JsonNode root = json(send(localS3, "GET", "/_admin/ui/namespace", null));
      assertEquals("db", root.get("namespaces").get(0).get(0).asString());
      assertFalse(root.has("tables"), "The root namespace holds no tables.");

      JsonNode db = json(send(localS3, "GET", "/_admin/ui/namespace?namespace=db", null));
      assertEquals("staging", db.get("namespaces").get(0).get(1).asString());
      assertEquals("orders", db.get("tables").get(0).asString());
      assertEquals("etl", db.get("properties").get("owner").asString());

      JsonNode table = json(send(localS3, "GET", "/_admin/ui/table?namespace=db&table=orders", null));
      assertTrue(table.get("metadata-location").asString().startsWith("s3://warehouse/"));
      JsonNode metadata = table.get("metadata");
      // Answered as text, which the page reads without rounding.
      assertEquals(SNAPSHOT_ID, metadata.get("current-snapshot-id").asString());
      assertTrue(metadata.get("current-snapshot-id").isString());
      assertEquals(SNAPSHOT_ID, metadata.get("snapshots").get(0).get("snapshot-id").asString());
      assertEquals("append", metadata.get("snapshots").get(0).get("summary").get("operation").asString());

      // The catalog of a table bucket, which has no namespaces yet.
      JsonNode analytics = json(send(localS3, "GET", "/_admin/ui/namespace?tableBucket=analytics", null));
      assertEquals(0, analytics.get("namespaces").size());

      HttpResponse<String> missing = send(localS3, "GET", "/_admin/ui/table?namespace=db&table=nope", null);
      assertEquals(404, missing.statusCode());
      assertEquals(404, send(localS3, "GET", "/_admin/ui/namespace?tableBucket=nope", null).statusCode());
    } finally {
      localS3.shutdown();
    }
  }

  @Test
  void listsTheIndexesAndVectorsOfAVectorBucket() throws Exception {
    LocalS3 localS3 = LocalS3.builder().port(-1).mode(LocalS3Mode.IN_MEMORY).build();
    localS3.start();
    try {
      assertEquals(200, post(localS3, "/CreateVectorBucket", "{\"vectorBucketName\":\"memories\"}").statusCode());
      assertEquals(200, post(localS3, "/CreateIndex", """
          {"vectorBucketName":"memories","indexName":"notes","dataType":"float32","dimension":3,
           "distanceMetric":"cosine"}""").statusCode());
      assertEquals(200, post(localS3, "/PutVectors", """
          {"vectorBucketName":"memories","indexName":"notes","vectors":[
            {"key":"a","data":{"float32":[1,0,0]},"metadata":{"topic":"cats"}},
            {"key":"b","data":{"float32":[0,1,0]}}]}""").statusCode());

      JsonNode buckets = json(send(localS3, "GET", "/_admin/ui/vector-buckets", null)).get("vectorBuckets");
      assertEquals("memories", buckets.get(0).get("vectorBucketName").asString());

      JsonNode indexes = json(send(localS3, "GET", "/_admin/ui/vector-indexes?vectorBucket=memories", null))
          .get("indexes");
      assertEquals("notes", indexes.get(0).get("indexName").asString());
      assertEquals(3, indexes.get(0).get("dimension").asInt());
      assertEquals(2, indexes.get(0).get("vectorCount").asLong());

      JsonNode vectors = json(send(localS3, "GET", "/_admin/ui/vectors?vectorBucket=memories&index=notes", null))
          .get("vectors");
      assertEquals(2, vectors.size());
      assertEquals("cats", vectors.get(0).get("metadata").get("topic").asString());
      assertTrue(vectors.get(0).path("data").isMissingNode() || vectors.get(0).get("data").isNull(),
          "A listing leaves the data out: " + vectors);

      JsonNode vector = json(send(localS3, "GET", "/_admin/ui/vector?vectorBucket=memories&index=notes&key=a", null));
      assertEquals(3, vector.get("data").get("float32").size());

      assertEquals(404, send(localS3, "GET", "/_admin/ui/vector-indexes?vectorBucket=nope", null).statusCode());
    } finally {
      localS3.shutdown();
    }
  }

  @Test
  void hidesTheViewsOfAServiceThatServesNeitherTablesNorVectors() throws Exception {
    LocalS3 localS3 = LocalS3.builder().port(-1).mode(LocalS3Mode.IN_MEMORY)
        .features(features -> features.vector(false).s3Tables(false)).build();
    localS3.start();
    try {
      JsonNode features = json(send(localS3, "GET", "/_admin/ui/features", null));
      assertFalse(features.get("tables").asBoolean());
      assertFalse(features.get("vectors").asBoolean());
      HttpResponse<String> vectors = send(localS3, "GET", "/_admin/ui/vector-buckets", null);
      assertEquals(404, vectors.statusCode());
      assertEquals("NotFound", json(vectors).get("code").asString());
    } finally {
      localS3.shutdown();
    }
  }

  private JsonNode json(HttpResponse<String> response) {
    assertEquals("application/json", response.headers().firstValue("Content-Type").orElse(""), response.body());
    return objectMapper.readTree(response.body());
  }

  private HttpResponse<String> post(LocalS3 localS3, String path, String body) throws Exception {
    return send(localS3, "POST", path, body, "Content-Type", "application/json");
  }

  /**
   * @param headers the names and the values of the headers to send, in turns.
   */
  private HttpResponse<String> send(LocalS3 localS3, String method, String path, String body, String... headers)
      throws Exception {
    HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + localS3.getPort() + path))
        .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
    for (int i = 0; i < headers.length; i += 2) {
      request.header(headers[i], headers[i + 1]);
    }
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

}
