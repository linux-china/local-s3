package com.robothy.s3.core.service.s3vectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.core.exception.vectors.LocalS3VectorErrorType;
import com.robothy.s3.core.exception.vectors.LocalS3VectorException;
import com.robothy.s3.core.model.internal.s3vectors.LocalS3VectorsMetadata;
import com.robothy.s3.core.model.internal.s3vectors.VectorBucketMetadata;
import com.robothy.s3.core.service.DefaultBucketGuard;
import com.robothy.s3.core.service.locks.BucketLock;
import com.robothy.s3.core.service.manager.vectors.LocalS3VectorsManager;
import com.robothy.s3.core.storage.MetadataStore;
import com.robothy.s3.core.storage.s3vectors.TransactionalVectorStorage;
import com.robothy.s3.core.storage.s3vectors.VectorStorage;
import com.robothy.s3.core.util.JsonUtils;
import com.robothy.s3.datatypes.s3vectors.DistanceMetric;
import com.robothy.s3.datatypes.s3vectors.VectorDataType;
import com.robothy.s3.datatypes.s3vectors.request.PutInputVector;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * Putting a vector with the key of an existing vector replaces it, and deletes the data of the replaced vector. A put is
 * applied as a whole: an invalid vector rejects the request, and a put that fails to be persisted is rolled back.
 */
class PutVectorsServiceTest {

  private static final String BUCKET = "vector-bucket";

  private static final String INDEX = "index";

  @TempDir
  Path dataPath;

  @Test
  void replacingAVectorDeletesTheDataOfTheReplacedVector() {
    VectorStorage storage = VectorStorage.createInMemory();
    S3VectorsService service = S3VectorsService.create(new LocalS3VectorsMetadata(), storage);
    createIndex(service);

    service.putVectors(BUCKET, INDEX, List.of(vector("a", 1.0f, 0.0f)));
    service.putVectors(BUCKET, INDEX, List.of(vector("a", 2.0f, 2.0f)));

    assertEquals(1, storage.getStoredVectorCount());
    assertArrayEquals(new float[] {2.0f, 2.0f}, getVector(service, "a"));
  }

  @Test
  void aPersistentServiceLeavesNoFileOfAReplacedVector() {
    S3VectorsService service = LocalS3VectorsManager.createFileSystem(dataPath).s3VectorsService();
    createIndex(service);

    service.putVectors(BUCKET, INDEX, List.of(vector("a", 1.0f, 0.0f), vector("b", 0.0f, 1.0f)));
    service.putVectors(BUCKET, INDEX, List.of(vector("a", 2.0f, 2.0f)));
    // The same key twice in one request: only the data of the last one is kept.
    service.putVectors(BUCKET, INDEX, List.of(vector("b", 3.0f, 3.0f), vector("b", 4.0f, 4.0f)));

    assertEquals(2, vectorFileCount());
    S3VectorsService restarted = LocalS3VectorsManager.createFileSystem(dataPath).s3VectorsService();
    assertArrayEquals(new float[] {2.0f, 2.0f}, getVector(restarted, "a"));
    assertArrayEquals(new float[] {4.0f, 4.0f}, getVector(restarted, "b"));
  }

  @Test
  void aPutThatFailsToBePersistedChangesNeitherTheVectorsNorTheMetadata() {
    LocalS3VectorsMetadata metadata = new LocalS3VectorsMetadata();
    TransactionalVectorStorage storage = new TransactionalVectorStorage(VectorStorage.createInMemory());
    FailingStore store = new FailingStore();
    S3VectorsService service = S3VectorsService.create(metadata, storage, new DefaultBucketGuard<>(BucketLock.create(),
        bucketName -> metadata.getVectorBucketMetadata(bucketName).get(), store, storage,
        bucketName -> metadata.getVectorBucketMetadataMap().put(bucketName, store.fetch(bucketName))));
    createIndex(service);
    service.putVectors(BUCKET, INDEX, List.of(vector("a", 1.0f, 0.0f)));
    Long replaced = storageId(metadata, "a");

    store.failing = true;
    assertThrows(IllegalStateException.class,
        () -> service.putVectors(BUCKET, INDEX, List.of(vector("a", 2.0f, 2.0f), vector("b", 0.0f, 1.0f))));

    assertTrue(storage.vectorDataExists(replaced),
        "The persisted metadata still references the replaced vector, so its data is kept.");
    assertEquals(1, storage.getStoredVectorCount(), "The vectors written by the failed put are deleted.");
    assertEquals(replaced, storageId(metadata, "a"), "The in-memory metadata is reloaded from the store.");
    assertArrayEquals(new float[] {1.0f, 0.0f}, getVector(service, "a"));
    assertTrue(service.getVectors(BUCKET, INDEX, List.of("b"), true, false).vectors().isEmpty());

    store.failing = false;
    service.putVectors(BUCKET, INDEX, List.of(vector("a", 3.0f, 3.0f)));
    assertFalse(storage.vectorDataExists(replaced), "Once the vector bucket is persisted, the data is deleted.");
    assertEquals(1, storage.getStoredVectorCount());
    assertArrayEquals(new float[] {3.0f, 3.0f}, getVector(service, "a"));
  }

