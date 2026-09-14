package com.robothy.s3.core.service.manager.vectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.core.service.s3vectors.S3VectorsService;
import com.robothy.s3.datatypes.s3vectors.DistanceMetric;
import com.robothy.s3.datatypes.s3vectors.VectorDataType;
import com.robothy.s3.datatypes.s3vectors.request.PutInputVector;
import com.robothy.s3.datatypes.s3vectors.response.GetOutputVector;
import com.robothy.s3.datatypes.s3vectors.response.QueryOutputVector;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An {@code IN_MEMORY} vectors service starts from the vectors of its data path, like an {@code IN_MEMORY} S3 service
 * starts from the objects of its data path, and never changes the path.
 */
class InMemoryLocalS3VectorsManagerTest {

  private static final String BUCKET = "vector-bucket";

  private static final String INDEX = "index";

  @TempDir
  Path dataPath;

  @Test
  void startsFromTheVectorsOfTheDataPath() {
    S3VectorsService persistent = LocalS3VectorsManager.createFileSystem(dataPath).s3VectorsService();
    persistent.createVectorBucket(BUCKET, null);
    persistent.createIndex(BUCKET, INDEX, VectorDataType.FLOAT32, 2, DistanceMetric.EUCLIDEAN, null);
    persistent.putVectors(BUCKET, INDEX, List.of(vector("a", 1.0f, 0.0f), vector("b", 0.0f, 1.0f)));

    S3VectorsService inMemory = LocalS3VectorsManager.createInMemory(dataPath).s3VectorsService();

    assertEquals(1, inMemory.listVectorBuckets(null, null, null).getVectorBuckets().size());
    Map<String, float[]> vectors = getVectors(inMemory, "a", "b");
    assertArrayEquals(new float[] {1.0f, 0.0f}, vectors.get("a"));
    assertArrayEquals(new float[] {0.0f, 1.0f}, vectors.get("b"));
    List<QueryOutputVector> nearest = inMemory.queryVectors(BUCKET, INDEX, data(0.9f, 0.1f), 1, true, false, null)
        .getVectors();
    assertEquals("a", nearest.get(0).getKey());
  }

  @Test
  void neverChangesTheDataPath() {
    S3VectorsService persistent = LocalS3VectorsManager.createFileSystem(dataPath).s3VectorsService();
    persistent.createVectorBucket(BUCKET, null);
    persistent.createIndex(BUCKET, INDEX, VectorDataType.FLOAT32, 2, DistanceMetric.EUCLIDEAN, null);
    persistent.putVectors(BUCKET, INDEX, List.of(vector("a", 1.0f, 0.0f), vector("b", 0.0f, 1.0f)));
    Map<String, byte[]> before = snapshot(dataPath);

    S3VectorsService inMemory = LocalS3VectorsManager.createInMemory(dataPath).s3VectorsService();
    inMemory.putVectors(BUCKET, INDEX, List.of(vector("c", 1.0f, 1.0f), vector("a", 2.0f, 2.0f)));
    inMemory.deleteVectors(BUCKET, INDEX, List.of("b"));
    inMemory.createVectorBucket("another-bucket", null);

    Map<String, float[]> changed = getVectors(inMemory, "a", "b", "c");
    assertArrayEquals(new float[] {2.0f, 2.0f}, changed.get("a"));
    assertFalse(changed.containsKey("b"));
    assertArrayEquals(new float[] {1.0f, 1.0f}, changed.get("c"));
    assertEquals(2, inMemory.listVectorBuckets(null, null, null).getVectorBuckets().size());

    assertEquals(before.keySet(), snapshot(dataPath).keySet(), "No file of the data path is added or removed.");
    snapshot(dataPath).forEach((file, content) -> assertArrayEquals(before.get(file), content, file));
    Map<String, float[]> original = getVectors(LocalS3VectorsManager.createFileSystem(dataPath).s3VectorsService(),
        "a", "b", "c");
    assertArrayEquals(new float[] {1.0f, 0.0f}, original.get("a"));
    assertArrayEquals(new float[] {0.0f, 1.0f}, original.get("b"));
    assertFalse(original.containsKey("c"));
  }

