package com.robothy.s3.core.service.s3vectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.robothy.s3.core.model.internal.s3vectors.LocalS3VectorsMetadata;
import com.robothy.s3.core.model.internal.s3vectors.VectorBucketMetadata;
import com.robothy.s3.core.model.internal.s3vectors.VectorIndexMetadata;
import com.robothy.s3.core.model.internal.s3vectors.VectorObjectMetadata;
import com.robothy.s3.core.service.BucketGuard;
import com.robothy.s3.core.storage.s3vectors.VectorStorage;
import com.robothy.s3.datatypes.s3vectors.response.GetVectorsResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

class GetVectorsServiceTest {

  @Test
  void getVectors_withMissingData_answersTheKeyAsAnError() {
    TestGetVectorsService service = new TestGetVectorsService();
    when(service.mockVectorStorage.getVectorData(1L)).thenReturn(null);

    GetVectorsResponse response = service.getVectors("bucket", "index", List.of("vector1"), true, false);
    assertTrue(response.vectors().isEmpty());
    assertEquals(List.of("vector1"), response.errorVectorKeys());
  }

  @Test
  void getVectors_waitsForAChangeOfTheBucket() throws Exception {
    TestGetVectorsService service = new TestGetVectorsService();
    when(service.mockVectorStorage.getVectorData(1L)).thenReturn(new float[]{1.0f, 2.0f});

    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Thread writer = Thread.ofVirtual().start(() -> service.bucketGuard().write("bucket", () -> {
      locked.countDown();
      try {
        release.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return null;
    }));
    assertTrue(locked.await(5, TimeUnit.SECONDS));

    CompletableFuture<GetVectorsResponse> got = CompletableFuture.supplyAsync(
        () -> service.getVectors("bucket", "index", List.of("vector1"), true, false));
    assertThrows(TimeoutException.class, () -> got.get(200, TimeUnit.MILLISECONDS));

    release.countDown();
    writer.join();
    GetVectorsResponse response = got.get(5, TimeUnit.SECONDS);
    assertArrayEquals(new float[]{1.0f, 2.0f}, response.vectors().getFirst().data().getValues());
  }

  private static class TestGetVectorsService implements GetVectorsService {

    private final BucketGuard bucketGuard = BucketGuard.inMemory();
    private final LocalS3VectorsMetadata mockMetadata = mock(LocalS3VectorsMetadata.class);
    final VectorStorage mockVectorStorage = mock(VectorStorage.class);

    TestGetVectorsService() {
      VectorBucketMetadata bucketMetadata = new VectorBucketMetadata();
      bucketMetadata.setVectorBucketName("bucket");
      VectorIndexMetadata indexMetadata = new VectorIndexMetadata();
      indexMetadata.setIndexName("index");
      bucketMetadata.putIndexMetadata("index", indexMetadata);
      VectorObjectMetadata vector = new VectorObjectMetadata();
      vector.setVectorId("vector1");
      vector.setStorageId(1L);
      Map<String, VectorObjectMetadata> vectorObjects = new ConcurrentHashMap<>();
      vectorObjects.put("vector1", vector);
      indexMetadata.setVectorObjects(vectorObjects);
      Map<String, VectorBucketMetadata> bucketMap = new HashMap<>();
      bucketMap.put("bucket", bucketMetadata);
      when(mockMetadata.getVectorBucketMetadataMap()).thenReturn(bucketMap);
    }

    @Override
    public BucketGuard bucketGuard() {
      return bucketGuard;
    }

    @Override
    public LocalS3VectorsMetadata metadata() {
      return mockMetadata;
    }

    @Override
    public VectorStorage vectorStorage() {
      return mockVectorStorage;
    }
  }
}
