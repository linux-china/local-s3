package com.robothy.s3.rest.handler;

import com.robothy.s3.core.iceberg.IcebergCatalogService;
import com.robothy.s3.core.iceberg.IcebergIdentifier;
import com.robothy.s3.core.iceberg.IcebergJson;
import com.robothy.s3.core.s3tables.S3TablesService;
import com.robothy.s3.core.service.s3vectors.S3VectorsService;
import com.robothy.s3.datatypes.s3vectors.VectorIndex;
import com.robothy.s3.datatypes.s3vectors.response.GetOutputVector;
import com.robothy.s3.datatypes.s3vectors.response.IndexSummary;
import com.robothy.s3.datatypes.s3vectors.response.ListIndexesResponse;
import com.robothy.s3.datatypes.s3vectors.response.ListVectorBucketsResponse;
import com.robothy.s3.datatypes.s3vectors.response.ListVectorsResponse;
import com.robothy.s3.datatypes.s3vectors.response.VectorBucketSummary;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * What the console reads of the Iceberg catalogs and of the vector buckets of a service, for the {@code Tables} and
 * {@code Vectors} views of the page, see {@code docs/deployment.md#console}. It only reads: the catalogs and the
 * vector buckets are changed by the clients that own them, through their APIs.
 */
final class ConsoleExplorer {

  /**
   * The largest page that the S3 Vectors API answers, which the console asks for to list a bucket in few requests.
   */
  private static final int VECTOR_PAGE_SIZE = 500;

  /**
   * The vectors of a page of the {@code Vectors} view: few enough to render, the page asks for the next ones.
   */
  private static final int VECTORS_PAGE_SIZE = 100;

  /**
   * The catalog that the service serves at {@code /iceberg}; {@code null} if it serves none.
   */
  private final @Nullable IcebergCatalogService icebergCatalog;

  /**
   * The table buckets, each of which is a catalog of its own; {@code null} if the service serves none.
   */
  private final @Nullable S3TablesService s3Tables;

  private final @Nullable S3VectorsService s3Vectors;

  ConsoleExplorer(@Nullable IcebergCatalogService icebergCatalog, @Nullable S3TablesService s3Tables,
                  @Nullable S3VectorsService s3Vectors) {
    this.icebergCatalog = icebergCatalog;
    this.s3Tables = s3Tables;
    this.s3Vectors = s3Vectors;
  }

  boolean hasTables() {
    return icebergCatalog != null || s3Tables != null;
  }

  boolean hasVectors() {
    return s3Vectors != null;
  }

  /*
   * Tables.
   */

  /**
   * The catalogs: the one of {@code /iceberg}, and one per table bucket.
   */
  ObjectNode catalogs() {
    ObjectNode result = IcebergJson.newObject();
    ArrayNode catalogs = IcebergJson.newArray();
    if (icebergCatalog != null) {
      ObjectNode catalog = IcebergJson.newObject();
      catalog.put("type", "iceberg");
      catalog.put("name", "Iceberg REST catalog");
      catalog.put("warehouse", icebergCatalog.warehouse());
      catalogs.add(catalog);
    }
    if (s3Tables != null) {
      String token = null;
      do {
        ObjectNode page = s3Tables.listTableBuckets(null, token, null, null);
        for (JsonNode bucket : page.path("tableBuckets")) {
          ObjectNode catalog = IcebergJson.newObject();
          catalog.put("type", "s3tables");
          catalog.put("name", bucket.path("name").asString());
          catalog.put("tableBucket", bucket.path("name").asString());
          catalog.put("arn", bucket.path("arn").asString());
          catalog.put("createdAt", bucket.path("createdAt").asString());
          catalogs.add(catalog);
        }
        token = page.path("continuationToken").asString(null);
      } while (token != null);
    }
    result.set("catalogs", catalogs);
    return result;
  }

  /**
   * A namespace: the namespaces directly under it, and, unless it is the root, its properties, tables and views.
   *
   * @param tableBucket the table bucket whose catalog to read; {@code null} for the catalog of {@code /iceberg}.
   * @param namespace the levels of the namespace, separated like a path segment of the REST API.
   */
  ObjectNode namespace(@Nullable String tableBucket, @Nullable String namespace) {
    IcebergCatalogService catalog = catalog(tableBucket);
    List<String> levels = IcebergIdentifier.parseNamespace(namespace);
    ObjectNode result = IcebergJson.newObject();
    result.set("namespace", array(levels));
    result.set("namespaces", catalog.listNamespaces(levels).path("namespaces"));
    if (!levels.isEmpty()) {
      result.set("properties", catalog.loadNamespace(levels).path("properties"));
      result.set("tables", names(catalog.listTables(levels, false)));
      result.set("views", names(catalog.listTables(levels, true)));
    }
    return result;
  }

  /**
   * A table, with its metadata as the catalog loads it: the schemas, the partition specs and the snapshots.
   */
  ObjectNode table(@Nullable String tableBucket, @Nullable String namespace, String name) {
    IcebergIdentifier identifier = IcebergIdentifier.of(IcebergIdentifier.parseNamespace(namespace), name);
    ObjectNode result = catalog(tableBucket).loadTable(identifier, false);
    snapshotIdsAsText(result);
    result.set("namespace", array(identifier.namespace()));
    result.put("name", name);
    return result;
  }

  /**
   * Answer the IDs of the snapshots as strings: they are random 64-bit numbers, which a number of JavaScript, the
   * page reads them as, rounds.
   */
  private static void snapshotIdsAsText(JsonNode node) {
    if (node instanceof ObjectNode object) {
      for (String name : List.copyOf(object.propertyNames())) {
        JsonNode value = object.get(name);
        if (name.endsWith("snapshot-id") && value.isIntegralNumber()) {
          object.put(name, value.asString());
        } else {
          snapshotIdsAsText(value);
        }
      }
    } else if (node instanceof ArrayNode array) {
      array.forEach(ConsoleExplorer::snapshotIdsAsText);
    }
  }

  private IcebergCatalogService catalog(@Nullable String tableBucket) {
    if (tableBucket == null || tableBucket.isEmpty()) {
      if (icebergCatalog == null) {
        throw new NoSuchElementException("The service serves no Iceberg REST catalog.");
      }
      return icebergCatalog;
    }
    IcebergCatalogService catalog = s3Tables == null ? null : s3Tables.catalogOfWarehouse(tableBucket);
    if (catalog == null) {
      throw new NoSuchElementException("The table bucket " + tableBucket + " doesn't exist.");
    }
    return catalog;
  }

  private static ArrayNode names(ObjectNode identifiers) {
    ArrayNode names = IcebergJson.newArray();
    identifiers.path("identifiers").forEach(identifier -> names.add(identifier.path("name").asString()));
    return names;
  }

  private static ArrayNode array(List<String> values) {
    ArrayNode array = IcebergJson.newArray();
    values.forEach(array::add);
    return array;
  }

  /*
   * Vectors.
   */

  List<VectorBucketSummary> vectorBuckets() {
    S3VectorsService vectors = vectors();
    List<VectorBucketSummary> buckets = new ArrayList<>();
    String token = null;
    do {
      ListVectorBucketsResponse page = vectors.listVectorBuckets(VECTOR_PAGE_SIZE, token, null);
      buckets.addAll(page.vectorBuckets());
      token = page.nextToken();
    } while (token != null);
    return buckets;
  }

  /**
   * The indexes of a vector bucket, each with what {@code GetIndex} answers: its dimension, its distance metric, the
   * metadata keys it doesn't filter by, and the number of its vectors.
   */
  List<VectorIndex> vectorIndexes(String vectorBucket) {
    S3VectorsService vectors = vectors();
    List<VectorIndex> indexes = new ArrayList<>();
    String token = null;
    do {
      ListIndexesResponse page = vectors.listIndexes(vectorBucket, VECTOR_PAGE_SIZE, token, null);
      for (IndexSummary summary : page.indexes()) {
        indexes.add(vectors.getIndex(vectorBucket, summary.indexName()).index());
      }
      token = page.nextToken();
    } while (token != null);
    return indexes;
  }

  /**
   * A page of the vectors of an index, with their metadata but without their data, which is read one vector at a
   * time: a page of vectors of a thousand dimensions would be large to send for a listing.
   */
  ListVectorsResponse vectors(String vectorBucket, String index, @Nullable String nextToken) {
    return vectors().listVectors(vectorBucket, index, VECTORS_PAGE_SIZE, nextToken, false, true, null, null);
  }

  GetOutputVector vector(String vectorBucket, String index, String key) {
    List<GetOutputVector> found = vectors().getVectors(vectorBucket, index, List.of(key), true, true).vectors();
    if (found == null || found.isEmpty()) {
      throw new NoSuchElementException("The index " + index + " has no vector " + key + ".");
    }
    return found.getFirst();
  }

  private S3VectorsService vectors() {
    if (s3Vectors == null) {
      throw new NoSuchElementException("The service serves no S3 Vectors API.");
    }
    return s3Vectors;
  }

}