  @Test
  void startsEmptyWithoutADataPathAndKeepsItsVectors() {
    Path missing = dataPath.resolve("missing");
    LocalS3VectorsManager manager = LocalS3VectorsManager.createInMemory(missing);
    assertFalse(Files.exists(missing));
    assertTrue(manager.s3VectorsService().listVectorBuckets(null, null, null).getVectorBuckets().isEmpty());

    // A service that is started again serves the vectors it held.
    manager.s3VectorsService().createVectorBucket(BUCKET, null);
    assertSame(manager.s3VectorsService(), manager.s3VectorsService());
    assertEquals(1, manager.s3VectorsService().listVectorBuckets(null, null, null).getVectorBuckets().size());

    assertTrue(LocalS3VectorsManager.createInMemory().s3VectorsService().listVectorBuckets(null, null, null)
        .getVectorBuckets().isEmpty());
  }

  @Test
  void aVectorBucketOfTheDataPathCanBeDeletedInMemory() {
    S3VectorsService persistent = LocalS3VectorsManager.createFileSystem(dataPath).s3VectorsService();
    persistent.createVectorBucket(BUCKET, null);

    S3VectorsService inMemory = LocalS3VectorsManager.createInMemory(dataPath).s3VectorsService();
    inMemory.deleteVectorBucket(BUCKET);

    assertThrows(Exception.class, () -> inMemory.getVectorBucket(BUCKET));
    assertEquals(1, LocalS3VectorsManager.createFileSystem(dataPath).s3VectorsService()
        .listVectorBuckets(null, null, null).getVectorBuckets().size());
  }

  /**
   * A reset restores the vectors of the data path, dropping what the service changed, and the service keeps working.
   */
  @Test
  void resetRestoresTheVectorsOfTheDataPath() {
    LocalS3VectorsManager persistentManager = LocalS3VectorsManager.createFileSystem(dataPath);
    S3VectorsService persistent = persistentManager.s3VectorsService();
    persistent.createVectorBucket(BUCKET, null);
    persistent.createIndex(BUCKET, INDEX, VectorDataType.FLOAT32, 2, DistanceMetric.EUCLIDEAN, null);
    persistent.putVectors(BUCKET, INDEX, List.of(vector("a", 1.0f, 0.0f), vector("b", 0.0f, 1.0f)));
    assertThrows(UnsupportedOperationException.class, persistentManager::reset);

    LocalS3VectorsManager manager = LocalS3VectorsManager.createInMemory(dataPath);
    S3VectorsService inMemory = manager.s3VectorsService();
    inMemory.putVectors(BUCKET, INDEX, List.of(vector("c", 1.0f, 1.0f)));
    inMemory.deleteVectors(BUCKET, INDEX, List.of("a"));
    inMemory.createVectorBucket("another-bucket", null);
    assertEquals(new VectorStatistics(2, 1, 2), manager.statistics());

    manager.reset();

    assertEquals(new VectorStatistics(1, 1, 2), manager.statistics());
    Map<String, float[]> vectors = getVectors(inMemory, "a", "b", "c");
    assertArrayEquals(new float[] {1.0f, 0.0f}, vectors.get("a"));
    assertFalse(vectors.containsKey("c"));

    LocalS3VectorsManager empty = LocalS3VectorsManager.createInMemory();
    empty.s3VectorsService().createVectorBucket(BUCKET, null);
    empty.reset();
    assertEquals(new VectorStatistics(0, 0, 0), empty.statistics());
  }

  private static PutInputVector vector(String key, float... values) {
    return PutInputVector.builder().key(key).data(data(values)).build();
  }

  private static PutInputVector.VectorData data(float... values) {
    return PutInputVector.VectorData.builder().values(values).build();
  }

  private static Map<String, float[]> getVectors(S3VectorsService service, String... keys) {
    Map<String, float[]> vectors = new TreeMap<>();
    for (GetOutputVector vector : service.getVectors(BUCKET, INDEX, List.of(keys), true, false).getVectors()) {
      vectors.put(vector.getKey(), vector.getData().getValues());
    }
    return vectors;
  }

  private static Map<String, byte[]> snapshot(Path directory) {
    Map<String, byte[]> files = new TreeMap<>();
    try (Stream<Path> paths = Files.walk(directory)) {
      for (Path path : paths.filter(Files::isRegularFile).toList()) {
        files.put(directory.relativize(path).toString(), Files.readAllBytes(path));
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return files;
  }

}