  @Test
  void anInvalidVectorRejectsTheWholeRequest() {
    VectorStorage storage = VectorStorage.createInMemory();
    S3VectorsService service = S3VectorsService.create(new LocalS3VectorsMetadata(), storage);
    createIndex(service);
    service.putVectors(BUCKET, INDEX, List.of(vector("a", 1.0f, 0.0f)));

    List<List<PutInputVector>> invalidRequests = List.of(
        List.of(vector("a", 2.0f, 2.0f), vector("b", 1.0f, 2.0f, 3.0f)),
        List.of(vector("a", 2.0f, 2.0f), vector("b", Float.NaN, 1.0f)),
        List.of(vector("a", 2.0f, 2.0f), vector(" ", 1.0f, 1.0f)),
        List.of(vector("a", 2.0f, 2.0f), PutInputVector.builder().key("b").build()),
        List.of());
    for (List<PutInputVector> request : invalidRequests) {
      LocalS3VectorException thrown = assertThrows(LocalS3VectorException.class,
          () -> service.putVectors(BUCKET, INDEX, request));
      assertEquals(LocalS3VectorErrorType.VALIDATION, thrown.getErrorType(), thrown.getMessage());
      assertEquals("ValidationException", thrown.getErrorType().getCode());
    }

    assertEquals(1, storage.getStoredVectorCount(), "No vector of a rejected request is stored.");
    assertArrayEquals(new float[] {1.0f, 0.0f}, getVector(service, "a"));
    assertTrue(service.getVectors(BUCKET, INDEX, List.of("b"), true, false).vectors().isEmpty());
  }

  @Test
  void theLimitsOfAwsOnKeysAndMetadataAreApplied() {
    S3VectorsService service = S3VectorsService.create(new LocalS3VectorsMetadata(), VectorStorage.createInMemory());
    service.createVectorBucket(BUCKET, null);
    service.createIndex(BUCKET, INDEX, VectorDataType.FLOAT32, 2, DistanceMetric.EUCLIDEAN, List.of("text"));

    service.putVectors(BUCKET, INDEX, List.of(vector("k".repeat(1024), 1.0f, 0.0f),
        vector("text", Map.of("text", "x".repeat(30 * 1024), "genre", "drama"), 1.0f, 0.0f)));

    List<PutInputVector> rejected = List.of(
        vector("k".repeat(1025), 1.0f, 0.0f),
        vector("big", Map.of("text", "x".repeat(41 * 1024)), 1.0f, 0.0f),
        vector("filterable", Map.of("genre", "x".repeat(3 * 1024)), 1.0f, 0.0f));
    for (PutInputVector vector : rejected) {
      LocalS3VectorException thrown = assertThrows(LocalS3VectorException.class,
          () -> service.putVectors(BUCKET, INDEX, List.of(vector)));
      assertEquals(LocalS3VectorErrorType.VALIDATION, thrown.getErrorType(), thrown.getMessage());
    }
  }

  @Test
  void aCosineIndexRejectsZeroVectors() {
    S3VectorsService service = S3VectorsService.create(new LocalS3VectorsMetadata(), VectorStorage.createInMemory());
    service.createVectorBucket(BUCKET, null);
    service.createIndex(BUCKET, INDEX, VectorDataType.FLOAT32, 2, DistanceMetric.COSINE, null);
    service.putVectors(BUCKET, INDEX, List.of(vector("a", 1.0f, 0.0f)));

    LocalS3VectorException putThrown = assertThrows(LocalS3VectorException.class,
        () -> service.putVectors(BUCKET, INDEX, List.of(vector("b", 1.0f, 1.0f), vector("c", 0.0f, -0.0f))));
    assertEquals(LocalS3VectorErrorType.VALIDATION, putThrown.getErrorType(), putThrown.getMessage());
    assertTrue(service.getVectors(BUCKET, INDEX, List.of("b", "c"), true, false).vectors().isEmpty());

    LocalS3VectorException queryThrown = assertThrows(LocalS3VectorException.class,
        () -> service.queryVectors(BUCKET, INDEX, PutInputVector.VectorData.builder().values(new float[2]).build(),
            1, true, false, null));
    assertEquals(LocalS3VectorErrorType.VALIDATION, queryThrown.getErrorType(), queryThrown.getMessage());
  }

