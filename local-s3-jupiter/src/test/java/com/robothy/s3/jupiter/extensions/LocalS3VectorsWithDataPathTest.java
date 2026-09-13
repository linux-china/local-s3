package com.robothy.s3.jupiter.extensions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.jupiter.LocalS3;
import com.robothy.s3.jupiter.supplier.DataPathSupplier;
import com.robothy.s3.rest.bootstrap.LocalS3Mode;
import java.io.File;
import java.util.List;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.services.s3vectors.S3VectorsClient;
import software.amazon.awssdk.services.s3vectors.model.DataType;
import software.amazon.awssdk.services.s3vectors.model.DistanceMetric;
import software.amazon.awssdk.services.s3vectors.model.GetOutputVector;

/**
 * The vectors of a data path are the initial data of an {@code IN_MEMORY} service, like the objects of the path, and
 * the changes of the service don't reach the path.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LocalS3VectorsWithDataPathTest {

  @TempDir
  private static File tmpDir;

  @Order(1)
  @Test
  @LocalS3(mode = LocalS3Mode.PERSISTENCE, dataPathSupplier = DataPathSupplierImpl.class)
  void createVectorsInPersistenceMode(S3VectorsClient client) {
    client.createVectorBucket(b -> b.vectorBucketName("vector-bucket"));
    client.createIndex(b -> b.vectorBucketName("vector-bucket").indexName("index")
        .dimension(2).dataType(DataType.FLOAT32).distanceMetric(DistanceMetric.EUCLIDEAN));
    client.putVectors(b -> b.vectorBucketName("vector-bucket").indexName("index").vectors(
        v -> v.key("a").data(d -> d.float32(List.of(1.0f, 0.0f))),
        v -> v.key("b").data(d -> d.float32(List.of(0.0f, 1.0f)))));
  }

  @Order(2)
  @Test
  @LocalS3(mode = LocalS3Mode.IN_MEMORY, dataPathSupplier = DataPathSupplierImpl.class)
  void readAndChangeTheVectorsInMemory(S3VectorsClient client) {
    assertEquals(List.of("a", "b"), keys(client));
    assertEquals("a", client.queryVectors(b -> b.vectorBucketName("vector-bucket").indexName("index").topK(1)
        .queryVector(d -> d.float32(List.of(0.9f, 0.1f)))).vectors().get(0).key());

    client.deleteVectors(b -> b.vectorBucketName("vector-bucket").indexName("index").keys("b"));
    client.putVectors(b -> b.vectorBucketName("vector-bucket").indexName("index").vectors(
        v -> v.key("c").data(d -> d.float32(List.of(1.0f, 1.0f)))));
    assertEquals(List.of("a", "c"), keys(client));
  }

  @Order(3)
  @Test
  @LocalS3(mode = LocalS3Mode.PERSISTENCE, dataPathSupplier = DataPathSupplierImpl.class)
  void theChangesInMemoryDidNotReachTheDataPath(S3VectorsClient client) {
    assertEquals(List.of("a", "b"), keys(client));
    assertTrue(client.listVectorBuckets(b -> { }).vectorBuckets().size() == 1);
  }

  private static List<String> keys(S3VectorsClient client) {
    return client.getVectors(b -> b.vectorBucketName("vector-bucket").indexName("index").keys("a", "b", "c"))
        .vectors().stream().map(GetOutputVector::key).sorted().toList();
  }

  static class DataPathSupplierImpl implements DataPathSupplier {
    @Override
    public String get() {
      return tmpDir.getAbsolutePath();
    }
  }

}