  @Test
  void aEuclideanIndexAcceptsZeroVectors() {
    S3VectorsService service = S3VectorsService.create(new LocalS3VectorsMetadata(), VectorStorage.createInMemory());
    createIndex(service);

    service.putVectors(BUCKET, INDEX, List.of(vector("zero", 0.0f, 0.0f)));

    assertArrayEquals(new float[] {0.0f, 0.0f}, getVector(service, "zero"));
    assertEquals(1, service.queryVectors(BUCKET, INDEX,
        PutInputVector.VectorData.builder().values(new float[2]).build(), 1, true, false, null).vectors().size());
  }

  @Test
  void aRejectedPutLeavesNothingInThePersistentService() {
    S3VectorsService service = LocalS3VectorsManager.createFileSystem(dataPath).s3VectorsService();
    createIndex(service);
    service.putVectors(BUCKET, INDEX, List.of(vector("a", 1.0f, 0.0f)));

    assertThrows(LocalS3VectorException.class,
        () -> service.putVectors(BUCKET, INDEX, List.of(vector("b", 0.0f, 1.0f), vector("c", 1.0f))));

    assertEquals(1, vectorFileCount());
    S3VectorsService restarted = LocalS3VectorsManager.createFileSystem(dataPath).s3VectorsService();
    assertArrayEquals(new float[] {1.0f, 0.0f}, getVector(restarted, "a"));
    assertTrue(restarted.getVectors(BUCKET, INDEX, List.of("b", "c"), true, false).vectors().isEmpty());
  }

  private static void createIndex(S3VectorsService service) {
    service.createVectorBucket(BUCKET, null);
    service.createIndex(BUCKET, INDEX, VectorDataType.FLOAT32, 2, DistanceMetric.EUCLIDEAN, null);
  }

  private static PutInputVector vector(String key, float... values) {
    return PutInputVector.builder().key(key).data(PutInputVector.VectorData.builder().values(values).build()).build();
  }

  private static PutInputVector vector(String key, Map<String, String> metadata, float... values) {
    return PutInputVector.builder().key(key).data(PutInputVector.VectorData.builder().values(values).build())
        .metadata(JsonUtils.fromJson(JsonUtils.toJson(metadata), JsonNode.class)).build();
  }

  private static float[] getVector(S3VectorsService service, String key) {
    return service.getVectors(BUCKET, INDEX, List.of(key), true, false).vectors().get(0).data().getValues();
  }

  private static Long storageId(LocalS3VectorsMetadata metadata, String key) {
    return metadata.getVectorBucketMetadata(BUCKET).get().getIndexMetadata(INDEX).get().getVectorObject(key)
        .getStorageId();
  }

  /**
   * The number of vectors in the files of the data path.
   */
  private long vectorFileCount() {
    return VectorStorage.createReadOnlyFileSystem(LocalS3VectorsManager.vectorStorageDirectory(dataPath))
        .getStoredVectorCount();
  }

  private static final class FailingStore implements MetadataStore<VectorBucketMetadata> {

    /**
     * The JSON of the stored vector buckets, so that a fetched bucket is a copy, like one read from a file.
     */
    private final Map<String, String> stored = new ConcurrentHashMap<>();

    private boolean failing;

    @Override
    public VectorBucketMetadata fetch(String name) {
      return JsonUtils.fromJson(stored.get(name), VectorBucketMetadata.class);
    }

    @Override
    public String store(String name, VectorBucketMetadata metadata) {
      if (failing) {
        throw new IllegalStateException("The disk is full.");
      }
      stored.put(name, JsonUtils.toJson(metadata));
      return name;
    }

    @Override
    public boolean exists(String name) {
      return stored.containsKey(name);
    }

    @Override
    public void delete(String name) {
      stored.remove(name);
    }

    @Override
    public List<VectorBucketMetadata> fetchAll() {
      return stored.keySet().stream().map(this::fetch).toList();
    }
  }

}
